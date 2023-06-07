/*
 * Copyright 2023 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.agentclientrelationships.auth

import play.api.Logging
import play.api.mvc._
import uk.gov.hmrc.agentclientrelationships.controllers.ErrorResults._
import uk.gov.hmrc.agentclientrelationships.model._
import uk.gov.hmrc.agentmtdidentifiers.model.{Enrolment => _, _}
import uk.gov.hmrc.auth.core.AffinityGroup.{Individual, Organisation}
import uk.gov.hmrc.auth.core.AuthProvider.{GovernmentGateway, PrivilegedApplication}
import uk.gov.hmrc.auth.core._
import uk.gov.hmrc.auth.core.retrieve.v2.Retrievals._
import uk.gov.hmrc.auth.core.retrieve.{Credentials, ~}
import uk.gov.hmrc.domain.TaxIdentifier
import uk.gov.hmrc.http.{HeaderCarrier, HeaderNames}

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}
import scala.util.matching.Regex

case class CurrentUser(credentials: Credentials, affinityGroup: Option[AffinityGroup])

trait AuthActions extends AuthorisedFunctions with Logging {
  me: Results =>

  override def authConnector: AuthConnector

  protected type RequestAndCurrentUser = CurrentUser => Future[Result]

  val supportedServices: Seq[Service]

  def authorisedUser(arn: Arn, clientId: TaxIdentifier, strideRoles: Seq[String])(
    body: RequestAndCurrentUser)(implicit ec: ExecutionContext, hc: HeaderCarrier): Future[Result] =
    authorised().retrieve(allEnrolments and affinityGroup and credentials) {
      case enrolments ~ affinity ~ optCreds =>
        optCreds
          .collect {
            case creds @ Credentials(_, "GovernmentGateway")
                if hasRequiredEnrolmentMatchingIdentifier(enrolments, affinity, Some(arn), clientId) =>
              creds
            case creds @ Credentials(_, "PrivilegedApplication") if hasRequiredStrideRole(enrolments, strideRoles) =>
              creds
          }
          .map { creds =>
            body(CurrentUser(creds, affinity))
          }
          .getOrElse(Future successful NoPermissionToPerformOperation)
    }

  def authorisedClientOrStrideUserOrAgent(clientId: TaxIdentifier, strideRoles: Seq[String])(
    body: RequestAndCurrentUser)(implicit ec: ExecutionContext, hc: HeaderCarrier): Future[Result] =
    authorised().retrieve(allEnrolments and affinityGroup and credentials) {
      case enrolments ~ affinity ~ optCreds =>
        optCreds
          .collect {
            case creds @ Credentials(_, "GovernmentGateway")
                if isAgent(affinity) | hasRequiredEnrolmentMatchingIdentifier(enrolments, affinity, None, clientId) =>
              creds
            case creds @ Credentials(_, "PrivilegedApplication") if hasRequiredStrideRole(enrolments, strideRoles) =>
              creds
          }
          .map { creds =>
            body(CurrentUser(creds, affinity))
          }
          .getOrElse(Future successful NoPermissionToPerformOperation)
    }

  def hasRequiredEnrolmentMatchingIdentifier(
    enrolments: Enrolments,
    affinity: Option[AffinityGroup],
    arn: Option[Arn] = None,
    clientId: TaxIdentifier): Boolean =
    affinity
      .flatMap {
        case AffinityGroup.Agent => arn
        case _                   => Some(clientId)
      }
      .exists(
        requiredIdentifier =>
          TypeOfEnrolment(requiredIdentifier)
            .extractIdentifierFrom(enrolments.enrolments)
            .contains(requiredIdentifier))

  def isAgent(affinity: Option[AffinityGroup]): Boolean =
    affinity.contains(AffinityGroup.Agent)

  def hasRequiredStrideRole(enrolments: Enrolments, strideRoles: Seq[String]): Boolean =
    strideRoles.exists(s => enrolments.enrolments.exists(_.key == s))

  private val supportedIdentifierKeys: String => String = {
    case EnrolmentMtdIt.enrolmentKey   => EnrolmentMtdIt.identifierKey
    case EnrolmentMtdVat.enrolmentKey  => EnrolmentMtdVat.identifierKey
    case EnrolmentTrust.enrolmentKey   => EnrolmentTrust.identifierKey
    case EnrolmentTrustNT.enrolmentKey => EnrolmentTrustNT.identifierKey
    case EnrolmentCgt.enrolmentKey     => EnrolmentCgt.identifierKey
    case EnrolmentPpt.enrolmentKey     => EnrolmentPpt.identifierKey
  }

  private def supportedEnrolments(enrolment: Enrolment): Option[Enrolment] =
    enrolment.key match {
      case EnrolmentMtdIt.enrolmentKey | EnrolmentMtdVat.enrolmentKey | EnrolmentTrust.enrolmentKey |
          EnrolmentTrustNT.enrolmentKey | EnrolmentCgt.enrolmentKey | EnrolmentPpt.enrolmentKey =>
        Some(enrolment)
      case _ => None
    }

  private def extractIdentifier(enrolment: Enrolment, service: String) =
    enrolment.getIdentifier(supportedIdentifierKeys(service)).map { i =>
      i.key match {
        case "MTDITID"                => MtdItId(i.value)
        case "VRN"                    => Vrn(i.value)
        case "SAUTR"                  => Utr(i.value)
        case "URN"                    => Urn(i.value)
        case "CGTPDRef"               => CgtRef(i.value)
        case "EtmpRegistrationNumber" => PptRef(i.value)
      }
    }

  def authorisedAsClient[A](service: String)(
    body: TaxIdentifier => Future[Result])(implicit ec: ExecutionContext, hc: HeaderCarrier): Future[Result] =
    authorised(Enrolment(service) and AuthProviders(GovernmentGateway))
      .retrieve(authorisedEnrolments and affinityGroup) {
        case enrolments ~ affinityG =>
          affinityG match {
            case Some(Individual) | Some(Organisation) =>
              val id: Option[TaxIdentifier] = for {
                enrolment  <- enrolments.getEnrolment(service).flatMap(supportedEnrolments)
                identifier <- extractIdentifier(enrolment, service)
              } yield identifier

              id match {
                case Some(i) => body(i)
                case _       => Future.successful(NoPermissionToPerformOperation)
              }
            case _ => Future.successful(NoPermissionToPerformOperation)
          }
      }

  //BTA & PTA Call
  def withAuthorisedAsClient[A, T](body: Map[Service, EnrolmentIdentifierValue] => Future[Result])(
    implicit ec: ExecutionContext,
    hc: HeaderCarrier): Future[Result] =
    authorised(AuthProviders(GovernmentGateway) and (Individual or Organisation))
      .retrieve(allEnrolments) { enrolments =>
        val identifiers: Map[Service, EnrolmentIdentifierValue] = (for {
          supportedService <- supportedServices // TODO DG PIR was not here.
          enrolment        <- enrolments.getEnrolment(supportedService.enrolmentKey)
          clientId         <- enrolment.identifiers.headOption
        } yield (supportedService, EnrolmentIdentifierValue(clientId.value))).toMap

        identifiers match {
          case s if s.isEmpty => Future.successful(NoPermissionToPerformOperation)
          case _              => body(identifiers)
        }
      }

  def withTerminationAuth(strideRole: String)(
    action: => Future[Result])(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Result] =
    authorised(AuthProviders(PrivilegedApplication) and Enrolment(strideRole)) {
      action
    }.recover {
      case _: NoActiveSession =>
        logger.warn(s"user not logged in")
        Unauthorized
      case _: InsufficientEnrolments =>
        logger.warn(s"stride user doesn't have permission to terminate an agent")
        Forbidden
      case _: UnsupportedAuthProvider =>
        logger.warn(s"user logged in with unsupported auth provider")
        Forbidden
    }

  protected def authorisedWithStride(oldStrideRole: String, newStrideRole: String)(
    body: String => Future[Result])(implicit ec: ExecutionContext, hc: HeaderCarrier): Future[Result] =
    authorised((Enrolment(oldStrideRole) or Enrolment(newStrideRole)) and AuthProviders(PrivilegedApplication))
      .retrieve(credentials) {
        case Some(Credentials(strideId, _)) => body(strideId)
        case _                              => Future.successful(NoPermissionToPerformOperation)
      }

  val basicAuthHeader: Regex = "Basic (.+)".r
  val decodedAuth: Regex = "(.+):(.+)".r

  private def decodeFromBase64(encodedString: String): String =
    try {
      new String(Base64.getDecoder.decode(encodedString), UTF_8)
    } catch { case _: Throwable => "" }

  def withBasicAuth(expectedAuth: BasicAuthentication)(body: => Future[Result])(
    implicit request: Request[_]): Future[Result] =
    request.headers.get(HeaderNames.authorisation) match {
      case Some(basicAuthHeader(encodedAuthHeader)) =>
        decodeFromBase64(encodedAuthHeader) match {
          case decodedAuth(username, password) =>
            if (BasicAuthentication(username, password) == expectedAuth) { body } else {
              logger.warn("Authorization header found in the request but invalid username or password")
              Future successful Unauthorized
            }
          case _ =>
            logger.warn("Authorization header found in the request but its not in the expected format")
            Future successful Unauthorized
        }
      case _ =>
        logger.warn("No Authorization header found in the request for agent termination")
        Future successful Unauthorized
    }

  protected def withAuthorisedAsAgent[A](
    body: Arn => Future[Result])(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Result] =
    withEnrolledAsAgent {
      case Some(arn) => body(Arn(arn))
      case None      => Future.failed(InsufficientEnrolments("AgentReferenceNumber identifier not found"))
    } recoverWith {
      case _: InsufficientEnrolments => Future.failed(InsufficientEnrolments())
    }

  protected def withEnrolledAsAgent[A](
    body: Option[String] => Future[Result])(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Result] =
    authorised(
      Enrolment("HMRC-AS-AGENT")
        and AuthProviders(GovernmentGateway))
      .retrieve(authorisedEnrolments) { enrolments =>
        val id = getEnrolmentValue(enrolments, "HMRC-AS-AGENT", "AgentReferenceNumber")
        body(id)
      }

  private def getEnrolmentValue(enrolments: Enrolments, serviceName: String, identifierKey: String) =
    for {
      enrolment  <- enrolments.getEnrolment(serviceName)
      identifier <- enrolment.getIdentifier(identifierKey)
    } yield identifier.value

}

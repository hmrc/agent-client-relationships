/*
 * Copyright 2025 HM Revenue & Customs
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

import uk.gov.hmrc.agentclientrelationships.util.RequestAwareLogging
import play.api.mvc._
import uk.gov.hmrc.agentclientrelationships.controllers.ErrorResults._
import uk.gov.hmrc.agentclientrelationships.model.{EnrolmentKey => LocalEnrolmentKey, _}
import uk.gov.hmrc.agentmtdidentifiers.model.{Enrolment => _, _}
import uk.gov.hmrc.auth.core.AffinityGroup.Individual
import uk.gov.hmrc.auth.core.AffinityGroup.Organisation
import uk.gov.hmrc.auth.core.AuthProvider.GovernmentGateway
import uk.gov.hmrc.auth.core.AuthProvider.PrivilegedApplication
import uk.gov.hmrc.auth.core._
import uk.gov.hmrc.auth.core.retrieve.v2.Retrievals._
import uk.gov.hmrc.auth.core.retrieve.Credentials
import uk.gov.hmrc.auth.core.retrieve.~
import uk.gov.hmrc.domain.TaxIdentifier
import uk.gov.hmrc.http.HeaderNames

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.matching.Regex
import uk.gov.hmrc.agentclientrelationships.util.RequestSupport._

case class CurrentUser(
  credentials: Option[Credentials],
  affinityGroup: Option[AffinityGroup]
) {
  def isStride: Boolean = credentials.map(_.providerType).contains("PrivilegedApplication")
}

trait AuthActions
extends AuthorisedFunctions
with RequestAwareLogging {
  me: Results =>

  implicit val executionContext: ExecutionContext

  override def authConnector: AuthConnector

  val supportedServices: Seq[Service]

  def authorisedUser(
    arn: Option[Arn],
    clientId: TaxIdentifier,
    strideRoles: Seq[String]
  )(body: CurrentUser => Future[Result])(implicit request: RequestHeader): Future[Result] =
    authorised().retrieve(allEnrolments and affinityGroup and credentials) { case enrolments ~ affinity ~ optCreds =>
      optCreds
        .collect {
          case creds @ Credentials(_, "GovernmentGateway")
              if hasRequiredEnrolmentMatchingIdentifier(
                enrolments,
                affinity,
                arn,
                clientId
              ) =>
            creds
          case creds @ Credentials(_, "PrivilegedApplication") if hasRequiredStrideRole(enrolments, strideRoles) => creds
        }
        .map { creds =>
          body(CurrentUser(Option(creds), affinity))
        }
        .getOrElse(Future successful NoPermissionToPerformOperation)
    }

  def authorisedClientOrStrideUserOrAgent(
    clientId: TaxIdentifier,
    strideRoles: Seq[String]
  )(body: CurrentUser => Future[Result])(implicit request: RequestHeader): Future[Result] =
    authorised().retrieve(allEnrolments and affinityGroup and credentials) { case enrolments ~ affinity ~ optCreds =>
      optCreds
        .collect {
          case creds @ Credentials(_, "GovernmentGateway")
              if isAgent(affinity) |
                hasRequiredEnrolmentMatchingIdentifier(
                  enrolments,
                  affinity,
                  None,
                  clientId
                ) =>
            creds
          case creds @ Credentials(_, "PrivilegedApplication") if hasRequiredStrideRole(enrolments, strideRoles) => creds
        }
        .map { creds =>
          body(CurrentUser(Option(creds), affinity))
        }
        .getOrElse(Future successful NoPermissionToPerformOperation)
    }

  private def hasRequiredEnrolmentMatchingIdentifier(
    enrolments: Enrolments,
    affinity: Option[AffinityGroup],
    arn: Option[Arn],
    clientId: TaxIdentifier
  ): Boolean = affinity
    .flatMap {
      case AffinityGroup.Agent => arn
      case _ => Some(clientId)
    }
    .exists { requiredIdentifier =>
      // check that among the identifiers that the user has, there is one that matches the clientId provided
      requiredIdentifier match {
        // need to handle Arn separately as it is not one of our managed services
        case Arn(arn) =>
          enrolments.enrolments
            .exists(enrolment =>
              enrolment.key == "HMRC-AS-AGENT" &&
                enrolment.identifiers.contains(EnrolmentIdentifier("AgentReferenceNumber", arn))
            )
        case taxId: TaxIdentifier =>
          val requiredTaxIdType = ClientIdentifier(taxId).enrolmentId
          enrolments.enrolments
            .flatMap(_.identifiers)
            .filter(_.key == requiredTaxIdType)
            .exists(
              _.value.replace(" ", "") == requiredIdentifier.value.replace(" ", "")
            ) // In case NINO comes back without spaces
      }
    }

  private def isAgent(affinity: Option[AffinityGroup]): Boolean = affinity.contains(AffinityGroup.Agent)

  def hasRequiredStrideRole(
    enrolments: Enrolments,
    strideRoles: Seq[String]
  ): Boolean = strideRoles.exists(s => enrolments.enrolments.exists(_.key == s))

  private def typedIdentifier(enrolment: Enrolment): Option[TaxIdentifier] = {
    val service = Service.forId(enrolment.key)
    val clientIdType = service.supportedClientIdType
    enrolment.getIdentifier(clientIdType.enrolmentId).map(eid => clientIdType.createUnderlying(eid.value))
  }

  def authorisedAsClient[A](
    serviceKey: String
  )(body: TaxIdentifier => Future[Result])(implicit request: RequestHeader): Future[Result] =
    authorised(Enrolment(serviceKey) and AuthProviders(GovernmentGateway)).retrieve(
      authorisedEnrolments and affinityGroup
    ) { case enrolments ~ affinityG =>
      affinityG match {
        case Some(Individual) | Some(Organisation) =>
          val id =
            if (supportedServices.exists(_.id == serviceKey)) {
              enrolments.getEnrolment(serviceKey).flatMap(typedIdentifier)
            }
            else
              None

          id match {
            case Some(i) => body(i)
            case _ => Future.successful(NoPermissionToPerformOperation)
          }
        case _ => Future.successful(NoPermissionToPerformOperation)
      }
    }

  // Authorisation request response is a special case where we need to check for multiple services
  def withAuthorisedClientForServiceKeys[
    A,
    T
  ](
    serviceKeys: Seq[String]
  )(body: Seq[LocalEnrolmentKey] => Future[Result])(implicit request: RequestHeader): Future[Result] =
    authorised(AuthProviders(GovernmentGateway) and (Individual or Organisation)).retrieve(allEnrolments) {
      enrolments =>
        val requiredEnrolments =
          for {
            serviceKey <- serviceKeys
            enrolment <- enrolments.getEnrolment(serviceKey)
          } yield LocalEnrolmentKey(serviceKey, enrolment.identifiers.map(id => Identifier(id.key, id.value)))

        requiredEnrolments match {
          case s if s.isEmpty => Future.successful(NoPermissionToPerformOperation)
          case _ => body(requiredEnrolments)
        }
    }

  def withAuthorisedAsClient[A, T](body: Map[Service, TaxIdentifier] => Future[Result])(implicit request: RequestHeader): Future[Result] =
    authorised(AuthProviders(GovernmentGateway) and (Individual or Organisation)).retrieve(allEnrolments) {
      enrolments =>
        val identifiers =
          for {
            supportedService <- supportedServices
            enrolment <- enrolments.getEnrolment(supportedService.enrolmentKey)
            clientId <- enrolment.identifiers.headOption
          } yield (supportedService, supportedService.supportedClientIdType.createUnderlying(clientId.value))

        identifiers match {
          case s if s.isEmpty => Future.successful(NoPermissionToPerformOperation)
          case _ => body(identifiers.toMap)
        }
    }

  def withAuthorisedAsClientWithNino(
    body: EnrolmentsWithNino => Future[Result]
  )(implicit request: RequestHeader): Future[Result] =
    authorised(AuthProviders(GovernmentGateway) and (Individual or Organisation)).retrieve(allEnrolments and nino) {
      case enrolments ~ nino => body(new EnrolmentsWithNino(enrolments, nino))
    }

  protected def authorisedWithStride(
    oldStrideRole: String,
    newStrideRole: String
  )(body: String => Future[Result])(implicit request: RequestHeader): Future[Result] =
    authorised((Enrolment(oldStrideRole) or Enrolment(newStrideRole)) and AuthProviders(PrivilegedApplication))
      .retrieve(credentials) {
        case Some(Credentials(strideId, _)) => body(strideId)
        case _ => Future.successful(NoPermissionToPerformOperation)
      }

  protected def authorisedWithStride(
    strideRole: String
  )(body: String => Future[Result])(implicit request: RequestHeader): Future[Result] =
    authorised((Enrolment(strideRole)) and AuthProviders(PrivilegedApplication))
      .retrieve(credentials) {
        case Some(Credentials(strideId, _)) => body(strideId)
        case _ => Future.successful(NoPermissionToPerformOperation)
      }

  val basicAuthHeader: Regex = "Basic (.+)".r
  val decodedAuth: Regex = "(.+):(.+)".r

  private def decodeFromBase64(encodedString: String): String =
    try new String(Base64.getDecoder.decode(encodedString), UTF_8)
    catch {
      case _: Throwable => ""
    }

  def withBasicAuth(
    expectedAuth: BasicAuthentication
  )(body: => Future[Result])(implicit request: Request[_]): Future[Result] =
    request.headers.get(HeaderNames.authorisation) match {
      case Some(basicAuthHeader(encodedAuthHeader)) =>
        decodeFromBase64(encodedAuthHeader) match {
          case decodedAuth(username, password) =>
            if (BasicAuthentication(username, password) == expectedAuth) {
              body
            }
            else {
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

  protected def withAuthorisedAsAgent[A](body: Arn => Future[Result])(implicit request: RequestHeader): Future[Result] =
    withEnrolledAsAgent {
      case Some(arn) => body(Arn(arn))
      case None => Future.failed(InsufficientEnrolments("AgentReferenceNumber identifier not found"))
    } recoverWith { case _: InsufficientEnrolments => Future.failed(InsufficientEnrolments()) }

  protected def withEnrolledAsAgent[A](
    body: Option[String] => Future[Result]
  )(implicit request: RequestHeader): Future[Result] =
    authorised(Enrolment("HMRC-AS-AGENT") and AuthProviders(GovernmentGateway)).retrieve(authorisedEnrolments) {
      enrolments =>
        val id = getEnrolmentValue(
          enrolments,
          "HMRC-AS-AGENT",
          "AgentReferenceNumber"
        )
        body(id)
    }

  private def getEnrolmentValue(
    enrolments: Enrolments,
    serviceName: String,
    identifierKey: String
  ) =
    for {
      enrolment <- enrolments.getEnrolment(serviceName)
      identifier <- enrolment.getIdentifier(identifierKey)
    } yield identifier.value

}

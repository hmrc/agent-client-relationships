/*
 * Copyright 2018 HM Revenue & Customs
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

import play.api.mvc._
import uk.gov.hmrc.agentclientrelationships.controllers.ErrorResults._
import uk.gov.hmrc.agentclientrelationships.model.{EnrolmentMtdIt, EnrolmentMtdVat, TypeOfEnrolment}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId, Vrn}
import uk.gov.hmrc.auth.core.AuthProvider.{GovernmentGateway, PrivilegedApplication}
import uk.gov.hmrc.auth.core._
import uk.gov.hmrc.auth.core.retrieve.Retrievals._
import uk.gov.hmrc.auth.core.retrieve.{Credentials, ~}
import uk.gov.hmrc.domain.TaxIdentifier
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.HeaderCarrierConverter
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext._

import scala.concurrent.Future

case class CurrentUser(credentials: Credentials, affinityGroup: Option[AffinityGroup])

trait AuthActions extends AuthorisedFunctions {
  me: Results =>

  override def authConnector: AuthConnector

  protected type RequestAndCurrentUser = Request[AnyContent] => CurrentUser => Future[Result]

  def AuthorisedAgentOrClientOrStrideUser(arn: Arn, clientId: TaxIdentifier, strideRole: String)(
    body: RequestAndCurrentUser): Action[AnyContent] =
    Action.async { implicit request =>
      implicit val hc: HeaderCarrier = HeaderCarrierConverter.fromHeadersAndSession(request.headers, None)
      authorised().retrieve(allEnrolments and affinityGroup and credentials) {
        case enrolments ~ affinity ~ creds =>
          creds.providerType match {
            case "GovernmentGateway" if hasRequiredEnrolmentMatchingIdentifier(enrolments, affinity, arn, clientId) =>
              body(request)(CurrentUser(creds, affinity))
            case "PrivilegedApplication" if hasRequiredStrideRole(enrolments, strideRole) =>
              body(request)(CurrentUser(creds, None))
            case _ =>
              Future successful NoPermissionToPerformOperation
          }
      }
    }

  def hasRequiredEnrolmentMatchingIdentifier(
    enrolments: Enrolments,
    affinity: Option[AffinityGroup],
    arn: Arn,
    clientId: TaxIdentifier): Boolean =
    affinity
      .map {
        case AffinityGroup.Agent => arn
        case _                   => clientId
      }
      .exists(
        requiredIdentifier =>
          TypeOfEnrolment(requiredIdentifier)
            .extractIdentifierFrom(enrolments.enrolments)
            .contains(requiredIdentifier))

  def hasRequiredStrideRole(enrolments: Enrolments, strideRole: String): Boolean =
    enrolments.enrolments.exists(_.key.toUpperCase() == strideRole)

  def AuthorisedAsItSaClient[A] = AuthorisedAsClient(EnrolmentMtdIt, MtdItId.apply) _

  def AuthorisedAsVatClient[A] = AuthorisedAsClient(EnrolmentMtdVat, Vrn.apply) _

  private def AuthorisedAsClient[A, T](enrolmentType: TypeOfEnrolment, wrap: String => T)(
    body: Request[AnyContent] => T => Future[Result]): Action[AnyContent] = Action.async { implicit request =>
    implicit val hc = HeaderCarrierConverter.fromHeadersAndSession(request.headers, None)

    authorised(Enrolment(enrolmentType.enrolmentKey) and AuthProviders(GovernmentGateway))
      .retrieve(authorisedEnrolments and affinityGroup) {
        case enrolments ~ _ =>
          val id = for {
            enrolment  <- enrolments.getEnrolment(enrolmentType.enrolmentKey)
            identifier <- enrolment.getIdentifier(enrolmentType.identifierKey)
          } yield identifier.value

          id match {
            case Some(x) => body(request)(wrap(x))
            case _       => Future.successful(NoPermissionToPerformOperation)
          }
      }
  }

  protected def AuthorisedWithStride(strideRole: String)(body: Request[AnyContent] => String => Future[Result]) =
    Action.async { implicit request =>
      implicit val hc = HeaderCarrierConverter.fromHeadersAndSession(request.headers, None)
      authorised(Enrolment(strideRole) and AuthProviders(PrivilegedApplication))
        .retrieve(credentials) {
          case Credentials(strideId, _) => body(request)(strideId)
        }
    }

  protected def withAuthorisedAsAgent[A](
    body: Arn => Future[Result])(implicit request: Request[A], hc: HeaderCarrier): Future[Result] =
    withEnrolledAsAgent {
      case Some(arn) => body(Arn(arn))
      case None      => Future.failed(InsufficientEnrolments("AgentReferenceNumber identifier not found"))
    } recoverWith {
      case _: InsufficientEnrolments => Future.failed(InsufficientEnrolments())
    }

  protected def withEnrolledAsAgent[A](
    body: Option[String] => Future[Result])(implicit request: Request[A], hc: HeaderCarrier): Future[Result] =
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

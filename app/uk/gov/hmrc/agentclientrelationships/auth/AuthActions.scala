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

import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext._
import play.api.mvc._
import uk.gov.hmrc.agentclientrelationships.controllers.ErrorResults._
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentType
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentType.{EnrolmentMtdIt, EnrolmentMtdVat}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId, Vrn}
import uk.gov.hmrc.auth.core.AuthProvider.GovernmentGateway
import uk.gov.hmrc.domain.TaxIdentifier
import uk.gov.hmrc.auth.core._
import uk.gov.hmrc.auth.core.retrieve.~
import uk.gov.hmrc.auth.core.retrieve.Retrievals._

import scala.concurrent.Future
import uk.gov.hmrc.play.HeaderCarrierConverter

trait AuthActions extends AuthorisedFunctions {
  me: Results =>

  override def authConnector: AuthConnector

  protected type AsyncPlayUserRequest = Request[AnyContent] => Future[Result]

  def AuthorisedAgentOrClient(arn: Arn, clientId: TaxIdentifier)(body: AsyncPlayUserRequest): Action[AnyContent] = Action.async {
    implicit request =>
      implicit val hc = HeaderCarrierConverter.fromHeadersAndSession(request.headers, None)
      authorised(AuthProviders(GovernmentGateway)).retrieve(allEnrolments and affinityGroup) {
        case enrol ~ affinityG => {

          val requiredIdentifier = affinityG match {
            case Some(AffinityGroup.Agent) => arn
            case _ => clientId
          }

          val requiredEnrolmentType = EnrolmentType.enrolmentTypeFor(requiredIdentifier)

          val actualIdFromEnrolment: Option[TaxIdentifier] = requiredEnrolmentType.findEnrolmentIdentifier(enrol.enrolments)

          if (actualIdFromEnrolment.contains(requiredIdentifier)) {
            body(request)
          } else {
            Future successful NoPermissionOnAgencyOrClient
          }
        }
      }
  }

  def AuthorisedAsItsaClient[A](body: Request[AnyContent] => MtdItId => Future[Result]): Action[AnyContent] = Action.async { implicit request =>
    implicit val hc = HeaderCarrierConverter.fromHeadersAndSession(request.headers, None)

    authorised(
      Enrolment(EnrolmentMtdIt.enrolmentKey)
        and AuthProviders(GovernmentGateway))
      .retrieve(authorisedEnrolments and affinityGroup) {
        case enrolments ~ _ =>
          val id = for {
            enrolment <- enrolments.getEnrolment(EnrolmentMtdIt.enrolmentKey)
            identifier <- enrolment.getIdentifier(EnrolmentMtdIt.identifierKey)
          } yield identifier.value

          id.map(x => body(request)(MtdItId(x)))
            .getOrElse(Future.successful(NoPermissionOnClient))
      }
  }

  def AuthorisedAsVatClient[A](body: Request[AnyContent] => Vrn => Future[Result]): Action[AnyContent] = Action.async { implicit request =>
    implicit val hc = HeaderCarrierConverter.fromHeadersAndSession(request.headers, None)

    authorised(
      Enrolment(EnrolmentMtdVat.enrolmentKey)
        and AuthProviders(GovernmentGateway))
      .retrieve(authorisedEnrolments and affinityGroup) {
        case enrolments ~ _ =>
          val id = for {
            enrolment <- enrolments.getEnrolment(EnrolmentMtdVat.enrolmentKey)
            identifier <- enrolment.getIdentifier(EnrolmentMtdVat.identifierKey)
          } yield identifier.value

          id.map(x => body(request)(Vrn(x)))
            .getOrElse(Future.successful(NoPermissionOnClient))
      }
  }
}
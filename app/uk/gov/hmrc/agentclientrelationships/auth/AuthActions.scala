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
import uk.gov.hmrc.agentclientrelationships.model.{ EnrolmentMtdIt, EnrolmentMtdVat, TypeOfEnrolment }
import uk.gov.hmrc.agentmtdidentifiers.model.{ Arn, MtdItId, Vrn }
import uk.gov.hmrc.auth.core.AuthProvider.GovernmentGateway
import uk.gov.hmrc.auth.core._
import uk.gov.hmrc.auth.core.retrieve.Retrievals._
import uk.gov.hmrc.auth.core.retrieve.{ Credentials, ~ }
import uk.gov.hmrc.domain.TaxIdentifier
import uk.gov.hmrc.play.HeaderCarrierConverter
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext._

import scala.concurrent.Future

case class CurrentUser(credentials: Credentials, affinityGroup: Option[AffinityGroup])

trait AuthActions extends AuthorisedFunctions {
  me: Results =>

  override def authConnector: AuthConnector

  protected type RequestAndCurrentUser = Request[AnyContent] => CurrentUser => Future[Result]

  def AuthorisedAgentOrClient(arn: Arn, clientId: TaxIdentifier)(body: RequestAndCurrentUser): Action[AnyContent] = Action.async {
    implicit request =>
      implicit val hc = HeaderCarrierConverter.fromHeadersAndSession(request.headers, None)
      authorised(AuthProviders(GovernmentGateway)).retrieve(allEnrolments and affinityGroup and credentials) {
        case enrol ~ affinityG ~ creds => {

          val requiredIdentifier = affinityG match {
            case Some(AffinityGroup.Agent) => arn
            case _ => clientId
          }

          val requiredEnrolmentType = TypeOfEnrolment(requiredIdentifier)

          val actualIdFromEnrolment: Option[TaxIdentifier] = requiredEnrolmentType.findEnrolmentIdentifier(enrol.enrolments)

          if (actualIdFromEnrolment.contains(requiredIdentifier)) {
            body(request)(CurrentUser(creds, affinityG))
          } else {
            Future successful NoPermissionOnAgencyOrClient
          }
        }
      }
  }

  def AuthorisedAsItSaClient[A] = AuthorisedAsClient(EnrolmentMtdIt, MtdItId.apply)_

  def AuthorisedAsVatClient[A] = AuthorisedAsClient(EnrolmentMtdVat, Vrn.apply)_

  private def AuthorisedAsClient[A, T](enrolmentType: TypeOfEnrolment, wrap: String => T)(body: Request[AnyContent] => T => Future[Result]): Action[AnyContent] = Action.async { implicit request =>
    implicit val hc = HeaderCarrierConverter.fromHeadersAndSession(request.headers, None)

    authorised(
      Enrolment(enrolmentType.enrolmentKey) and AuthProviders(GovernmentGateway))
      .retrieve(authorisedEnrolments and affinityGroup) {
        case enrolments ~ _ =>
          val id = for {
            enrolment <- enrolments.getEnrolment(enrolmentType.enrolmentKey)
            identifier <- enrolment.getIdentifier(enrolmentType.identifierKey)
          } yield identifier.value

          id match {
            case Some(x) => body(request)(wrap(x))
            case _ => Future.successful(NoPermissionOnClient)
          }
      }
  }
}
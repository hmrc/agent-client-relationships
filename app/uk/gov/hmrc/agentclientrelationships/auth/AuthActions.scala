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

  private def getEnrolmentInfo(enrolment: Set[Enrolment], enrolmentKey: String, identifier: String): Option[String] =
    enrolment.find(_.key equals enrolmentKey).flatMap(_.identifiers.find(_.key equals identifier).map(_.value))

  protected type AsyncPlayUserRequest = Request[AnyContent] => AgentOrClientRequest[AnyContent] => Future[Result]

  def AuthorisedAgent(arn: Arn, clientId: TaxIdentifier)(body: AsyncPlayUserRequest): Action[AnyContent] = Action.async {
    implicit request =>
      implicit val hc = HeaderCarrierConverter.fromHeadersAndSession(request.headers, None)
      authorised(AuthProviders(GovernmentGateway)).retrieve(allEnrolments and affinityGroup) {
        case enrol ~ affinityG =>

          val idFromEnrolment: Option[TaxIdentifier] = affinityG match {
            case Some(AffinityGroup.Agent) => getEnrolmentInfo(enrol.enrolments, "HMRC-AS-AGENT", "AgentReferenceNumber").map(Arn.apply)
            case _ => clientId match {
                case _ : MtdItId => getEnrolmentInfo(enrol.enrolments, "HMRC-MTD-IT", "MTDITID").map(MtdItId.apply)
                case _ : Vrn => getEnrolmentInfo(enrol.enrolments, "HMRC-MTD-VAT", "MTDVATID").map(Vrn.apply)
                case _ => None
              }
            }

          idFromEnrolment.filter(id => id == clientId || id == arn) match {
            case Some(taxIdentifier) => body(request)(AgentOrClientRequest(taxIdentifier, request))
            case _ => Future successful NoPermissionOnAgencyOrClient
          }
      }
  }
}

case class AgentOrClientRequest[A](taxIdentifier: TaxIdentifier, request: Request[A]) extends WrappedRequest[A](request)

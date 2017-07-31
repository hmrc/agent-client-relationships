/*
 * Copyright 2017 HM Revenue & Customs
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

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.mvc._
import uk.gov.hmrc.agentclientrelationships.controllers.ErrorResults._
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}
import uk.gov.hmrc.auth.core.AffinityGroup.Agent
import uk.gov.hmrc.auth.core.AuthProvider.GovernmentGateway
import uk.gov.hmrc.auth.core.Retrievals._
import uk.gov.hmrc.auth.core._
import uk.gov.hmrc.play.http.{HeaderCarrier, Upstream4xxResponse}

import scala.concurrent.Future

trait AuthActions extends AuthorisedFunctions {
  me: Results =>

  override def authConnector: AuthConnector

  private def getEnrolmentInfo(enrolment: Set[Enrolment], enrolmentKey: String): Option[String] =
    enrolment.find(_.key equals enrolmentKey).flatMap(_.identifiers.find(_.key equals "AgentReferenceNumber").map(_.value))

  protected type AsyncPlayUserRequest = Request[AnyContent] => AgentOrClientRequest[AnyContent] => Future[Result]


  def AuthorisedAgent[A](body: AsyncPlayUserRequest): Action[AnyContent] = Action.async {
    implicit request =>
      implicit val hc = HeaderCarrier.fromHeadersAndSession(request.headers, None)
      authorised(AuthProviders(GovernmentGateway)).retrieve(allEnrolments and affinityGroup) {
        case enrol ~ affinityG =>
          affinityG match {
            case Some(Agent) => checkEnrolments(enrol) match {
              case Right(ids) => body(request)(AgentOrClientRequest(Some(ids._1), Some(ids._2), request))
              case Left(result) => Future successful result
            }
            case _ => Future successful NoAgentOrClient
          }
      }.recover({
        case e: Upstream4xxResponse if e.upstreamResponseCode == 401 => GenericUnauthorized
      })
  }

 private def checkEnrolments(enrol: Enrolments): Either[Result, (Arn, MtdItId)] = {
    (getEnrolmentInfo(enrol.enrolments, "HMRC-AS-AGENT"), getEnrolmentInfo(enrol.enrolments, "HMRC-MTD-IT")) match {
      case (Some(arn), Some(mtdItId)) =>
        (Arn.isValid(arn), MtdItId.isValid(mtdItId)) match {
          case (true, true) => Right(Arn(arn), MtdItId(mtdItId))
          case (false, false) => Left(NoAgentOrClient)
          case (_, _) => Left(NotFound)
        }
      case (None, None) => Left(NoPermissionOnAgencyOrClient)
      case (_, _) => Left(NoAgentOrClient)
    }
  }
}

case class AgentOrClientRequest[A](arn: Option[Arn], mtdItId: Option[MtdItId], request: Request[A]) extends WrappedRequest[A](request)

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

package uk.gov.hmrc.agentclientrelationships.controllers.actions

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import uk.gov.hmrc.agentclientrelationships.controllers.ErrorResults._
import uk.gov.hmrc.agentclientrelationships.connectors.{AuthConnector, Authority}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}
import uk.gov.hmrc.play.http.HeaderCarrier
import uk.gov.hmrc.play.http.Upstream4xxResponse
import play.api.mvc._

import scala.concurrent.Future

trait AuthActions {
  me: Results =>
  def authConnector: AuthConnector

  private val withAuthority = new ActionBuilder[RequestWithAuthority] with ActionRefiner[Request, RequestWithAuthority] {
    protected def refine[A](request: Request[A]): Future[Either[Result, RequestWithAuthority[A]]] = {
      implicit val hc = HeaderCarrier.fromHeadersAndSession(request.headers, None)
      authConnector.currentAuthority() map {
        case Some(authority@Authority(_)) => Right(new RequestWithAuthority(authority, request))
        case _ => Left(Unauthorized)
      }
    }
  }

  protected val agentOrClient = withAuthority andThen new ActionRefiner[RequestWithAuthority, AgentOrClient] {
    protected def refine[A](request: RequestWithAuthority[A]): Future[Either[Result, AgentOrClient[A]]] = {
      implicit val hc = HeaderCarrier.fromHeadersAndSession(request.headers, None)
      (for {
        opArn <- authConnector.currentArn(request.authority.enrolmentsUrl)
        opMtdItId <- authConnector.currentMtdItId(request.authority.enrolmentsUrl)
      } yield (opArn, opMtdItId) match {
        case (None, None) => Left(NoAgentOrClient)
        case (optArn, optMtdItId) => Right(AgentOrClient(optArn, optMtdItId, request))
      }).recover({
        case e: Upstream4xxResponse if e.upstreamResponseCode == 401 =>
          Left(GenericUnauthorized)
      })
    }
  }
}

class RequestWithAuthority[A](val authority: Authority, request: Request[A]) extends WrappedRequest[A](request)

case class AgentOrClient[A](arn: Option[Arn], mtdItId: Option[MtdItId], request: Request[A]) extends WrappedRequest[A](request)
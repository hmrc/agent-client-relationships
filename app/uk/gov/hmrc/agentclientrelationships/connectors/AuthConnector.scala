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

package uk.gov.hmrc.agentclientrelationships.connectors

import java.net.URL
import javax.inject.{Inject, Named}

import com.google.inject.Singleton
import play.api.libs.json.JsValue
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}
import uk.gov.hmrc.play.http.{HeaderCarrier, HttpGet, HttpReads, Upstream4xxResponse}
import uk.gov.hmrc.agentclientrelationships.model._

import scala.concurrent.{ExecutionContext, Future}

case class Authority(enrolmentsUrl: String)

@Singleton
class AuthConnector @Inject()(@Named("auth-baseUrl") baseUrl: URL, httpGet: HttpGet) {

  def currentAuthority()(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Option[Authority]] = {
    val response: Future[JsValue] = httpGetAs[JsValue]("/auth/authority")
    response flatMap { r =>
      for {
        enrolmentsUrl <- Future successful enrolmentsRelativeUrl(r)
      } yield Some(Authority(enrolmentsUrl))
    } recover {
      case error: Upstream4xxResponse if error.upstreamResponseCode == 401 => None
      case e => throw e
    }
  }

  def currentArn(enrolmentUrl: String)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Option[Arn]] =
    enrolments(enrolmentUrl).map(_.arnOption)

  def currentMtdItId(enrolmentsUrl: String)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Option[MtdItId]] =
    enrolments(enrolmentsUrl).map(_.mtdItIdOption)

  private def enrolments(enrolmentsUrl: String)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Enrolments] =
    httpGetAs[Set[AuthEnrolment]](enrolmentsUrl).map(Enrolments(_))

  private def enrolmentsRelativeUrl(authorityJson: JsValue) = (authorityJson \ "enrolments").as[String]

  private def url(relativeUrl: String): URL = new URL(baseUrl, relativeUrl)

  private def httpGetAs[T](relativeUrl: String)(implicit rds: HttpReads[T], hc: HeaderCarrier, ec: ExecutionContext): Future[T] =
    httpGet.GET[T](url(relativeUrl).toString)(rds, hc)
}

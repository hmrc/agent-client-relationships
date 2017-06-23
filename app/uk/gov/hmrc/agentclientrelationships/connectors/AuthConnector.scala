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
import javax.inject.{Inject, Named, Singleton}

import play.api.libs.json.JsValue
import uk.gov.hmrc.play.http.{HeaderCarrier, HttpGet, HttpReads, Upstream4xxResponse}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AuthConnector @Inject() (@Named("auth-baseUrl") baseUrl: URL, httpGet: HttpGet) {

  def currentAuthDetails()(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Option[AuthDetails]] =
    currentAuthority
      .map({ authority =>
        Some(AuthDetails(ggCredentialId(authority)))
          }
      ) recover {
      case ex: Upstream4xxResponse if ex.upstreamResponseCode == 401 => None
    }


  private def ggCredentialId(authorityJson: JsValue): Option[String] = {
    (authorityJson \ "credId").asOpt[String]
  }

  private def currentAuthority()(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[JsValue] =
    httpGetAs[JsValue]("/auth/authority")

  private def url(relativeUrl: String): URL = new URL(baseUrl, relativeUrl)

  private def httpGetAs[T](relativeUrl: String)(implicit rds: HttpReads[T], hc: HeaderCarrier, ec: ExecutionContext): Future[T] =
    httpGet.GET[T](url(relativeUrl).toString)(rds, hc)

}

case class AuthDetails(
  ggCredentialId: Option[String]
)

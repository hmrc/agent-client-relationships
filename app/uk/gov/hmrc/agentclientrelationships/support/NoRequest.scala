/*
 * Copyright 2020 HM Revenue & Customs
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

package uk.gov.hmrc.agentclientrelationships.support
import java.security.cert.X509Certificate

import play.api.mvc.{Headers, Request}

object NoRequest extends Request[Any] {
  override def body: Any = ""
  override def id: Long = 0L
  override def tags: Map[String, String] = Map()
  override def uri: String = ""
  override def path: String = ""
  override def method: String = ""
  override def version: String = ""
  override def queryString: Map[String, Seq[String]] = Map()
  override def headers: Headers = ???
  override def remoteAddress: String = ""
  override def secure: Boolean = false
  override def clientCertificateChain: Option[Seq[X509Certificate]] = ???
}

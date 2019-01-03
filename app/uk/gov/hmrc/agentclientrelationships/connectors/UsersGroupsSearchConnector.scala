/*
 * Copyright 2019 HM Revenue & Customs
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

import com.codahale.metrics.MetricRegistry
import com.kenshoo.play.metrics.Metrics
import play.api.libs.json._
import uk.gov.hmrc.agent.kenshoo.monitoring.HttpAPIMonitor
import uk.gov.hmrc.agentclientrelationships.support.RelationshipNotFound
import uk.gov.hmrc.domain.AgentCode
import uk.gov.hmrc.http._

import scala.concurrent.{ExecutionContext, Future}

case class GroupInfo(groupId: String, affinityGroup: Option[String], agentCode: Option[AgentCode])

object GroupInfo {
  implicit val formats: Format[GroupInfo] = Json.format[GroupInfo]
}

@Singleton
class UsersGroupsSearchConnector @Inject()(
  @Named("users-groups-search-baseUrl") baseUrl: URL,
  httpGet: HttpGet,
  metrics: Metrics)
    extends HttpAPIMonitor {
  override val kenshooRegistry: MetricRegistry = metrics.defaultRegistry

  def getGroupInfo(groupId: String)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[GroupInfo] = {
    val url = new URL(baseUrl, s"/users-groups-search/groups/$groupId")
    monitor(s"ConsumedAPI-UGS-getGroupInfo-GET") {
      httpGet.GET[GroupInfo](url.toString)
    } recoverWith {
      case _: NotFoundException => Future failed RelationshipNotFound("UNKNOWN_AGENT_CODE")
    }
  }
}

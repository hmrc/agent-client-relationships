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

package uk.gov.hmrc.agentclientrelationships.connectors

import java.net.URL
import javax.inject.{Inject, Named, Singleton}

import com.codahale.metrics.MetricRegistry
import com.kenshoo.play.metrics.Metrics
import play.api.Logger
import play.api.libs.json.JsObject
import uk.gov.hmrc.agent.kenshoo.monitoring.HttpAPIMonitor
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}
import uk.gov.hmrc.http.{HeaderCarrier, HttpGet}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class EnrolmentStoreProxyConnector @Inject()(@Named("enrolment-store-proxy-baseUrl") baseUrl: URL, httpGet: HttpGet, metrics: Metrics)
  extends HttpAPIMonitor {
  override val kenshooRegistry: MetricRegistry = metrics.defaultRegistry

  def getGroupIdFor(arn: Arn)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[String] = {
    val url = new URL(baseUrl, s"/enrolment-store-proxy/enrolment-store/enrolments/HMRC-AS-AGENT~AgentReferenceNumber~${arn.value}/groups?type=principal")
    monitor(s"ConsumedAPI-ES-getGroupIdForARN-GET") {
      httpGet.GET[JsObject](url.toString)
    }
      .map(json => {
        val groupIds = (json \ "principalGroupIds").as[Seq[String]]
        if(groupIds.isEmpty){
          throw RelationshipNotFound("UNKNOWN_GROUP_ID_FOR_ARN")
        } else {
          if (groupIds.lengthCompare(1) > 0) {
            Logger.warn(s"Multiple groupIds found for $arn: $groupIds")
          }
          groupIds.head
        }
      })
  }

  def getGroupIdsFor(mtdItId: MtdItId)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Set[String]] = {
    val url = new URL(baseUrl, s"/enrolment-store-proxy/enrolment-store/enrolments/HMRC-MTD-IT~MTDITID~${mtdItId.value}/groups?type=delegated")
    monitor(s"ConsumedAPI-ES-getGroupIdsForMTDITID-GET") {
      httpGet.GET[JsObject](url.toString)
    }
      .map(json => (json \ "delegatedGroupIds").as[Seq[String]].toSet)
  }

}

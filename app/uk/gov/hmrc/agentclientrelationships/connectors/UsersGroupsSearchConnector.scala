/*
 * Copyright 2023 HM Revenue & Customs
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
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import javax.inject.{Inject, Singleton}
import play.api.Logging
import play.api.http.Status
import play.api.libs.json._
import uk.gov.hmrc.agentclientrelationships.util.HttpAPIMonitor
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.domain.AgentCode
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient, HttpErrorFunctions, HttpResponse, UpstreamErrorResponse}

import scala.concurrent.{ExecutionContext, Future}

case class GroupInfo(groupId: String, affinityGroup: Option[String], agentCode: Option[AgentCode])

object GroupInfo {
  implicit val formats: Format[GroupInfo] = Json.format[GroupInfo]
}

case class CredentialRole(credentialRole: String)

object CredentialRole {
  implicit val formats: Format[CredentialRole] = Json.format
}

/** Cut down version of UserDetails from users-groups-search, with only the data we are interested in
  */
case class UserDetails(userId: Option[String] = None, credentialRole: Option[String] = None)

object UserDetails {
  implicit val formats: Format[UserDetails] = Json.format
}

@Singleton
class UsersGroupsSearchConnector @Inject() (httpGet: HttpClient)(implicit
  val metrics: Metrics,
  val appConfig: AppConfig,
  val ec: ExecutionContext
) extends HttpAPIMonitor
    with HttpErrorFunctions
    with Logging {
  def getGroupUsers(groupId: String)(implicit hc: HeaderCarrier): Future[Seq[UserDetails]] = {
    val url = new URL(s"${appConfig.userGroupsSearchUrl}/users-groups-search/groups/$groupId/users")
    monitor(s"ConsumedAPI-UGS-getGroupUsers-GET") {
      httpGet.GET[HttpResponse](url.toString).map { response =>
        response.status match {
          case status if is2xx(status) => response.json.as[Seq[UserDetails]]
          case Status.NOT_FOUND =>
            logger.warn(s"Group $groupId not found in SCP")
            throw UpstreamErrorResponse(s"Group $groupId not found in SCP", Status.NOT_FOUND)
          case other =>
            logger.error(s"Error in UGS-getGroupUsers: $other, ${response.body}")
            throw UpstreamErrorResponse(s"Error in UGS-getGroupUsers: $other, ${response.body}", other)
        }
      }
    }
  }

  def getFirstGroupAdminUser(
    groupId: String
  )(implicit hc: HeaderCarrier): Future[Option[UserDetails]] =
    getGroupUsers(groupId)
      .map(_.find(_.credentialRole.exists(_ == "Admin")))
      .recover { case e =>
        logger.error(s"Could not find admin user for groupId $groupId due to: $e")
        None
      }

  def getGroupInfo(groupId: String)(implicit hc: HeaderCarrier): Future[Option[GroupInfo]] = {
    val url = new URL(s"${appConfig.userGroupsSearchUrl}/users-groups-search/groups/$groupId")
    monitor(s"ConsumedAPI-UGS-getGroupInfo-GET") {
      httpGet.GET[HttpResponse](url.toString).map { response =>
        response.status match {
          case status if is2xx(status) => Some(response.json.as[GroupInfo])
          case Status.NOT_FOUND =>
            logger.warn(s"Group $groupId not found in SCP")
            None
          case other =>
            logger.error(s"Error in UGS-getGroupInfo: $other, ${response.body}")
            None
        }
      }
    }
  }
}

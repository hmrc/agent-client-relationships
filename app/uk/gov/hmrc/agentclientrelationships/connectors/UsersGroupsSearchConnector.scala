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

import javax.inject.Inject
import javax.inject.Singleton
import play.api.Logging
import play.api.http.Status
import play.api.libs.json._
import play.api.mvc.RequestHeader
import uk.gov.hmrc.agentclientrelationships.util.HttpApiMonitor
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.util.RequestSupport.hc
import uk.gov.hmrc.domain.AgentCode
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.HttpErrorFunctions
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.http.StringContextOps
import uk.gov.hmrc.http.UpstreamErrorResponse

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

case class GroupInfo(
  groupId: String,
  affinityGroup: Option[String],
  agentCode: Option[AgentCode]
)

object GroupInfo {
  implicit val formats: Format[GroupInfo] = Json.format[GroupInfo]
}

case class CredentialRole(credentialRole: String)

object CredentialRole {
  implicit val formats: Format[CredentialRole] = Json.format
}

/** Cut down version of UserDetails from users-groups-search, with only the data we are interested in
  */
case class UserDetails(
  userId: Option[String] = None,
  credentialRole: Option[String] = None
)

object UserDetails {
  implicit val formats: Format[UserDetails] = Json.format
}

@Singleton
class UsersGroupsSearchConnector @Inject() (
  httpClient: HttpClientV2,
  appConfig: AppConfig
)(implicit
  val metrics: Metrics,
  val ec: ExecutionContext
)
extends HttpApiMonitor
with HttpErrorFunctions
with Logging {

  def getGroupUsers(groupId: String)(implicit rh: RequestHeader): Future[Seq[UserDetails]] =
    monitor(s"ConsumedAPI-UGS-getGroupUsers-GET") {
      httpClient
        .get(url"${appConfig.userGroupsSearchUrl}/users-groups-search/groups/$groupId/users")
        .execute[HttpResponse]
        .map { response =>
          // TODO: Refactor error handling, use http verbs and standard `.execute[Seq[UserDetails]]` to yield required value
          // Current issues:
          // - Code relies on UpstreamErrorResponse exceptions being caught upstream
          // - Exception-based flow control makes code hard to understand
          // - Error recovery logic is hidden from the immediate context
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

  // TODO: move this transformation to the Service Layer
  def getFirstGroupAdminUser(groupId: String)(implicit rh: RequestHeader): Future[Option[UserDetails]] = getGroupUsers(
    groupId
  ).map(_.find(_.credentialRole.exists(_ == "Admin")))

  def getGroupInfo(groupId: String)(implicit rh: RequestHeader): Future[Option[GroupInfo]] =
    monitor(s"ConsumedAPI-UGS-getGroupInfo-GET") {
      httpClient
        .get(url"${appConfig.userGroupsSearchUrl}/users-groups-search/groups/$groupId")
        .execute[Option[GroupInfo]]
    }

}

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

package uk.gov.hmrc.agentclientrelationships.config

import play.api.Configuration
import uk.gov.hmrc.agentclientrelationships.model.BasicAuthentication
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import java.net.URLDecoder
import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.duration.Duration

case class ConfigNotFoundException(message: String)
extends RuntimeException(message)

@Singleton
class AppConfig @Inject() (
  config: Configuration,
  servicesConfig: ServicesConfig
) {

  val appName = servicesConfig.getString("appName")

  private def getConfigString(key: String) = servicesConfig.getConfString(
    key,
    throw ConfigNotFoundException(s"Could not find config key: '$key'")
  )

  val internalAuthTokenEnabled: Boolean = config.get[Boolean]("internal-auth-token-enabled-on-start")

  def expectedAuth: BasicAuthentication = {
    val username = config
      .get[Option[String]]("agent-termination.username")
      .getOrElse(throw new RuntimeException(s"Could not find config key 'agent-termination.username'"))
    val password = config
      .get[Option[String]]("agent-termination.password")
      .getOrElse(throw ConfigNotFoundException(s"Could not find config key 'agent-termination.password'"))

    BasicAuthentication(username, password)
  }

  val enrolmentStoreProxyUrl = servicesConfig.baseUrl("enrolment-store-proxy")

  val taxEnrolmentsUrl = servicesConfig.baseUrl("tax-enrolments")

  val userGroupsSearchUrl = servicesConfig.baseUrl("users-groups-search")

  val agentPermissionsUrl: String = servicesConfig.baseUrl("agent-permissions")

  val agentFiRelationshipBaseUrl: String = servicesConfig.baseUrl("agent-fi-relationship")

  val desUrl = servicesConfig.baseUrl("des")
  val desEnv = getConfigString("des.environment")
  val desToken = getConfigString("des.authorization-token")

  val ifsPlatformBaseUrl = servicesConfig.baseUrl("ifs")

  /** Itegration Framework (If) Environment
    */
  val ifsEnvironment: String = getConfigString("ifs.environment")

  val ifsAPI1712Token = getConfigString("ifs.authorization-api1712-token")
  val ifsAPI1495Token = getConfigString("ifs.authorization-api1495-token")
  val ifsAPI2143Token = getConfigString("ifs.authorization-api2143-token")

  val hipPlatformBaseUrl = servicesConfig.baseUrl("hip")
  val hipAuthToken = getConfigString("hip.authorization-token")

  val ifBaseUrl = servicesConfig.baseUrl("if")
  val ifEnvironment = getConfigString("if.environment")
  val ifAuthToken = getConfigString("if.authorization-token")

  val agentMappingUrl = servicesConfig.baseUrl("agent-mapping")

  val authUrl = servicesConfig.baseUrl("auth")

  val internalAuthBaseUrl: String = servicesConfig.baseUrl("internal-auth")
  val internalAuthToken: String = servicesConfig.getString("internal-auth.token")

  val citizenDetailsBaseUrl: String = servicesConfig.baseUrl("citizen-details")

  val agentUserClientDetailsUrl = servicesConfig.baseUrl("agent-user-client-details")

  val agentAssuranceBaseUrl: String = servicesConfig.baseUrl("agent-assurance")

  val emailBaseUrl: String = servicesConfig.baseUrl("email")

  val oldAuthStrideRole = URLDecoder.decode(servicesConfig.getString("old.auth.stride.role"), "UTF-8")

  val newAuthStrideRole = servicesConfig.getString("new.auth.stride.role")

  val partialAuthStrideRole: String = servicesConfig.getString("partial-auth.stride.role")

  val copyMtdItRelationshipFlag = servicesConfig.getBoolean("features.copy-relationship.mtd-it")

  val recoveryEnabled = servicesConfig.getBoolean("features.recovery-enable")

  val recoveryInterval = servicesConfig.getInt("recovery-interval")

  val recoveryTimeout = servicesConfig.getInt("recovery-timeout")

  val inactiveRelationshipsClientRecordStartDate = servicesConfig.getString(
    "inactive-relationships-client.record-start-date"
  )
  val overseasItsaEnabled: Boolean = servicesConfig.getBoolean("features.overseas-itsa-enabled")
  val cbcEnabled: Boolean = servicesConfig.getBoolean("features.cbc-enabled")

  val supportedServices: Seq[Service] =
    if (cbcEnabled) {
      Service.supportedServices
    }
    else
      Service.supportedServices.filterNot(service => service == Service.Cbc || service == Service.CbcNonUk)

  // Note: Personal Income Record is not handled through agent-client-relationships for many of the endpoints
  val supportedServicesWithoutPir: Seq[Service] = supportedServices.filterNot(_ == Service.PersonalIncomeRecord)

  val apiSupportedServices: Seq[Service] = Seq(
    Service.MtdIt,
    Service.MtdItSupp,
    Service.Vat
  )

  val invitationsTtl: Long = config.get[Long]("mongodb.invitations.expireAfterDays")

  val invitationExpiringDuration: Duration = servicesConfig.getDuration("invitation.expiryDuration")

  val emailSchedulerEnabled: Boolean = servicesConfig.getBoolean("emailScheduler.enabled")
  val emailSchedulerWarningCronExp: String = servicesConfig.getString("emailScheduler.warningEmailCronExpression")
  val emailSchedulerExpiredCronExp: String = servicesConfig.getString("emailScheduler.expiredEmailCronExpression")
  val emailSchedulerLockTTL: Int = servicesConfig.getInt("emailScheduler.lockDurationInSeconds")

}

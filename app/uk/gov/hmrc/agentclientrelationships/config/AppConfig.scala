/*
 * Copyright 2022 HM Revenue & Customs
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

import java.net.URLDecoder

import javax.inject.{Inject, Singleton}
import play.api.Configuration
import uk.gov.hmrc.agentclientrelationships.model.BasicAuthentication
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

case class ConfigNotFoundException(message: String) extends RuntimeException(message)

@Singleton
class AppConfig @Inject()(config: Configuration, servicesConfig: ServicesConfig) {

  val appName = "agent-client-relationships"

  private def getConfigString(key: String) =
    servicesConfig.getConfString(key, throw ConfigNotFoundException(s"Could not find config key: '$key'"))

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

  val desUrl = servicesConfig.baseUrl("des")
  val desEnv = getConfigString("des.environment")
  val desToken = getConfigString("des.authorization-token")

  val iFPlatformEnabled: Boolean = servicesConfig.getBoolean("des-if.enabled")

  val ifPlatformBaseUrl = servicesConfig.baseUrl("if")
  val ifEnvironment = getConfigString("if.environment")
  val ifAuthToken = getConfigString("if.authorization-token")

  val agentMappingUrl = servicesConfig.baseUrl("agent-mapping")

  val authUrl = servicesConfig.baseUrl("auth")

  val agentClientAuthorisationUrl = servicesConfig.baseUrl("agent-client-authorisation")

  val inactiveRelationshipShowLastDays = servicesConfig.getInt("inactive-relationships.show-last-days")

  val oldAuthStrideRole = URLDecoder.decode(servicesConfig.getString("old.auth.stride.role"), "utf-8")

  val newAuthStrideRole = servicesConfig.getString("new.auth.stride.role")

  val copyMtdItRelationshipFlag = servicesConfig.getBoolean("features.copy-relationship.mtd-it")

  val copyMtdVatRelationshipFlag = servicesConfig.getBoolean("features.copy-relationship.mtd-vat")

  val recoveryEnabled = servicesConfig.getBoolean("features.recovery-enable")

  val recoveryInterval = servicesConfig.getInt("recovery-interval")

  val recoveryTimeout = servicesConfig.getInt("recovery-timeout")

  val terminationStrideRole = servicesConfig.getString("termination.stride.role")

  val inactiveRelationshipsClientRecordStartDate =
    servicesConfig.getString("inactive-relationships-client.record-start-date")

  val altItsaEnabled =
    servicesConfig.getBoolean("alt-itsa.enabled")

}

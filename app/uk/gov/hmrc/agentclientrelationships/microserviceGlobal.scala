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

package uk.gov.hmrc.agentclientrelationships

import java.net.URL
import javax.inject.Provider

import com.google.inject.AbstractModule
import com.google.inject.name.Names
import com.typesafe.config.Config
import net.ceedubs.ficus.Ficus._
import play.api.mvc.EssentialFilter
import play.api.{Application, Configuration, Play}
import uk.gov.hmrc.agentclientrelationships.repository.{MongoLockRepository, MongoRelationshipCopyRecordRepository, RelationshipCopyRecordRepository}
import uk.gov.hmrc.agentclientrelationships.services.{MongoRecoveryLockService, RecoveryLockService}
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.lock.LockRepository
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.config.{AppName, ControllerConfig, RunMode, ServicesConfig}
import uk.gov.hmrc.play.microservice.bootstrap.DefaultMicroserviceGlobal
import uk.gov.hmrc.http.{HttpGet, HttpPost}
import uk.gov.hmrc.play.microservice.filters.{AuditFilter, LoggingFilter, MicroserviceFilterSupport}

class GuiceModule() extends AbstractModule with ServicesConfig {

  def configure(): Unit = {
    bind(classOf[HttpGet]).toInstance(WSHttp)
    bind(classOf[HttpPost]).toInstance(WSHttp)
    bind(classOf[AuditConnector]).toInstance(MicroserviceGlobal.auditConnector)
    bind(classOf[AuthConnector]).to(classOf[MicroserviceAuthConnector])
    bind(classOf[RelationshipCopyRecordRepository]).to(classOf[MongoRelationshipCopyRecordRepository])
    bind(classOf[LockRepository]).to(classOf[MongoLockRepository])
    bind(classOf[RecoveryLockService]).to(classOf[MongoRecoveryLockService])
    bindBaseUrl("enrolment-store-proxy")
    bindBaseUrl("tax-enrolments")
    bindBaseUrl("users-groups-search")
    bindBaseUrl("des")
    bindBaseUrl("agent-mapping")
    bindBaseUrl("auth")
    bindProperty("des.environment", "des.environment")
    bindProperty("des.authorizationToken", "des.authorization-token")
    bindBooleanProperty("features.copy-relationship.mtd-it")
    bindBooleanProperty("features.copy-relationship.mtd-vat")
  }

  private def bindBaseUrl(serviceName: String) =
    bind(classOf[URL]).annotatedWith(Names.named(s"$serviceName-baseUrl")).toProvider(new BaseUrlProvider(serviceName))

  private class BaseUrlProvider(serviceName: String) extends Provider[URL] {
    override lazy val get = new URL(baseUrl(serviceName))
  }

  private def bindProperty(objectName: String, propertyName: String) =
    bind(classOf[String]).annotatedWith(Names.named(objectName)).toProvider(new PropertyProvider(propertyName))

  private class PropertyProvider(confKey: String) extends Provider[String] {
    override lazy val get = getConfString(confKey, throw new IllegalStateException(s"No value found for configuration property $confKey"))
  }

  private def bindBooleanProperty(propertyName: String) =
    bind(classOf[Boolean]).annotatedWith(Names.named(propertyName)).toProvider(new BooleanPropertyProvider(propertyName))

  private class BooleanPropertyProvider(confKey: String) extends Provider[Boolean] {
    def getBooleanFromRoot = runModeConfiguration
      .getBoolean(confKey)
      .getOrElse(throw new IllegalStateException(s"No value found for configuration property $confKey"))
    override lazy val get: Boolean = getConfBool(confKey, getBooleanFromRoot)
  }
}

object ControllerConfiguration extends ControllerConfig {
  lazy val controllerConfigs: Config = Play.current.configuration.underlying.as[Config]("controllers")
}


object MicroserviceAuditFilter extends AuditFilter with AppName with MicroserviceFilterSupport {
  override val auditConnector = MicroserviceAuditConnector
  override def controllerNeedsAuditing(controllerName: String): Boolean = ControllerConfiguration.paramsForController(controllerName).needsAuditing
}

object MicroserviceLoggingFilter extends LoggingFilter with MicroserviceFilterSupport {
  override def controllerNeedsLogging(controllerName: String): Boolean = ControllerConfiguration.paramsForController(controllerName).needsLogging
}


object MicroserviceGlobal extends DefaultMicroserviceGlobal with RunMode {
  override val auditConnector = MicroserviceAuditConnector

  override def microserviceMetricsConfig(implicit app: Application): Option[Configuration] = app.configuration.getConfig(s"microservice.metrics")

  override val loggingFilter = MicroserviceLoggingFilter

  override val microserviceAuditFilter = MicroserviceAuditFilter

  override val authFilter = None

  lazy val monitoringFilter: EssentialFilter = Play.current.injector.instanceOf[MicroserviceMonitoringFilter]

  override lazy val microserviceFilters: Seq[EssentialFilter] = defaultMicroserviceFilters ++ Seq(monitoringFilter)
}

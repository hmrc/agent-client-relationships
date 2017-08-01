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

package uk.gov.hmrc.agentclientrelationships

import java.net.URL
import javax.inject.Provider

import com.google.inject.AbstractModule
import com.google.inject.name.Names
import com.typesafe.config.Config
import net.ceedubs.ficus.Ficus._
import play.api.{Application, Configuration, Play}
import uk.gov.hmrc.auth.filter.AuthorisationFilter
import uk.gov.hmrc.play.audit.filters.AuditFilter
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.config.{AppName, ControllerConfig, RunMode, ServicesConfig}
import uk.gov.hmrc.play.filters.MicroserviceFilterSupport
import uk.gov.hmrc.play.http.logging.filters.LoggingFilter
import uk.gov.hmrc.play.http.{HttpGet, HttpPost}
import uk.gov.hmrc.play.microservice.bootstrap.DefaultMicroserviceGlobal

class GuiceModule() extends AbstractModule with ServicesConfig {

  def configure(): Unit = {
    bind(classOf[HttpGet]).toInstance(WSHttp)
    bind(classOf[HttpPost]).toInstance(WSHttp)
    bind(classOf[AuditConnector]).toInstance(MicroserviceGlobal.auditConnector)
    bindBaseUrl("government-gateway-proxy")
    bindBaseUrl("des")
    bindBaseUrl("agent-mapping")
    bindBaseUrl("auth")
    bindProperty("des.environment", "des.environment")
    bindProperty("des.authorizationToken", "des.authorization-token")
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
}

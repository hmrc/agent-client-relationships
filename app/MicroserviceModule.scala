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

import java.net.{URL, URLDecoder}

import akka.actor.ActorSystem
import com.google.inject.AbstractModule
import com.google.inject.name.{Named, Names}
import com.typesafe.config.Config
import javax.inject.{Inject, Provider, Singleton}
import org.slf4j.MDC
import play.api.{Configuration, Environment, Logger}
import uk.gov.hmrc.agentclientrelationships.connectors.MicroserviceAuthConnector
import uk.gov.hmrc.agentclientrelationships.repository._
import uk.gov.hmrc.agentclientrelationships.services.{AgentCacheProvider, MongoRecoveryLockService, RecoveryLockService}
import uk.gov.hmrc.agentclientrelationships.support.RecoveryScheduler
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.http._
import uk.gov.hmrc.lock.LockRepository
import uk.gov.hmrc.play.audit.http.HttpAuditing
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.config.ServicesConfig
import uk.gov.hmrc.play.http.ws.WSHttp

import scala.concurrent.duration.Duration

class MicroserviceModule(val environment: Environment, val configuration: Configuration)
    extends AbstractModule
    with ServicesConfig {

  override val runModeConfiguration: Configuration = configuration
  override protected def mode = environment.mode

  def configure(): Unit = {
    val appName = "agent-client-relationships"

    val loggerDateFormat: Option[String] = configuration.getString("logger.json.dateformat")
    Logger(getClass).info(s"Starting microservice : $appName : in mode : ${environment.mode}")
    MDC.put("appName", appName)
    loggerDateFormat.foreach(str => MDC.put("logger.json.dateformat", str))

    bindProperty1Param("appName")

    bind(classOf[HttpGet]).to(classOf[HttpVerbs])
    bind(classOf[HttpPost]).to(classOf[HttpVerbs])
    bind(classOf[HttpPut]).to(classOf[HttpVerbs])
    bind(classOf[AuthConnector]).to(classOf[MicroserviceAuthConnector])
    bind(classOf[RelationshipCopyRecordRepository]).to(classOf[MongoRelationshipCopyRecordRepository])
    bind(classOf[DeleteRecordRepository]).to(classOf[MongoDeleteRecordRepository])
    bind(classOf[LockRepository]).to(classOf[MongoLockRepository])
    bind(classOf[RecoveryLockService]).to(classOf[MongoRecoveryLockService])
    bind(classOf[RecoveryScheduleRepository]).to(classOf[MongoRecoveryScheduleRepository])
    bind(classOf[AgentCacheProvider])

    bindBaseUrl("enrolment-store-proxy")
    bindBaseUrl("tax-enrolments")
    bindBaseUrl("users-groups-search")
    bindBaseUrl("des")
    bindBaseUrl("agent-mapping")
    bindBaseUrl("auth")

    bindProperty("des.environment", "des.environment")
    bindProperty("des.authorizationToken", "des.authorization-token")
    bindServiceConfigProperty[Duration]("inactive-relationships.show-last-days")
    bindProperty1Param("old.auth.stride.role", URLDecoder.decode(_, "utf-8"))
    bindProperty1Param("new.auth.stride.role")
    bindBooleanProperty("features.copy-relationship.mtd-it")
    bindBooleanProperty("features.copy-relationship.mtd-vat")
    bindBooleanProperty("features.recovery-enable")
    bindIntegerProperty("recovery-interval")
    bindIntegerProperty("recovery-timeout")

    if (configuration.getBoolean("features.recovery-enable").getOrElse(false)) {
      bind(classOf[RecoveryScheduler]).asEagerSingleton()
    }
  }

  private def bindBaseUrl(serviceName: String) =
    bind(classOf[URL]).annotatedWith(Names.named(s"$serviceName-baseUrl")).toProvider(new BaseUrlProvider(serviceName))

  private class BaseUrlProvider(serviceName: String) extends Provider[URL] {
    override lazy val get = new URL(baseUrl(serviceName))
  }

  private def bindProperty(objectName: String, propertyName: String) =
    bind(classOf[String]).annotatedWith(Names.named(objectName)).toProvider(new PropertyProvider(propertyName))

  private class PropertyProvider(confKey: String) extends Provider[String] {
    override lazy val get =
      getConfString(confKey, throw new IllegalStateException(s"No value found for configuration property $confKey"))
  }

  private def bindProperty1Param(propertyName: String, mapFx: String => String = identity) =
    bind(classOf[String])
      .annotatedWith(Names.named(propertyName))
      .toProvider(new PropertyProviderFor1Param(propertyName, mapFx))

  private class PropertyProviderFor1Param(confKey: String, mapFx: String => String) extends Provider[String] {
    override lazy val get = configuration
      .getString(confKey)
      .map(mapFx)
      .getOrElse(throw new IllegalStateException(s"No value found for configuration property $confKey"))
  }

  private def bindBooleanProperty(propertyName: String) =
    bind(classOf[Boolean])
      .annotatedWith(Names.named(propertyName))
      .toProvider(new BooleanPropertyProvider(propertyName))

  private class BooleanPropertyProvider(confKey: String) extends Provider[Boolean] {
    def getBooleanFromRoot =
      runModeConfiguration
        .getBoolean(confKey)
        .getOrElse(throw new IllegalStateException(s"No value found for configuration property $confKey"))
    override lazy val get: Boolean = getConfBool(confKey, getBooleanFromRoot)
  }

  private def bindIntegerProperty(propertyName: String) =
    bind(classOf[Int])
      .annotatedWith(Names.named(propertyName))
      .toProvider(new IntegerPropertyProvider(propertyName))

  private class IntegerPropertyProvider(confKey: String) extends Provider[Int] {
    override lazy val get: Int = configuration
      .getInt(confKey)
      .getOrElse(throw new IllegalStateException(s"No value found for configuration property $confKey"))
  }

  import com.google.inject.binder.ScopedBindingBuilder
  import com.google.inject.name.Names.named

  import scala.reflect.ClassTag

  private def bindServiceConfigProperty[A](
    propertyName: String)(implicit classTag: ClassTag[A], ct: ServiceConfigPropertyType[A]): ScopedBindingBuilder =
    ct.bindServiceConfigProperty(classTag.runtimeClass.asInstanceOf[Class[A]])(propertyName)

  sealed trait ServiceConfigPropertyType[A] {
    def bindServiceConfigProperty(clazz: Class[A])(propertyName: String): ScopedBindingBuilder
  }

  object ServiceConfigPropertyType {

    implicit val durationServiceConfigProperty: ServiceConfigPropertyType[Duration] =
      new ServiceConfigPropertyType[Duration] {
        def bindServiceConfigProperty(clazz: Class[Duration])(propertyName: String): ScopedBindingBuilder =
          bind(clazz)
            .annotatedWith(named(s"$propertyName"))
            .toProvider(new DurationServiceConfigPropertyProvider(propertyName))

        private class DurationServiceConfigPropertyProvider(propertyName: String) extends Provider[Duration] {
          override lazy val get = getConfDurationCustom(
            propertyName,
            throw new RuntimeException(s"No service configuration value found for $propertyName"))
        }

        private def getConfDurationCustom(confKey: String, defDur: => Duration) =
          runModeConfiguration
            .getString(s"$rootServices.$confKey")
            .orElse(runModeConfiguration.getString(s"$services.$confKey"))
            .orElse(runModeConfiguration.getString(s"$playServices.$confKey")) match {
            case Some(s) => Duration.create(s.replace("_", " "))
            case None    => defDur
          }
      }

  }

}

@Singleton
class HttpVerbs @Inject()(
  val auditConnector: AuditConnector,
  @Named("appName") val appName: String,
  val config: Configuration,
  val actorSystem: ActorSystem)
    extends HttpGet
    with HttpPost
    with HttpPut
    with HttpPatch
    with HttpDelete
    with WSHttp
    with HttpAuditing {
  override val hooks = Seq(AuditingHook)
  override protected def configuration: Option[Config] = Some(config.underlying)
}

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

import java.net.URL

import javax.inject.{Inject, Provider, Singleton}
import com.google.inject.AbstractModule
import com.google.inject.name.{Named, Names}
import org.slf4j.MDC
import play.api.inject.bind
import play.api.{Configuration, Environment, Logger}
import uk.gov.hmrc.agentclientrelationships.connectors.MicroserviceAuthConnector
import uk.gov.hmrc.agentclientrelationships.repository._
import uk.gov.hmrc.agentclientrelationships.services.{MongoRecoveryLockService, RecoveryLockService}
import uk.gov.hmrc.agentclientrelationships.support.RecoveryScheduler
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.http._
import uk.gov.hmrc.lock.LockRepository
import uk.gov.hmrc.play.audit.http.HttpAuditing
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.config.ServicesConfig
import uk.gov.hmrc.play.http.ws.WSHttp

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

    bindBaseUrl("enrolment-store-proxy")
    bindBaseUrl("tax-enrolments")
    bindBaseUrl("users-groups-search")
    bindBaseUrl("des")
    bindBaseUrl("agent-mapping")
    bindBaseUrl("auth")

    bindProperty("des.environment", "des.environment")
    bindProperty("des.authorizationToken", "des.authorization-token")
    bindProperty1Param("auth.stride.role")
    bindBooleanProperty("features.copy-relationship.mtd-it")
    bindBooleanProperty("features.copy-relationship.mtd-vat")
    bindBooleanProperty("features.recovery-enable")
    bindIntegerProperty("recovery-interval")

    bind(classOf[RecoveryScheduler]).asEagerSingleton()
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

  private def bindProperty1Param(propertyName: String) =
    bind(classOf[String])
      .annotatedWith(Names.named(propertyName))
      .toProvider(new PropertyProviderFor1Param(propertyName))

  private class PropertyProviderFor1Param(confKey: String) extends Provider[String] {
    override lazy val get = configuration
      .getString(confKey)
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

    implicit val stringServiceConfigProperty: ServiceConfigPropertyType[String] =
      new ServiceConfigPropertyType[String] {
        def bindServiceConfigProperty(clazz: Class[String])(propertyName: String): ScopedBindingBuilder =
          bind(clazz)
            .annotatedWith(named(s"$propertyName"))
            .toProvider(new StringServiceConfigPropertyProvider(propertyName))

        private class StringServiceConfigPropertyProvider(propertyName: String) extends Provider[String] {
          override lazy val get = getConfString(
            propertyName,
            throw new RuntimeException(s"No service configuration value found for '$propertyName'"))
        }
      }

    implicit val intServiceConfigProperty: ServiceConfigPropertyType[Int] = new ServiceConfigPropertyType[Int] {
      def bindServiceConfigProperty(clazz: Class[Int])(propertyName: String): ScopedBindingBuilder =
        bind(clazz)
          .annotatedWith(named(s"$propertyName"))
          .toProvider(new IntServiceConfigPropertyProvider(propertyName))

      private class IntServiceConfigPropertyProvider(propertyName: String) extends Provider[Int] {
        override lazy val get = getConfInt(
          propertyName,
          throw new RuntimeException(s"No service configuration value found for '$propertyName'"))
      }
    }

    implicit val booleanServiceConfigProperty: ServiceConfigPropertyType[Boolean] =
      new ServiceConfigPropertyType[Boolean] {
        def bindServiceConfigProperty(clazz: Class[Boolean])(propertyName: String): ScopedBindingBuilder =
          bind(clazz)
            .annotatedWith(named(s"$propertyName"))
            .toProvider(new BooleanServiceConfigPropertyProvider(propertyName))

        private class BooleanServiceConfigPropertyProvider(propertyName: String) extends Provider[Boolean] {
          override lazy val get = getConfBool(propertyName, false)
        }
      }

  }

}

@Singleton
class HttpVerbs @Inject()(val auditConnector: AuditConnector, @Named("appName") val appName: String)
    extends HttpGet
    with HttpPost
    with HttpPut
    with HttpPatch
    with HttpDelete
    with WSHttp
    with HttpAuditing {
  override val hooks = Seq(AuditingHook)
}

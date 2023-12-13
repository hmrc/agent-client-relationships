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

package uk.gov.hmrc.agentclientrelationships.services

import com.codahale.metrics.MetricRegistry
import com.github.blemale.scaffeine.Scaffeine
import javax.inject.{Inject, Singleton}
import play.api.{Configuration, Environment, Logging}
import uk.gov.hmrc.agentclientrelationships.connectors.{GroupInfo, UserDetails}
import uk.gov.hmrc.agentclientrelationships.model.InactiveRelationship

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success

trait KenshooCacheMetrics extends Logging {

  val kenshooRegistry: MetricRegistry

  def record[T](name: String): Unit = {
    kenshooRegistry.getMeters.getOrDefault(name, kenshooRegistry.meter(name)).mark()
    logger.debug(s"kenshoo-event::meter::$name::recorded")
  }

}

trait Cache[T] {
  def apply(key: String)(body: => Future[T])(implicit ec: ExecutionContext): Future[T]
}

class DoNotCache[T] extends Cache[T] {
  def apply(key: String)(body: => Future[T])(implicit ec: ExecutionContext): Future[T] = body
}

class LocalCaffeineCache[T](name: String, size: Int, expires: Duration)(implicit val kenshooRegistry: MetricRegistry)
    extends KenshooCacheMetrics
    with Cache[T] {

  private val underlying: com.github.blemale.scaffeine.Cache[String, T] =
    Scaffeine()
      .recordStats()
      .expireAfterWrite(FiniteDuration(expires.toMillis, MILLISECONDS))
      .maximumSize(size)
      .build[String, T]()

  def apply(key: String)(body: => Future[T])(implicit ec: ExecutionContext): Future[T] =
    underlying.getIfPresent(key) match {
      case Some(v) =>
        record("Count-" + name + "-from-cache")
        Future.successful(v)
      case None =>
        body.andThen {
          case Success(v) =>
            logger.info(s"Missing $name cache hit, storing new value.")
            record("Count-" + name + "-from-source")
            underlying.put(key, v)
        }
    }
}

@Singleton
class AgentCacheProvider @Inject()(val environment: Environment, configuration: Configuration)(
  implicit val kenshooRegistry: MetricRegistry) {

  private val cacheSize = configuration.underlying.getInt("agent.cache.size")
  private val cacheExpires = Duration.create(configuration.underlying.getString("agent.cache.expires"))
  private val cacheEnabled = configuration.underlying.getBoolean("agent.cache.enabled")

  private val agentTrackCacheSize = configuration.underlying.getInt("agent.trackPage.cache.size")
  private val agentTrackCacheExpires =
    Duration.create(configuration.underlying.getString("agent.trackPage.cache.expires"))
  private val agentTrackCacheEnabled = configuration.underlying.getBoolean("agent.trackPage.cache.enabled")

  val esPrincipalGroupIdCache: Cache[String] =
    if (cacheEnabled) new LocalCaffeineCache[String]("es-principalGroupId-cache", cacheSize, cacheExpires)
    else new DoNotCache[String]

  val ugsFirstGroupAdminCache: Cache[Option[UserDetails]] =
    if (cacheEnabled) new LocalCaffeineCache[Option[UserDetails]]("ugs-firstGroupAdmin-cache", cacheSize, cacheExpires)
    else new DoNotCache[Option[UserDetails]]

  val ugsGroupInfoCache: Cache[Option[GroupInfo]] =
    if (cacheEnabled) new LocalCaffeineCache[Option[GroupInfo]]("ugs-groupInfo-cache", cacheSize, cacheExpires)
    else new DoNotCache[Option[GroupInfo]]

  val agentTrackPageCache: Cache[Seq[InactiveRelationship]] =
    if (agentTrackCacheEnabled)
      new LocalCaffeineCache[Seq[InactiveRelationship]](
        "agent-trackPage-cache",
        agentTrackCacheSize,
        agentTrackCacheExpires)
    else new DoNotCache[Seq[InactiveRelationship]]
}

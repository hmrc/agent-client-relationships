/*
 * Copyright 2024 HM Revenue & Customs
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
import play.api.libs.json.{Reads, Writes}
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import javax.inject.{Inject, Singleton}
import play.api.{Configuration, Logging}
import uk.gov.hmrc.agentclientrelationships.connectors.{GroupInfo, UserDetails}
import uk.gov.hmrc.agentclientrelationships.model.InactiveRelationship
import uk.gov.hmrc.mongo.cache.CacheIdType.SimpleCacheId
import uk.gov.hmrc.mongo.cache.{DataKey, MongoCacheRepository}
import uk.gov.hmrc.mongo.{CurrentTimestampSupport, MongoComponent}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success

trait KenshooCacheMetrics extends Logging {

  val kenshooRegistry: MetricRegistry

  def record(name: String): Unit = {
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

class CacheRepository @Inject() (
  mongoComponent: MongoComponent,
  collectionName: String,
  ttl: Duration
)(implicit ec: ExecutionContext)
    extends MongoCacheRepository(
      mongoComponent = mongoComponent,
      collectionName = collectionName,
      ttl = ttl,
      timestampSupport = new CurrentTimestampSupport(),
      cacheIdType = SimpleCacheId
    )

class CacheRepositoryFactory @Inject() (
  mongoComponent: MongoComponent,
  configuration: Configuration
)(implicit ec: ExecutionContext) {
  def apply(collectionName: String, ttlConfigKey: String): CacheRepository = new CacheRepository(
    mongoComponent = mongoComponent,
    collectionName = collectionName,
    ttl = configuration.get[FiniteDuration](ttlConfigKey)
  )
}

class MongoCache[T] @Inject() (cacheRepository: CacheRepository, name: String)(implicit
  metrics: Metrics,
  reads: Reads[T],
  writes: Writes[T]
) extends KenshooCacheMetrics
    with Cache[T] {

  val kenshooRegistry: MetricRegistry = metrics.defaultRegistry

  def apply(cacheId: String)(body: => Future[T])(implicit ec: ExecutionContext): Future[T] = {
    val dataKey: DataKey[T] = DataKey[T](cacheId)

    cacheRepository.get(cacheId)(dataKey).flatMap {
      case Some(cachedValue) =>
        record(s"Count-$name-from-cache")
        Future.successful(cachedValue)
      case None =>
        body.andThen { case Success(newValue) =>
          logger.info(s"Missing $name cache hit, storing new value.")
          record(s"Count-$name-from-source")
          cacheRepository.put(cacheId)(dataKey, newValue).map(_ => newValue)
        }
    }
  }
}

@Singleton
class AgentCacheProvider @Inject() (configuration: Configuration, cacheRepositoryFactory: CacheRepositoryFactory)(
  implicit metrics: Metrics
) {

  implicit val readsOptionalGroupInfo: Reads[Option[GroupInfo]] = _.validateOpt[GroupInfo]
  implicit val readsOptionalUserDetails: Reads[Option[UserDetails]] = _.validateOpt[UserDetails]

  private val cacheEnabled: Boolean = configuration.underlying.getBoolean("agent.cache.enabled")
  private val agentTrackPageCacheEnabled: Boolean = configuration.underlying.getBoolean("agent.trackPage.cache.enabled")

  private def createCache[T](enabled: Boolean, cacheRepository: CacheRepository, name: String)(implicit
    reads: Reads[T],
    writes: Writes[T]
  ): Cache[T] =
    if (enabled) new MongoCache[T](cacheRepository, name)
    else new DoNotCache[T]

  private lazy val esPrincipalGroupIdCacheRepository: CacheRepository =
    cacheRepositoryFactory("es-principalGroupId-cache", "agent.cache.expires")

  private lazy val ugsFirstGroupAdminCacheRepository: CacheRepository =
    cacheRepositoryFactory("ugs-firstGroupAdmin-cache", "agent.cache.expires")

  private lazy val ugsGroupInfoCacheRepository: CacheRepository =
    cacheRepositoryFactory("ugs-groupInfo-cache", "agent.cache.expires")

  private lazy val agentTrackPageCacheRepository: CacheRepository =
    cacheRepositoryFactory("agent-track-cache", "agent.trackPage.cache.expires")

  val esPrincipalGroupIdCache: Cache[String] =
    createCache[String](cacheEnabled, esPrincipalGroupIdCacheRepository, "es-principalGroupId-cache")

  val ugsFirstGroupAdminCache: Cache[Option[UserDetails]] =
    createCache[Option[UserDetails]](cacheEnabled, ugsFirstGroupAdminCacheRepository, "ugs-firstGroupAdmin-cache")

  val ugsGroupInfoCache: Cache[Option[GroupInfo]] =
    createCache[Option[GroupInfo]](cacheEnabled, ugsGroupInfoCacheRepository, "ugs-groupInfo-cache")

  val agentTrackPageCache: Cache[Seq[InactiveRelationship]] =
    createCache[Seq[InactiveRelationship]](
      agentTrackPageCacheEnabled,
      agentTrackPageCacheRepository,
      "agent-trackPage-cache"
    )
}

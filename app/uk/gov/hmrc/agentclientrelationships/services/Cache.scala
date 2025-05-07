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

@Singleton
class CacheRepositoryFactory @Inject() (mongoComponent: MongoComponent, configuration: Configuration)(implicit
  ec: ExecutionContext
) {
  def apply(collectionName: String, ttlConfigKey: String): MongoCacheRepository[String] =
    new MongoCacheRepository[String](
      mongoComponent = mongoComponent,
      collectionName = collectionName,
      ttl = configuration.get[FiniteDuration](ttlConfigKey),
      timestampSupport = new CurrentTimestampSupport(),
      cacheIdType = SimpleCacheId
    )
}

class MongoCache[T] @Inject() (
  cacheRepositoryFactory: CacheRepositoryFactory,
  collectionName: String,
  ttlConfigKey: String
)(implicit metrics: Metrics, reads: Reads[T], writes: Writes[T])
extends KenshooCacheMetrics
with Cache[T] {

  val kenshooRegistry: MetricRegistry = metrics.defaultRegistry

  private lazy val cacheRepository: MongoCacheRepository[String] = cacheRepositoryFactory(collectionName, ttlConfigKey)

  def apply(cacheId: String)(body: => Future[T])(implicit ec: ExecutionContext): Future[T] = {
    val dataKey: DataKey[T] = DataKey[T](cacheId)

    cacheRepository
      .get(cacheId)(dataKey)
      .flatMap {
        case Some(cachedValue) =>
          record(s"Count-$collectionName-from-cache")
          Future.successful(cachedValue)
        case None =>
          body.andThen { case Success(newValue) =>
            logger.info(s"Missing $collectionName cache hit, storing new value.")
            record(s"Count-$collectionName-from-source")
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

  private def createCache[T](enabled: Boolean, collectionName: String, ttlConfigKey: String)(implicit
    reads: Reads[T],
    writes: Writes[T]
  ): Cache[T] =
    if (enabled)
      new MongoCache[T](cacheRepositoryFactory, collectionName, ttlConfigKey)
    else
      new DoNotCache[T]

  val esPrincipalGroupIdCache: Cache[String] = createCache[String](
    cacheEnabled,
    "es-principalGroupId-cache",
    "agent.cache.expires"
  )

  val ugsFirstGroupAdminCache: Cache[Option[UserDetails]] = createCache[Option[UserDetails]](
    cacheEnabled,
    "ugs-firstGroupAdmin-cache",
    "agent.cache.expires"
  )

  val ugsGroupInfoCache: Cache[Option[GroupInfo]] = createCache[Option[GroupInfo]](
    cacheEnabled,
    "ugs-groupInfo-cache",
    "agent.cache.expires"
  )

  val agentTrackPageCache: Cache[Seq[InactiveRelationship]] = createCache[Seq[InactiveRelationship]](
    agentTrackPageCacheEnabled,
    "agent-trackPage-cache",
    "agent.trackPage.cache.expires"
  )
}

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

import play.api.libs.json.Reads
import play.api.libs.json.Writes

import javax.inject.Inject
import javax.inject.Singleton
import play.api.Configuration
import play.api.mvc.RequestHeader
import uk.gov.hmrc.agentclientrelationships.util.RequestAwareLogging
import uk.gov.hmrc.agentclientrelationships.connectors.GroupInfo
import uk.gov.hmrc.mongo.cache.CacheIdType.SimpleCacheId
import uk.gov.hmrc.mongo.cache.DataKey
import uk.gov.hmrc.mongo.cache.MongoCacheRepository
import uk.gov.hmrc.mongo.CurrentTimestampSupport
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mdc.Mdc

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Success

trait Cache[T] {
  def apply(key: String)(body: => Future[T])(implicit request: RequestHeader): Future[T]
}

class DoNotCache[T]
extends Cache[T] {
  def apply(key: String)(body: => Future[T])(implicit request: RequestHeader): Future[T] = body
}

@Singleton
class CacheRepositoryFactory @Inject() (
  mongoComponent: MongoComponent,
  configuration: Configuration
)(implicit ec: ExecutionContext) {
  def apply(
    collectionName: String,
    ttlConfigKey: String
  ): MongoCacheRepository[String] =
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
)(implicit
  reads: Reads[T],
  writes: Writes[T],
  executionContext: ExecutionContext
)
extends RequestAwareLogging
with Cache[T] {

  private lazy val cacheRepository: MongoCacheRepository[String] = cacheRepositoryFactory(collectionName, ttlConfigKey)

  def apply(cacheId: String)(body: => Future[T])(implicit request: RequestHeader): Future[T] = {
    val dataKey: DataKey[T] = DataKey[T](cacheId)

    Mdc.preservingMdc {
      cacheRepository
        .get(cacheId)(dataKey)
        .flatMap {
          case Some(cachedValue) => Future.successful(cachedValue)
          case None =>
            body.andThen { case Success(newValue) =>
              logger.info(s"Missing $collectionName cache hit, storing new value.")
              cacheRepository.put(cacheId)(dataKey, newValue).map(_ => newValue)
            }
        }
    }
  }

}

@Singleton
class AgentCacheProvider @Inject() (
  configuration: Configuration,
  cacheRepositoryFactory: CacheRepositoryFactory
)(implicit
  executionContext: ExecutionContext
) {

  implicit val readsOptionalGroupInfo: Reads[Option[GroupInfo]] = _.validateOpt[GroupInfo]

  private val customerStatusExistingRelationshipsCacheEnabled: Boolean = configuration.underlying.getBoolean(
    "agent.customerStatusExistingRelationships.cache.enabled"
  )

  private def createCache[T](
    enabled: Boolean,
    collectionName: String,
    ttlConfigKey: String
  )(implicit
    reads: Reads[T],
    writes: Writes[T]
  ): Cache[T] =
    if (enabled) {
      new MongoCache[T](
        cacheRepositoryFactory,
        collectionName,
        ttlConfigKey
      )
    }
    else
      new DoNotCache[T]

  val customerStatusExistingRelationshipsCache: Cache[Boolean] = createCache[Boolean](
    enabled = customerStatusExistingRelationshipsCacheEnabled,
    collectionName = "agent-customerStatus-existingRelationships-cache",
    ttlConfigKey = "agent.customerStatusExistingRelationships.cache.expires"
  )

}

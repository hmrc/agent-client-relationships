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

package uk.gov.hmrc.agentclientrelationships.repository

import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.Filters.{and, equal, in}
import org.mongodb.scala.model.{IndexModel, IndexOptions, Indexes}
import play.api.Logging
import uk.gov.hmrc.agentclientrelationships.model.PartialAuthRelationship
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.agentmtdidentifiers.model.Service.{HMRCMTDIT, HMRCMTDITSUPP}
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import org.mongodb.scala.model.Updates._

import java.time.Instant
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class PartialAuthRepository @Inject() (mongoComponent: MongoComponent)(implicit ec: ExecutionContext)
    extends PlayMongoRepository[PartialAuthRelationship](
      mongoComponent = mongoComponent,
      collectionName = "partial-auth",
      domainFormat = PartialAuthRelationship.format,
      indexes = Seq(
        IndexModel(Indexes.ascending("service", "nino", "arn", "active")),
        IndexModel(
          Indexes.ascending("service", "nino", "arn"),
          IndexOptions()
            .partialFilterExpression(BsonDocument("active" -> true))
            .unique(true)
            .name("activeRelationshipsIndex")
        ),
        IndexModel(Indexes.ascending("nino"))
      ),
      replaceIndexes = true
    )
    with Logging {

  override lazy val requiresTtlIndex: Boolean = false

  def create(
    created: Instant,
    arn: Arn,
    service: String,
    nino: Nino
  ): Future[Unit] = {
    require(List(HMRCMTDIT, HMRCMTDITSUPP).contains(service))
    val partialAuth =
      PartialAuthRelationship(created, arn.value, service, nino.value, active = true, lastUpdated = created)
    collection.insertOne(partialAuth).toFuture().map(_ => ())
  }

  def findActive(nino: Nino, arn: Arn): Future[Option[PartialAuthRelationship]] =
    collection
      .find(
        and(
          in("service", HMRCMTDIT, HMRCMTDITSUPP),
          equal("nino", nino.value),
          equal("arn", arn.value),
          equal("active", true)
        )
      )
      .headOption()

  def findActive(serviceId: String, nino: Nino, arn: Arn): Future[Option[PartialAuthRelationship]] =
    collection
      .find(
        and(
          equal("service", serviceId),
          equal("nino", nino.value),
          equal("arn", arn.value),
          equal("active", true)
        )
      )
      .headOption()

  def findByNino(nino: Nino): Future[Option[PartialAuthRelationship]] =
    collection.find(equal("nino", nino.value)).headOption()

  /* this will only find partially authorised ITSA main agents for a given nino string */
  def findMainAgent(nino: String): Future[Option[PartialAuthRelationship]] =
    collection
      .find(
        and(
          equal("service", HMRCMTDIT),
          equal("nino", nino),
          equal("active", true)
        )
      )
      .headOption()

  def deauthorise(serviceId: String, nino: Nino, arn: Arn, updated: Instant): Future[Boolean] =
    collection
      .updateOne(
        and(
          equal("service", serviceId),
          equal("nino", nino.value),
          equal("arn", arn.value),
          equal("active", true)
        ),
        combine(set("active", false), set("lastUpdated", updated))
      )
      .toFuture()
      .map(_.wasAcknowledged())

  // for example when a partialAuth becomes a MTD relationship we want to delete the partialAuth
  def deleteActivePartialAuth(serviceId: String, nino: Nino, arn: Arn): Future[Boolean] =
    collection
      .deleteOne(
        and(
          equal("service", serviceId),
          equal("nino", nino.value),
          equal("arn", arn.value),
          equal("active", true)
        )
      )
      .toFuture()
      .map(_.getDeletedCount == 1L)
}

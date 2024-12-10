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

import org.mongodb.scala.model.Filters.{and, equal}
import org.mongodb.scala.model.{IndexModel, IndexOptions, Indexes}
import play.api.Logging
import uk.gov.hmrc.agentclientrelationships.model.PartialAuthRelationship
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.agentmtdidentifiers.model.Service.{HMRCMTDIT, HMRCMTDITSUPP}
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository

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
        IndexModel(Indexes.ascending("service", "nino", "arn"), IndexOptions().name("clientQueryIndex").unique(true))
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
    val partialAuth = PartialAuthRelationship(created, arn.value, service, nino.value)
    collection.insertOne(partialAuth).toFuture().map(_ => ())
  }

  def find(serviceId: String, nino: Nino, arn: Arn): Future[Option[PartialAuthRelationship]] =
    collection
      .find(
        and(
          equal("service", serviceId),
          equal("nino", nino.value),
          equal("arn", arn.value)
        )
      )
      .headOption()
}

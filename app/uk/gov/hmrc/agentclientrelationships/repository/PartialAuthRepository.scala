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
import uk.gov.hmrc.agentclientrelationships.model.PartialAuthInvitation
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, Service}
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository

import java.time.Instant
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class PartialAuthRepository @Inject() (mongoComponent: MongoComponent)(implicit ec: ExecutionContext)
    extends PlayMongoRepository[PartialAuthInvitation](
      mongoComponent = mongoComponent,
      collectionName = "partial-auth",
      domainFormat = PartialAuthInvitation.format,
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
    service: Service,
    nino: Nino
  ): Future[PartialAuthInvitation] = {
    val partialAuth = PartialAuthInvitation(created, arn.value, service.id, nino.value)
    collection.insertOne(partialAuth).toFuture().map(_ => partialAuth)
  }

  def find(service: Service, nino: Nino, arn: Arn): Future[Option[PartialAuthInvitation]] =
    collection
      .find(
        and(
          equal("service", service.id),
          equal("nino", nino),
          equal("arn", arn.value)
        )
      )
      .headOption()

  def deletePartialAuth(service: Service, nino: Nino, arn: Arn): Future[Boolean] =
    collection
      .deleteOne(
        and(
          equal("arn", arn.value),
          equal("service", service.id),
          equal("clientId", nino.value)
        )
      )
      .toFuture()
      .map(_.wasAcknowledged())
}

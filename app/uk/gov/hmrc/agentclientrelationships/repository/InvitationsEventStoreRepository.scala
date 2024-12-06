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
import uk.gov.hmrc.agentclientrelationships.model.{InvitationEvent, InvitationStatus}
import uk.gov.hmrc.agentmtdidentifiers.model.ClientIdentifier.ClientId
import uk.gov.hmrc.agentmtdidentifiers.model.Service
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository

import java.time.Instant
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class InvitationsEventStoreRepository @Inject() (mongoComponent: MongoComponent)(implicit ec: ExecutionContext)
    extends PlayMongoRepository[InvitationEvent](
      mongoComponent = mongoComponent,
      collectionName = "invitations-event-store",
      domainFormat = InvitationEvent.format,
      indexes = Seq(
        IndexModel(Indexes.ascending("service", "clientId"), IndexOptions().name("clientQueryIndex"))
      ),
      replaceIndexes = true
    )
    with Logging {

  override lazy val requiresTtlIndex: Boolean = false

  def create(
    status: InvitationStatus,
    created: Instant,
    arn: String,
    service: String,
    clientId: String,
    deauthorisedBy: Option[String]
  ): Future[InvitationEvent] = {
    val invitationEvent = InvitationEvent(status, created, arn, service, clientId, deauthorisedBy)
    collection.insertOne(invitationEvent).toFuture().map(_ => invitationEvent)
  }

  def findAllForClient(service: Service, clientId: ClientId): Future[Seq[InvitationEvent]] =
    collection
      .find(
        and(
          equal("service", service.id),
          equal("clientId", clientId.value)
        )
      )
      .toFuture()
}

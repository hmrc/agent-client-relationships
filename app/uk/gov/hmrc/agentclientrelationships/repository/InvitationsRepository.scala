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

import org.mongodb.scala.model.Filters.{and, equal, in}
import org.mongodb.scala.model.Updates.{combine, set}
import org.mongodb.scala.model._
import org.mongodb.scala.result.InsertOneResult
import play.api.Logging
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model._
import uk.gov.hmrc.agentclientrelationships.repository.FieldKeys.{arnKey, clientIdKey, invitationIdKey, serviceKey, statusKey, suppliedClientIdKey}
import uk.gov.hmrc.agentmtdidentifiers.model.ClientIdentifier.ClientId
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId, Service}
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}

import java.time.{Instant, LocalDate}
import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

object FieldKeys {
  val arnKey: String = "arn"
  val invitationIdKey: String = "invitationId"
  val clientIdKey: String = "clientId"
  val suppliedClientIdKey: String = "suppliedClientId"
  val serviceKey: String = "service"
  val statusKey: String = "status"
}

@Singleton
class InvitationsRepository @Inject() (mongoComponent: MongoComponent, appConfig: AppConfig)(implicit
  ec: ExecutionContext
) extends PlayMongoRepository[Invitation](
      mongoComponent = mongoComponent,
      collectionName = "invitations",
      domainFormat = Invitation.mongoFormat,
      indexes = Seq(
        IndexModel(Indexes.ascending(arnKey), IndexOptions().name("arnIndex")),
        IndexModel(Indexes.ascending(invitationIdKey), IndexOptions().name("invitationIdIndex").unique(true)),
        IndexModel(
          Indexes.ascending("created"),
          IndexOptions().name("timeToLive").expireAfter(appConfig.invitationsTtl, TimeUnit.DAYS)
        ),
        IndexModel(Indexes.ascending(suppliedClientIdKey, statusKey, serviceKey))
      ),
      replaceIndexes = true
    )
    with Logging {

  def create(
    arn: String,
    service: Service,
    clientId: ClientId,
    suppliedClientId: ClientId,
    clientName: String,
    expiryDate: LocalDate,
    clientType: Option[String]
  ): Future[Invitation] = {
    val invitation = Invitation.createNew(arn, service, clientId, suppliedClientId, clientName, expiryDate, clientType)
    collection.insertOne(invitation).toFuture().map(_ => invitation)
  }

  def migrateActivePartialAuthInvitation(
    invitation: Invitation
  ): Future[InsertOneResult] =
    collection.insertOne(invitation).toFuture()

  def findOneByIdForAgent(arn: String, invitationId: String): Future[Option[Invitation]] =
    collection
      .find(
        combine(
          equal(arnKey, arn),
          equal(invitationIdKey, invitationId)
        )
      )
      .headOption()

  def findOneById(invitationId: String): Future[Option[Invitation]] =
    collection
      .find(equal(invitationIdKey, invitationId))
      .headOption()

  def findAllBy(
    arn: Option[String] = None,
    services: Seq[String] = Nil,
    clientIds: Seq[String] = Nil,
    status: Option[InvitationStatus] = None
  ): Future[Seq[Invitation]] =
    collection
      .find(
        and(
          Seq(
            arn.map(equal(arnKey, _)),
            if (services.nonEmpty) Some(in(serviceKey, services: _*)) else None,
            if (clientIds.nonEmpty) Some(in(clientIdKey, clientIds: _*)) else None,
            status.map(a => equal("status", Codecs.toBson[InvitationStatus](a)))
          ).flatten: _*
        )
      )
      .toFuture()

  def findOneByIdForClient(invitationId: String): Future[Option[Invitation]] =
    collection
      .find(
        equal(invitationIdKey, invitationId)
      )
      .headOption()

  def findAllForAgent(arn: String): Future[Seq[Invitation]] =
    collection.find(equal(arnKey, arn)).toFuture()

  def findAllForAgent(
    arn: String,
    services: Seq[String],
    clientIds: Seq[String],
    isSuppliedClientId: Boolean = false
  ): Future[Seq[Invitation]] =
    collection
      .find(
        and(
          equal(arnKey, arn),
          in(serviceKey, services: _*),
          in(if (isSuppliedClientId) "suppliedClientId" else "clientId", clientIds.map(_.replaceAll(" ", "")): _*)
        )
      )
      .toFuture()

  def findByArnClientIdService(arn: Arn, suppliedClientId: ClientId, service: Service): Future[Seq[Invitation]] =
    collection
      .find(
        and(equal(arnKey, arn.value), equal("suppliedClientId", suppliedClientId.value), equal("service", service.id))
      )
      .toFuture()

  def updateStatus(
    invitationId: String,
    status: InvitationStatus,
    timestamp: Option[Instant] = None
  ): Future[Option[Invitation]] =
    collection
      .findOneAndUpdate(
        equal(invitationIdKey, invitationId),
        combine(
          set("status", Codecs.toBson(status)),
          set("lastUpdated", timestamp.getOrElse(Instant.now()))
        ),
        FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
      )
      .toFutureOption()

  def deauthInvitation(
    invitationId: String,
    relationshipEndedBy: String,
    timestamp: Option[Instant] = None
  ): Future[Option[Invitation]] =
    collection
      .findOneAndUpdate(
        equal(invitationIdKey, invitationId),
        combine(
          set("status", Codecs.toBson[InvitationStatus](DeAuthorised)),
          set("lastUpdated", timestamp.getOrElse(Instant.now())),
          set("relationshipEndedBy", relationshipEndedBy)
        ),
        FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
      )
      .toFutureOption()

  def updateStatusFromTo(
    invitationId: String,
    fromStatus: InvitationStatus,
    toStatus: InvitationStatus,
    relationshipEndedBy: Option[String] = None,
    lastUpdated: Option[Instant] = None
  ): Future[Option[Invitation]] =
    collection
      .findOneAndUpdate(
        and(
          equal(invitationIdKey, invitationId),
          equal("status", Codecs.toBson[InvitationStatus](fromStatus))
        ),
        combine(
          (Seq(
            Some(set("status", Codecs.toBson(toStatus))),
            Some(set("lastUpdated", lastUpdated.getOrElse(Instant.now()))),
            relationshipEndedBy.map(set("relationshipEndedBy", _))
          ).flatten): _*
        ),
        FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
      )
      .toFutureOption()

  def updatePartialAuthToAcceptedStatus(arn: Arn, service: String, nino: Nino, mtdItId: MtdItId): Future[Boolean] =
    collection
      .updateOne(
        and(
          equal(arnKey, arn.value),
          equal("clientId", nino.value),
          equal("service", service),
          equal("status", Codecs.toBson[InvitationStatus](PartialAuth))
        ),
        combine(
          set("status", Codecs.toBson[InvitationStatus](Accepted)),
          set("lastUpdated", Instant.now),
          set("clientId", mtdItId.value),
          set("clientIdType", "MTDITID")
        )
      )
      .toFuture()
      .map(_.getModifiedCount == 1L)

  def updateClientIdAndType(
    clientId: String,
    clientIdType: String,
    newClientId: String,
    newClientIdType: String
  ): Future[Boolean] =
    collection
      .updateOne(
        and(equal("clientId", clientId), equal("clientIdType", clientIdType)),
        combine(
          set("clientId", newClientId),
          set("clientIdType", newClientIdType),
          set("suppliedClientId", newClientId),
          set("suppliedClientIdType", newClientIdType),
          set("lastUpdated", Instant.now)
        )
      )
      .toFuture()
      .map(_.getModifiedCount == 1L)

  def deauthorise(
    arn: String,
    suppliedClientId: String,
    service: String,
    relationshipEndedBy: String
  ): Future[Option[Invitation]] =
    collection
      .findOneAndUpdate(
        and(
          equal(arnKey, arn),
          equal("service", service),
          equal("suppliedClientId", suppliedClientId),
          equal("status", Codecs.toBson[InvitationStatus](Accepted)) // TODO This will not work for partial auth
        ),
        combine(
          set("status", Codecs.toBson[InvitationStatus](DeAuthorised)),
          set("relationshipEndedBy", relationshipEndedBy),
          set("lastUpdated", Instant.now())
        ),
        FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
      )
      .toFutureOption()

  def findAllPendingForClient(clientId: String, services: Seq[String]): Future[Seq[Invitation]] =
    collection
      .find(
        and(
          equal(suppliedClientIdKey, clientId),
          equal(statusKey, Codecs.toBson[InvitationStatus](Pending)),
          in(serviceKey, services: _*)
        )
      )
      .toFuture()

}

object InvitationsRepository {
  val endedByClient = "Client"
  val endedByHMRC = "HMRC"
  val endedByAgent = "Agent"
}

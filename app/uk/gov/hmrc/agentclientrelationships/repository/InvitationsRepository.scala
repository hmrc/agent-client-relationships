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

import org.mongodb.scala.Observable
import org.mongodb.scala.bson.{BsonValue, conversions}
import org.mongodb.scala.model.Accumulators.addToSet
import org.mongodb.scala.model.Aggregates.facet
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.Updates.{combine, set}
import org.mongodb.scala.model._
import org.mongodb.scala.result.InsertOneResult
import play.api.Logging
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model._
import uk.gov.hmrc.agentclientrelationships.repository.FieldKeys._
import uk.gov.hmrc.agentclientrelationships.util.CryptoUtil.encryptedString
import uk.gov.hmrc.agentmtdidentifiers.model.ClientIdentifier.ClientId
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId, Service}
import uk.gov.hmrc.crypto.{Decrypter, Encrypter}
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}

import java.net.URLDecoder
import java.time.{Instant, LocalDate}
import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Named, Singleton}
import scala.concurrent.{ExecutionContext, Future}

object FieldKeys {
  val arnKey: String = "arn"
  val invitationIdKey: String = "invitationId"
  val clientIdKey: String = "clientId"
  val suppliedClientIdKey: String = "suppliedClientId"
  val serviceKey: String = "service"
  val statusKey: String = "status"
  val clientNameKey: String = "clientName"
  val expiryDateKey: String = "expiryDate"
  val warningEmaiSentKey: String = "warningEmailSent"
  val expiredEmailSentKey: String = "expiredEmailSent"
}

@Singleton
class InvitationsRepository @Inject() (mongoComponent: MongoComponent, appConfig: AppConfig)(implicit
  ec: ExecutionContext,
  @Named("aes") crypto: Encrypter with Decrypter
) extends PlayMongoRepository[Invitation](
      mongoComponent = mongoComponent,
      collectionName = "invitations",
      domainFormat = Invitation.mongoFormat,
      indexes = Seq(
        IndexModel(Indexes.ascending(arnKey), IndexOptions().name("arnIndex")),
        IndexModel(Indexes.ascending(invitationIdKey), IndexOptions().name("invitationIdIndex").unique(true)),
        IndexModel(
          Indexes.ascending(arnKey, serviceKey, suppliedClientIdKey),
          IndexOptions()
            .partialFilterExpression(equal(statusKey, Codecs.toBson[InvitationStatus](Pending)))
            .name("uniquePendingIndex")
            .unique(true)
        ),
        IndexModel(
          Indexes.ascending("created"),
          IndexOptions().name("timeToLive").expireAfter(appConfig.invitationsTtl, TimeUnit.DAYS)
        ),
        IndexModel(Indexes.ascending(clientIdKey)),
        IndexModel(Indexes.ascending(suppliedClientIdKey)),
        IndexModel(Indexes.ascending(statusKey)),
        IndexModel(Indexes.ascending(serviceKey)),
        IndexModel(Indexes.ascending(clientNameKey)),
        IndexModel(Indexes.ascending(statusKey, warningEmaiSentKey)),
        IndexModel(Indexes.ascending(statusKey, expiredEmailSentKey))
      ),
      replaceIndexes = true,
      extraCodecs = Seq(
        Codecs.playFormatCodec(MongoTrackRequestsResult.format)
      )
    )
    with Logging {

  // scalastyle:off parameter.number
  def create(
    arn: String,
    service: Service,
    clientId: ClientId,
    suppliedClientId: ClientId,
    clientName: String,
    agencyName: String,
    agencyEmail: String,
    expiryDate: LocalDate,
    clientType: Option[String]
  ): Future[Invitation] = {
    val invitation = Invitation.createNew(
      arn,
      service,
      clientId,
      suppliedClientId,
      clientName,
      agencyName,
      agencyEmail,
      expiryDate,
      clientType
    )
    collection.insertOne(invitation).toFuture().map(_ => invitation)
  }
  // scalastyle:on

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

  def cancelByIdForAgent(arn: String, invitationId: String): Future[Boolean] =
    collection
      .updateOne(
        and(
          equal(arnKey, arn),
          equal(invitationIdKey, invitationId),
          equal("status", Codecs.toBson[InvitationStatus](Pending))
        ),
        combine(
          set("status", Codecs.toBson[InvitationStatus](Cancelled)),
          set("lastUpdated", Instant.now())
        )
      )
      .toFuture()
      .map(_.getModifiedCount == 1L)

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
    if (arn.isEmpty && clientIds.isEmpty) Future.successful(Nil) // no user-specific identifiers were provided
    else
      collection
        .find(
          and(
            Seq(
              arn.map(equal(arnKey, _)),
              if (services.nonEmpty) Some(in(serviceKey, services: _*)) else None,
              if (clientIds.nonEmpty) Some(in(clientIdKey, clientIds.map(encryptedString): _*)) else None,
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
          in(
            if (isSuppliedClientId) "suppliedClientId" else "clientId",
            clientIds.map(_.replaceAll(" ", "")).map(encryptedString): _*
          )
        )
      )
      .toFuture()

  def updateStatus(
    invitationId: String,
    status: InvitationStatus,
    timestamp: Option[Instant] = None
  ): Future[Invitation] =
    collection
      .findOneAndUpdate(
        equal(invitationIdKey, invitationId),
        combine(
          set("status", Codecs.toBson(status)),
          set("lastUpdated", timestamp.getOrElse(Instant.now()))
        ),
        FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
      )
      .headOption()
      .map(_.getOrElse(throw new RuntimeException(s"Could not find an invitation with invitationId '$invitationId'")))

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
          equal("clientId", encryptedString(nino.value)),
          equal("service", service),
          equal("status", Codecs.toBson[InvitationStatus](PartialAuth))
        ),
        combine(
          set("status", Codecs.toBson[InvitationStatus](Accepted)),
          set("lastUpdated", Instant.now),
          set("clientId", encryptedString(mtdItId.value)),
          set("clientIdType", "MTDITID")
        )
      )
      .toFuture()
      .map(_.getModifiedCount == 1L)

  def updateInvitation(
    service: String,
    clientId: String,
    clientIdType: String,
    newService: String,
    newClientId: String,
    newClientIdType: String
  ): Future[Boolean] =
    collection
      .updateOne(
        and(
          equal(serviceKey, service),
          equal(clientIdKey, encryptedString(clientId)),
          equal("clientIdType", clientIdType)
        ),
        combine(
          set(serviceKey, newService),
          set(clientIdKey, encryptedString(newClientId)),
          set("clientIdType", newClientIdType),
          set(suppliedClientIdKey, encryptedString(newClientId)),
          set("suppliedClientIdType", newClientIdType),
          set("lastUpdated", Instant.now)
        )
      )
      .toFuture()
      .map(_.getModifiedCount == 1L)

  // Does not support deauthorising partial auth
  // Must be called with mtditid for ITSA (e.g. remove authorisation controller converts nino to mtditid at the very beginning)
  def deauthorise(
    arn: String,
    clientId: String,
    service: String,
    relationshipEndedBy: String
  ): Future[Option[Invitation]] =
    collection
      .findOneAndUpdate(
        and(
          equal(arnKey, arn),
          equal("service", service),
          equal("clientId", encryptedString(clientId)),
          equal("status", Codecs.toBson[InvitationStatus](Accepted))
        ),
        combine(
          set("status", Codecs.toBson[InvitationStatus](DeAuthorised)),
          set("relationshipEndedBy", relationshipEndedBy),
          set("lastUpdated", Instant.now())
        ),
        FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
      )
      .toFutureOption()

  def findAllPendingForSuppliedClient(clientId: String, services: Seq[String]): Future[Seq[Invitation]] =
    collection
      .find(
        and(
          equal(suppliedClientIdKey, encryptedString(clientId)),
          equal(statusKey, Codecs.toBson[InvitationStatus](Pending)),
          in(serviceKey, services: _*)
        )
      )
      .toFuture()

  def findAllForClient(clientId: String, services: Seq[String]): Future[Seq[Invitation]] =
    collection
      .find(
        and(
          equal(clientIdKey, encryptedString(clientId)),
          in(serviceKey, services: _*)
        )
      )
      .toFuture()

  def updatePartialAuthToDeAuthorisedStatus(
    arn: Arn,
    service: String,
    nino: Nino,
    relationshipEndedBy: String
  ): Future[Option[Invitation]] =
    collection
      .findOneAndUpdate(
        and(
          equal("arn", arn.value),
          equal("clientId", encryptedString(nino.value)),
          equal("service", service),
          equal("status", Codecs.toBson[InvitationStatus](PartialAuth))
        ),
        combine(
          set("status", Codecs.toBson[InvitationStatus](DeAuthorised)),
          set("relationshipEndedBy", relationshipEndedBy),
          set("lastUpdated", Instant.now)
        ),
        FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
      )
      .toFutureOption()

  private def makeTrackRequestsFilters(
    statusFilter: Option[String],
    clientName: Option[String]
  ): conversions.Bson = Aggregates.filter(
    and(
      statusFilter
        .map(status =>
          if (status == "Accepted") or(equal(statusKey, status), equal(statusKey, PartialAuth.toString))
          else equal(statusKey, status)
        )
        .getOrElse(Filters.exists(statusKey)),
      clientName
        .map(name => equal(clientNameKey, encryptedString(URLDecoder.decode(name))))
        .getOrElse(Filters.exists(clientNameKey))
    )
  )

  def trackRequests(
    arn: String,
    statusFilter: Option[String],
    clientName: Option[String],
    pageNumber: Int,
    pageSize: Int
  ): Future[TrackRequestsResult] = {
    val filters = makeTrackRequestsFilters(statusFilter, clientName)
    val fullAggregatePipeline = Seq(
      Aggregates.filter(equal(arnKey, arn)),
      facet(
        Facet("clientNamesFacet", Aggregates.group(null, addToSet("clientNames", "$clientName"))),
        Facet("availableFiltersFacet", Aggregates.group(null, addToSet("availableFilters", "$status"))),
        Facet("totalResultsFacet", filters, Aggregates.count("count")),
        Facet(
          "requests",
          filters,
          Aggregates.sort(Sorts.descending("created")),
          Aggregates.skip((pageNumber - 1) * pageSize),
          Aggregates.limit(pageSize)
        )
      )
    )
    for {
      results <- collection
                   .aggregate[MongoTrackRequestsResult](fullAggregatePipeline)
                   .toFuture()
                   .map(_.headOption.getOrElse(MongoTrackRequestsResult()))
    } yield TrackRequestsResult(
      requests = results.requests,
      clientNames = results.clientNamesFacet.headOption.map(_.clientNames.sorted).getOrElse(Nil),
      availableFilters = results.availableFiltersFacet.headOption.map(_.availableFilters.sorted).getOrElse(Nil),
      totalResults = results.totalResultsFacet.headOption.map(_.count).getOrElse(0),
      pageNumber = pageNumber,
      filtersApplied = (statusFilter, clientName) match {
        case (Some(f), Some(c)) => Some(Map("statusFilter" -> f, "clientFilter" -> c))
        case (Some(f), None)    => Some(Map("statusFilter" -> f))
        case (None, Some(c))    => Some(Map("clientFilter" -> c))
        case _                  => None
      }
    )
  }

  def findAllForWarningEmail: Observable[WarningEmailAggregationResult] = {

    val aggregatePipeline = Seq(
      Aggregates.filter(
        and(
          equal(statusKey, Codecs.toBson[InvitationStatus](Pending)),
          equal(warningEmaiSentKey, false),
          lte(expiryDateKey, LocalDate.now().plusDays(5L)),
          gte(expiryDateKey, LocalDate.now())
        )
      ),
      Aggregates.group("$arn", addToSet("invitations", "$$ROOT"))
    )

    collection
      .aggregate[BsonValue](aggregatePipeline)
      .map(Codecs.fromBson[WarningEmailAggregationResult])
  }

  def findAllForExpiredEmail: Observable[Invitation] =
    collection
      .find(
        and(
          equal(statusKey, Codecs.toBson[InvitationStatus](Pending)),
          equal(expiredEmailSentKey, false),
          lte(expiryDateKey, LocalDate.now())
        )
      )

  def updateWarningEmailSent(invitationId: String): Future[Boolean] =
    collection
      .updateOne(equal(invitationIdKey, invitationId), set(warningEmaiSentKey, true))
      .toFuture()
      .map(_.getModifiedCount == 1L)

  def updateExpiredEmailSent(invitationId: String): Future[Boolean] =
    collection
      .updateOne(equal(invitationIdKey, invitationId), set(expiredEmailSentKey, true))
      .toFuture()
      .map(_.getModifiedCount == 1L)
}

object InvitationsRepository {
  val endedByClient = "Client"
  val endedByHMRC = "HMRC"
  val endedByAgent = "Agent"
}

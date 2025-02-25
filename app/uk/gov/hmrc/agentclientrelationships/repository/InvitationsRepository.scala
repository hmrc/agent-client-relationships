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

import org.mongodb.scala.bson.conversions
import org.mongodb.scala.model.Accumulators.addToSet
import org.mongodb.scala.model.Aggregates.facet
import org.mongodb.scala.model.Filters.{and, equal, in, or}
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
          Indexes.ascending("created"),
          IndexOptions().name("timeToLive").expireAfter(appConfig.invitationsTtl, TimeUnit.DAYS)
        ),
        IndexModel(Indexes.ascending(clientIdKey)),
        IndexModel(Indexes.ascending(suppliedClientIdKey)),
        IndexModel(Indexes.ascending(statusKey)),
        IndexModel(Indexes.ascending(serviceKey)),
        IndexModel(Indexes.ascending(clientNameKey))
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

  def findByArnClientIdService(arn: Arn, suppliedClientId: ClientId, service: Service): Future[Seq[Invitation]] =
    collection
      .find(
        and(
          equal(arnKey, arn.value),
          equal("suppliedClientId", encryptedString(suppliedClientId.value)),
          equal("service", service.id)
        )
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

  def updateClientIdAndType(
    clientId: String,
    clientIdType: String,
    newClientId: String,
    newClientIdType: String
  ): Future[Boolean] =
    collection
      .updateOne(
        and(equal("clientId", encryptedString(clientId)), equal("clientIdType", clientIdType)),
        combine(
          set("clientId", encryptedString(newClientId)),
          set("clientIdType", newClientIdType),
          set("suppliedClientId", encryptedString(newClientId)),
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
          equal("suppliedClientId", encryptedString(suppliedClientId)),
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
          equal(suppliedClientIdKey, encryptedString(clientId)),
          equal(statusKey, Codecs.toBson[InvitationStatus](Pending)),
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
}

object InvitationsRepository {
  val endedByClient = "Client"
  val endedByHMRC = "HMRC"
  val endedByAgent = "Agent"
}

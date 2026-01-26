/*
 * Copyright 2025 HM Revenue & Customs
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
import org.mongodb.scala.bson.BsonValue
import org.mongodb.scala.bson.conversions
import org.mongodb.scala.model.Accumulators.addToSet
import org.mongodb.scala.model.Aggregates.facet
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.Updates.combine
import org.mongodb.scala.model.Updates.set
import org.mongodb.scala.model._
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model._
import uk.gov.hmrc.agentclientrelationships.model.identifiers.ClientIdentifier.ClientId
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Arn
import uk.gov.hmrc.agentclientrelationships.model.identifiers.MtdItId
import uk.gov.hmrc.agentclientrelationships.model.identifiers.NinoWithoutSuffix
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service
import uk.gov.hmrc.agentclientrelationships.model.invitation.CancelInvitationResponse
import uk.gov.hmrc.agentclientrelationships.model.invitation.CancelInvitationResponse._
import uk.gov.hmrc.agentclientrelationships.repository.FieldKeys._
import uk.gov.hmrc.agentclientrelationships.util.CryptoUtil.encryptedString
import uk.gov.hmrc.agentclientrelationships.util.RequestAwareLogging
import uk.gov.hmrc.crypto.Decrypter
import uk.gov.hmrc.crypto.Encrypter
import uk.gov.hmrc.mdc.Mdc
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.Codecs
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository

import java.net.URLDecoder
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

object FieldKeys {

  val arnKey: String = "arn"
  val invitationIdKey: String = "invitationId"
  val clientIdKey: String = "clientId"
  val clientIdTypeKey: String = "clientIdTypeKey"
  val suppliedClientIdKey: String = "suppliedClientId"
  val suppliedClientIdTypeKey: String = "suppliedClientIdType"
  val serviceKey: String = "service"
  val statusKey: String = "status"
  val clientNameKey: String = "clientName"
  val expiryDateKey: String = "expiryDate"
  val warningEmaiSentKey: String = "warningEmailSent"
  val expiredEmailSentKey: String = "expiredEmailSent"
  val lastUpdatedKey: String = "lastUpdated"
  val relationshipEndedByKey: String = "relationshipEndedBy"

}

@Singleton
class InvitationsRepository @Inject() (
  mongoComponent: MongoComponent,
  appConfig: AppConfig
)(implicit
  ec: ExecutionContext,
  @Named("aes")
  crypto: Encrypter
    with Decrypter
)
extends PlayMongoRepository[Invitation](
  mongoComponent = mongoComponent,
  collectionName = "invitations",
  domainFormat = Invitation.mongoFormat,
  indexes = Seq(
    IndexModel(Indexes.ascending(arnKey), IndexOptions().name("arnIndex")),
    IndexModel(Indexes.ascending(invitationIdKey), IndexOptions().name("invitationIdIndex").unique(true)),
    IndexModel(
      Indexes.ascending(
        arnKey,
        serviceKey,
        suppliedClientIdKey
      ),
      IndexOptions()
        .partialFilterExpression(equal(statusKey, Codecs.toBson[InvitationStatus](Pending)))
        .name("uniquePendingIndex")
        .unique(true)
    ),
    IndexModel(
      Indexes.ascending("created"),
      IndexOptions().name("timeToLive").expireAfter(appConfig.invitationsTtl, TimeUnit.DAYS)
    ),
    IndexModel(
      Indexes.compoundIndex(
        Indexes.ascending(arnKey),
        Indexes.descending("created")
      ),
      IndexOptions().name("arnCreatedIdx")
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
  extraCodecs = Seq(Codecs.playFormatCodec(MongoTrackRequestsResult.format))
)
with RequestAwareLogging {

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
  ): Future[Invitation] = Mdc.preservingMdc {
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

  def findOneByIdForAgent(
    arn: String,
    invitationId: String
  ): Future[Option[Invitation]] = Mdc.preservingMdc {
    collection
      .find(combine(equal(arnKey, arn), equal(invitationIdKey, invitationId)))
      .headOption()
  }

  def cancelByIdForAgent(
    arn: String,
    invitationId: String
  ): Future[CancelInvitationResponse] = Mdc.preservingMdc {
    val filterById = equal(invitationIdKey, invitationId)

    collection.find(filterById).headOption().flatMap {
      case None => Future.successful(NotFound)
      case Some(invitation) if invitation.status != Pending => Future.successful(WrongInvitationStatus)
      case Some(invitation) if invitation.arn != arn => Future.successful(NoPermission)
      case Some(_) =>
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
          .map(_ => Success)
    }
  }

  def findOneById(invitationId: String): Future[Option[Invitation]] = Mdc.preservingMdc {
    collection
      .find(equal(invitationIdKey, invitationId))
      .headOption()
  }

  def findAllBy(
    arn: Option[String] = None,
    services: Seq[String] = Nil,
    clientIds: Seq[String] = Nil,
    status: Option[InvitationStatus] = None,
    isSuppliedClientId: Boolean = false
  ): Future[Seq[Invitation]] = Mdc.preservingMdc {
    if (arn.isEmpty && clientIds.isEmpty)
      Future.successful(Nil) // no user-specific identifiers were provided
    else
      collection
        .find(
          and(
            Seq(
              arn.map(equal(arnKey, _)),
              if (services.nonEmpty)
                Some(in(serviceKey, services: _*))
              else
                None,
              if (clientIds.nonEmpty) {
                val key =
                  if (isSuppliedClientId)
                    suppliedClientIdKey
                  else
                    clientIdKey
                Some(in(key, clientIds.flatMap(expandNinoSuffixes).map(encryptedString): _*))
              }
              else
                None,
              status.map(a => equal("status", Codecs.toBson[InvitationStatus](a)))
            ).flatten: _*
          )
        )
        .toFuture()
  }

  def findAllForAgent(arn: String): Future[Seq[Invitation]] = Mdc.preservingMdc {
    collection.find(equal(arnKey, arn)).toFuture()
  }

  def findAllForAgentService(
    arn: String,
    services: Seq[String]
  ): Future[Seq[Invitation]] = Mdc.preservingMdc {
    collection
      .find(
        and(
          equal(arnKey, arn),
          in(serviceKey, services: _*)
        )
      )
      .toFuture()
  }

  def findAllForAgent(
    arn: String,
    services: Seq[String],
    clientIds: Seq[String],
    isSuppliedClientId: Boolean = false
  ): Future[Seq[Invitation]] = Mdc.preservingMdc {
    collection
      .find(
        and(
          equal(arnKey, arn),
          in(serviceKey, services: _*),
          in(
            if (isSuppliedClientId)
              suppliedClientIdKey
            else
              clientIdKey,
            clientIds.map(_.replaceAll(" ", "")).flatMap(expandNinoSuffixes).map(encryptedString): _*
          )
        )
      )
      .toFuture()
  }

  def updateStatus(
    invitationId: String,
    status: InvitationStatus,
    timestamp: Option[Instant] = None
  ): Future[Invitation] = Mdc.preservingMdc {
    collection
      .findOneAndUpdate(
        equal(invitationIdKey, invitationId),
        combine(set("status", Codecs.toBson(status)), set("lastUpdated", timestamp.getOrElse(Instant.now()))),
        FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
      )
      .headOption()
      .map(_.getOrElse(throw new RuntimeException(s"Could not find an invitation with invitationId '$invitationId'")))
  }

  def deauthAcceptedInvitations(
    service: String,
    optArn: Option[String],
    clientId: String,
    invitationIdToIgnore: Option[String],
    relationshipEndedBy: String,
    timestamp: Instant = Instant.now()
  ): Future[Boolean] = Mdc.preservingMdc {
    collection
      .updateMany(
        and(
          Seq(
            Some(in(
              statusKey,
              Codecs.toBson[InvitationStatus](Accepted),
              Codecs.toBson[InvitationStatus](PartialAuth)
            )),
            Some(equal(serviceKey, service)),
            Some(or(
              in(suppliedClientIdKey, expandNinoSuffixes(clientId).map(encryptedString): _*),
              in(clientIdKey, expandNinoSuffixes(clientId).map(encryptedString): _*) // Some deauth requests target the MTDITID for ITSA
            )),
            optArn.map(a => equal(arnKey, a)),
            invitationIdToIgnore
              .map(id => notEqual(invitationIdKey, id))
          ).flatten: _*
        ),
        combine(
          set(statusKey, Codecs.toBson[InvitationStatus](DeAuthorised)),
          set(lastUpdatedKey, timestamp),
          set(relationshipEndedByKey, relationshipEndedBy)
        ),
        UpdateOptions()
      )
      .toFuture()
      .map(_.getModifiedCount > 0)
  }

  def updatePartialAuthToAcceptedStatus(
    arn: Arn,
    service: String,
    nino: NinoWithoutSuffix,
    mtdItId: MtdItId
  ): Future[Boolean] = Mdc.preservingMdc {
    collection
      .updateOne(
        and(
          equal(arnKey, arn.value),
          in(clientIdKey, expandNinoSuffixes(nino.value).map(encryptedString): _*),
          equal(serviceKey, service),
          equal(statusKey, Codecs.toBson[InvitationStatus](PartialAuth))
        ),
        combine(
          set(statusKey, Codecs.toBson[InvitationStatus](Accepted)),
          set(lastUpdatedKey, Instant.now),
          set(clientIdKey, encryptedString(mtdItId.value)),
          set(clientIdTypeKey, "MTDITID")
        )
      )
      .toFuture()
      .map(_.getModifiedCount == 1L)
  }

  def updateInvitation(
    service: String,
    clientId: String,
    clientIdType: String,
    newService: String,
    newClientId: String,
    newClientIdType: String
  ): Future[Boolean] = Mdc.preservingMdc {
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
  }

  private def makeTrackRequestsFilters(
    statusFilter: Option[String],
    clientName: Option[String]
  ): conversions.Bson = Aggregates.filter(
    and(
      statusFilter
        .map(status =>
          if (status == "Accepted")
            or(equal(statusKey, status), equal(statusKey, PartialAuth.toString))
          else
            equal(statusKey, status)
        )
        .getOrElse(Filters.exists(statusKey)),
      clientName
        .map(name => equal(clientNameKey, encryptedString(URLDecoder.decode(name, "UTF-8"))))
        .getOrElse(Filters.exists(clientNameKey))
    )
  )

  def trackRequests(
    arn: String,
    statusFilter: Option[String],
    clientName: Option[String],
    pageNumber: Int,
    pageSize: Int
  ): Future[TrackRequestsResult] = Mdc.preservingMdc {
    val filters = makeTrackRequestsFilters(statusFilter, clientName)
    val fullAggregatePipeline = Seq(
      Aggregates.filter(equal(arnKey, arn)),
      Aggregates.sort(Sorts.descending("created")),
      facet(
        Facet("clientNamesFacet", Aggregates.group(null, addToSet("clientNames", "$clientName"))),
        Facet("availableFiltersFacet", Aggregates.group(null, addToSet("availableFilters", "$status"))),
        Facet(
          "totalResultsFacet",
          filters,
          Aggregates.count("count")
        ),
        Facet(
          "requests",
          filters,
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
      filtersApplied =
        (statusFilter, clientName) match {
          case (Some(f), Some(c)) => Some(Map("statusFilter" -> f, "clientFilter" -> c))
          case (Some(f), None) => Some(Map("statusFilter" -> f))
          case (None, Some(c)) => Some(Map("clientFilter" -> c))
          case _ => None
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

    collection.aggregate[BsonValue](aggregatePipeline).map(Codecs.fromBson[WarningEmailAggregationResult])
  }

  def findAllForExpiredEmail: Observable[Invitation] = collection.find(
    and(
      equal(statusKey, Codecs.toBson[InvitationStatus](Pending)),
      equal(expiredEmailSentKey, false),
      lte(expiryDateKey, LocalDate.now())
    )
  )

  def updateWarningEmailSent(invitationId: String): Future[Boolean] = Mdc.preservingMdc {
    collection
      .updateOne(equal(invitationIdKey, invitationId), set(warningEmaiSentKey, true))
      .toFuture()
      .map(_.getModifiedCount == 1L)
  }

  def updateExpiredEmailSent(invitationId: String): Future[Boolean] = Mdc.preservingMdc {
    collection
      .updateOne(equal(invitationIdKey, invitationId), set(expiredEmailSentKey, true))
      .toFuture()
      .map(_.getModifiedCount == 1L)
  }

  private def expandNinoSuffixes(clientId: String): Seq[String] = {
    clientId match {
      case nino if NinoWithoutSuffix.isValid(nino) => NinoWithoutSuffix(nino).variations
      case clientId => Seq(clientId)
    }
  }

}

object InvitationsRepository {

  val endedByClient = "Client"
  val endedByHMRC = "HMRC"
  val endedByAgent = "Agent"

}

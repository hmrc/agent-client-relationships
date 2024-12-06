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
import play.api.Logging
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.{DeAuthorised, Invitation, InvitationStatus}
import uk.gov.hmrc.agentmtdidentifiers.model.ClientIdentifier.ClientId
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, Service}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}

import java.time.{Instant, LocalDate}
import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class InvitationsRepository @Inject() (mongoComponent: MongoComponent, appConfig: AppConfig)(implicit
  ec: ExecutionContext
) extends PlayMongoRepository[Invitation](
      mongoComponent = mongoComponent,
      collectionName = "invitations",
      domainFormat = Invitation.mongoFormat,
      indexes = Seq(
        IndexModel(Indexes.ascending("arn"), IndexOptions().name("arnIndex")),
        IndexModel(Indexes.ascending("invitationId"), IndexOptions().name("invitationIdIndex").unique(true)),
        IndexModel(
          Indexes.ascending("created"),
          IndexOptions().name("timeToLive").expireAfter(appConfig.invitationsTtl, TimeUnit.DAYS)
        )
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
    expiryDate: LocalDate
  ): Future[Invitation] = {
    val invitation = Invitation.createNew(arn, service, clientId, suppliedClientId, clientName, expiryDate)
    collection.insertOne(invitation).toFuture().map(_ => invitation)
  }

  def findOneById(arn: String, invitationId: String): Future[Option[Invitation]] =
    collection
      .find(
        combine(
          equal("arn", arn),
          equal("invitationId", invitationId)
        )
      )
      .headOption()

  def findAllForAgent(arn: String): Future[Seq[Invitation]] =
    collection.find(equal("arn", arn)).toFuture()

  def findAllForAgent(
    arn: String,
    services: Seq[String],
    clientIds: Seq[String],
    isSuppliedClientId: Boolean = false
  ): Future[Seq[Invitation]] =
    collection
      .find(
        and(
          equal("arn", arn),
          in("service", services: _*),
          in(if (isSuppliedClientId) "suppliedClientId" else "clientId", clientIds: _*)
        )
      )
      .toFuture()

  def findByArnClientIdService(arn: Arn, suppliedClientId: ClientId, service: Service): Future[Seq[Invitation]] =
    collection
      .find(
        and(equal("arn", arn.value), equal("suppliedClientId", suppliedClientId.value), equal("service", service.id))
      )
      .toFuture()

  def updateStatus(invitationId: String, status: InvitationStatus): Future[Option[Invitation]] =
    collection
      .findOneAndUpdate(
        equal("invitationId", invitationId),
        combine(
          set("status", Codecs.toBson(status)),
          set("lastUpdated", Instant.now())
        ),
        FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
      )
      .toFutureOption()

  def deauthorise(invitationId: String, relationshipEndedBy: String): Future[Option[Invitation]] =
    collection
      .findOneAndUpdate(
        equal("invitationId", invitationId),
        combine(
          set("status", Codecs.toBson[InvitationStatus](DeAuthorised)),
          set("relationshipEndedBy", relationshipEndedBy),
          set("lastUpdated", Instant.now())
        ),
        FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
      )
      .toFutureOption()

  // TODO WG - Confirm if that is still correct ?!? Should we update status to Deauth
  def setRelationshipEnded(invitation: Invitation, endedBy: String): Future[Invitation] =
    for {
      invitationOpt <- collection.find(equal("invitationId", invitation.invitationId)).headOption()
      modifiedOpt = invitationOpt.map { i =>
                      i.copy(
                        relationshipEndedBy = Some(endedBy)
                      )
                    }
      updated <- modifiedOpt match {
                   case Some(modified) =>
                     collection
                       .replaceOne(equal("invitationId", invitation.invitationId), modified)
                       .toFuture()
                       .flatMap { updateResult =>
                         if (updateResult.getModifiedCount != 1L)
                           Future.failed(
                             new Exception(s"Invitation ${invitation.invitationId} de-authorisation failed")
                           )
                         else
                           collection
                             .find(equal("invitationId", invitation.invitationId))
                             .headOption()
                             .map(
                               _.getOrElse(
                                 throw new Exception(
                                   s"Error de-authorising: invitation ${invitation.invitationId} not found"
                                 )
                               )
                             )
                       }
                   case None => throw new Exception(s"Invitation ${invitation.invitationId} not found")
                 }
    } yield updated

}

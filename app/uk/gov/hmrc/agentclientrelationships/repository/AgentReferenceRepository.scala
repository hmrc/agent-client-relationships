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

import org.mongodb.scala.model.Filters.equal
import org.mongodb.scala.model.Indexes.ascending
import org.mongodb.scala.model.Updates.addToSet
import org.mongodb.scala.model.IndexModel
import org.mongodb.scala.model.IndexOptions
import play.api.Logger
import play.api.Logging
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.AgentReferenceRecord
import uk.gov.hmrc.agentclientrelationships.util.CryptoUtil.encryptedString
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.crypto.Decrypter
import uk.gov.hmrc.crypto.Encrypter
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository

import javax.inject._
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@Singleton
class AgentReferenceRepository @Inject() (mongo: MongoComponent)(implicit
  ec: ExecutionContext,
  @Named("aes")
  crypto: Encrypter
    with Decrypter
)
extends PlayMongoRepository[AgentReferenceRecord](
  mongoComponent = mongo,
  collectionName = "agent-reference",
  domainFormat = AgentReferenceRecord.mongoFormat,
  indexes = List(
    IndexModel(ascending("uid"), IndexOptions().unique(true)),
    IndexModel(ascending("arn"), IndexOptions().unique(true))
  )
)
with Logging {

  val localLogger: Logger = logger

  // to support static link for agents there is no TTL
  override lazy val requiresTtlIndex: Boolean = false

  def create(agentReferenceRecord: AgentReferenceRecord): Future[Unit] = collection
    .insertOne(agentReferenceRecord)
    .toFuture()
    .map(_ => ())

  def findBy(uid: String): Future[Option[AgentReferenceRecord]] = collection.find(equal("uid", uid)).headOption()

  def findByArn(arn: Arn): Future[Option[AgentReferenceRecord]] = collection.find(equal("arn", arn.value)).headOption()

  def updateAgentName(
    uid: String,
    newAgentName: String
  ): Future[Unit] = collection
    .updateOne(equal("uid", uid), addToSet("normalisedAgentNames", encryptedString(newAgentName)))
    .toFuture()
    .map { updateOneResult =>
      if (updateOneResult.getModifiedCount == 1)
        ()
      else
        throw new RuntimeException("could not update agent reference name, no matching uid found.")
    }

  def delete(arn: Arn): Future[Unit] = collection
    .deleteOne(equal("arn", arn.value))
    .toFuture()
    .map { r =>
      if (r.getDeletedCount == 0)
        localLogger.error("could not delete agent reference record, no matching ARN found.")
      ()
    }

}

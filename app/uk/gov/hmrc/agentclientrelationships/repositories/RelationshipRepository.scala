/*
 * Copyright 2016 HM Revenue & Customs
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

package uk.gov.hmrc.agentclientrelationships.repositories

import org.joda.time.DateTime
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import reactivemongo.api.DB
import reactivemongo.bson.BSONObjectID
import uk.gov.hmrc.agentclientrelationships.model.{Arn, Relationship}
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats
import uk.gov.hmrc.mongo.{AtomicUpdate, ReactiveRepository, Repository}

import scala.concurrent.Future

trait RelationshipRepository extends Repository[Relationship, BSONObjectID] {

  def create(clientRegimeId: String, regime: String, arn: Arn): Future[Relationship]
  def removeRelationship(clientRegimeId: String, regime: String, arn: Arn): Future[_]
  def list(clientRegimeId: String, regime: String, arn: Arn): Future[List[Relationship]]
  def list(arn: Arn): Future[Seq[Relationship]]
}

class RelationshipMongoRepository(implicit mongo: () => DB) extends ReactiveRepository[Relationship, BSONObjectID]("agentClientRelationships", mongo, Relationship.mongoFormats, ReactiveMongoFormats.objectIdFormats)
        with RelationshipRepository with AtomicUpdate[Relationship] {
  override def create(clientRegimeId: String, regime: String, arn: Arn): Future[Relationship] = {
    val request = Relationship(
      id = BSONObjectID.generate,
      arn = arn,
      regime = regime,
      clientRegimeId = clientRegimeId,
      created = new DateTime()
    )

    insert(request).map(_ => request)
  }

  override def list(clientRegimeId: String, regime: String, arn: Arn): Future[List[Relationship]] =
    find("clientRegimeId" -> clientRegimeId, "regime" -> regime, "arn" -> arn)

  override def removeRelationship(clientRegimeId: String, regime: String, arn: Arn) = {
    remove(query = "clientRegimeId" -> clientRegimeId, "regime" -> regime, "arn" -> arn)
  }


  override def list(arn: Arn): Future[Seq[Relationship]] =
    find("arn" -> arn)

  override def isInsertion(newRecordId: BSONObjectID, oldRecord: Relationship): Boolean =
    newRecordId != oldRecord.id
}

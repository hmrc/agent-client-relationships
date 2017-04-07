/*
 * Copyright 2017 HM Revenue & Customs
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

import javax.inject.Inject

import com.google.inject.{ImplementedBy, Singleton}
import org.joda.time.DateTime
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.DB
import reactivemongo.bson.BSONObjectID
import uk.gov.hmrc.agentclientrelationships.model.{Arn, Relationship}
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats
import uk.gov.hmrc.mongo.{AtomicUpdate, ReactiveRepository, Repository}

import scala.concurrent.Future


@ImplementedBy(classOf[RelationshipMongoRepository])
trait RelationshipRepository extends Repository[Relationship, BSONObjectID] {

  def create(clientId: String, regime: String, arn: Arn): Future[Relationship]
  def removeRelationship(clientId: String, regime: String, arn: Arn): Future[_]
  def list(clientId: String, regime: String, arn: Arn): Future[List[Relationship]]
  def list(arn: Arn): Future[Seq[Relationship]]
}

@Singleton
class RelationshipMongoRepository @Inject() (mongoComponent: ReactiveMongoComponent) extends ReactiveRepository[Relationship, BSONObjectID]("agentClientRelationships", mongoComponent.mongoConnector.db , Relationship.mongoFormats, ReactiveMongoFormats.objectIdFormats)
        with RelationshipRepository with AtomicUpdate[Relationship] {
  override def create(clientId: String, regime: String, arn: Arn): Future[Relationship] = {
    val request = Relationship(
      id = BSONObjectID.generate,
      arn = arn,
      regime = regime,
      clientId = clientId,
      created = new DateTime()
    )

    insert(request).map(_ => request)
  }

  override def list(clientId: String, regime: String, arn: Arn): Future[List[Relationship]] =
    find("clientId" -> clientId, "regime" -> regime, "arn" -> arn)

  override def removeRelationship(clientId: String, regime: String, arn: Arn) = {
    remove(query = "clientId" -> clientId, "regime" -> regime, "arn" -> arn)
  }


  override def list(arn: Arn): Future[Seq[Relationship]] =
    find("arn" -> arn)

  override def isInsertion(newRecordId: BSONObjectID, oldRecord: Relationship): Boolean =
    newRecordId != oldRecord.id
}

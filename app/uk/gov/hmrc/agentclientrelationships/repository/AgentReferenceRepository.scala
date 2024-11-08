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

import com.google.inject.ImplementedBy
import org.mongodb.scala.model.Filters.equal
import org.mongodb.scala.model.Indexes.ascending
import org.mongodb.scala.model.Updates.addToSet
import org.mongodb.scala.model.{IndexModel, IndexOptions, UpdateOptions}
import play.api.{Logger, Logging}
import play.api.libs.json._
import uk.gov.hmrc.agentclientrelationships.repository.AgentReferenceRecord.formats
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository

import javax.inject._
import scala.collection.Seq
import scala.concurrent.{ExecutionContext, Future}

case class AgentReferenceRecord(
  uid: String,
  arn: Arn,
  normalisedAgentNames: Seq[String]
)

object AgentReferenceRecord {
  implicit val formats: Format[AgentReferenceRecord] = Json.format[AgentReferenceRecord]
}

@ImplementedBy(classOf[MongoAgentReferenceRepository])
trait AgentReferenceRepository {
  def create(agentReferenceRecord: AgentReferenceRecord): Future[Unit]
  def findBy(uid: String): Future[Option[AgentReferenceRecord]]
  def findByArn(arn: Arn): Future[Option[AgentReferenceRecord]]
  def updateAgentName(uid: String, newAgentName: String): Future[Unit]
  def delete(arn: Arn): Future[Unit]
}

@Singleton
class MongoAgentReferenceRepository @Inject() (mongo: MongoComponent)(implicit ec: ExecutionContext)
    extends PlayMongoRepository[AgentReferenceRecord](
      mongoComponent = mongo,
      collectionName = "agent-reference",
      domainFormat = formats,
      indexes = List(
        IndexModel(ascending("uid"), IndexOptions().unique(true)),
        IndexModel(ascending("arn"), IndexOptions().unique(true))
      )
    )
    with AgentReferenceRepository
    with Logging {

  val localLogger: Logger = logger

// to support static link for agents there is no TTL
  override lazy val requiresTtlIndex: Boolean = false

  override def create(agentReferenceRecord: AgentReferenceRecord): Future[Unit] =
    collection
      .insertOne(agentReferenceRecord)
      .toFuture()
      .map(_ => ())

  override def findBy(uid: String): Future[Option[AgentReferenceRecord]] =
    collection
      .find(equal("uid", uid))
      .headOption()

  override def findByArn(arn: Arn): Future[Option[AgentReferenceRecord]] =
    collection
      .find(equal("arn", arn.value))
      .headOption()

  override def updateAgentName(uid: String, newAgentName: String): Future[Unit] =
    collection
      .updateOne(equal("uid", uid), addToSet("normalisedAgentNames", newAgentName), UpdateOptions())
      .toFuture()
      .map { updateOneResult =>
        if (updateOneResult.getModifiedCount == 1) ()
        else throw new RuntimeException("could not update agent reference name, no matching uid found.")
      }

  override def delete(arn: Arn): Future[Unit] =
    collection
      .deleteOne(equal("arn", arn.value))
      .toFuture()
      .map { r =>
        if (r.getDeletedCount == 0L)
          localLogger.error("could not delete agent reference record, no matching ARN found.")
        ()
      }
}

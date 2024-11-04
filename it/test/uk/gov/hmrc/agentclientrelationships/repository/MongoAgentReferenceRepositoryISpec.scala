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

import org.mongodb.scala.MongoWriteException
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import scala.concurrent.ExecutionContext.Implicits.global

class MongoAgentReferenceRepositoryISpec extends UnitSpec with DefaultPlayMongoRepositorySupport[AgentReferenceRecord] {

  val repository = new MongoAgentReferenceRepository(mongoComponent)

  "AgentReferenceRepository" when {
    def agentReferenceRecord(uid: String, arn: String) = AgentReferenceRecord(uid, Arn(arn), Seq("stan-lee"))

    "create" should {
      "successfully create a record in the agentReferenceRepository" in {
        await(repository.create(agentReferenceRecord("SCX39TGT", "LARN7404004"))) shouldBe ()
      }

      "throw an error if ARN is duplicated" in {
        await(repository.collection.insertOne(agentReferenceRecord("SCX39TGT", "LARN7404004")).toFuture())
        an[MongoWriteException] shouldBe thrownBy {
          await(repository.create(agentReferenceRecord("SCX39TGE", "LARN7404004")))
        }
      }

      "throw an error if UID is duplicated" in {
        await(repository.collection.insertOne(agentReferenceRecord("SCX39TGT", "LARN7404004")).toFuture())
        an[MongoWriteException] shouldBe thrownBy {
          await(repository.create(agentReferenceRecord("SCX39TGT", "LARN7404005")))
        }
      }
    }

    "findBy" should {
      "successfully find a created record by its uid" in {
        await(repository.create(agentReferenceRecord("SCX39TGT", "LARN7404004")))

        await(repository.findBy("SCX39TGT")) shouldBe Some(agentReferenceRecord("SCX39TGT", "LARN7404004"))
      }
    }

    "findByArn" should {
      "successfully find a created record by its arn" in {
        await(repository.create(agentReferenceRecord("SCX39TGT", "LARN7404004")))

        await(repository.findByArn(Arn("LARN7404004"))) shouldBe Some(agentReferenceRecord("SCX39TGT", "LARN7404004"))
      }
    }

    "updateAgentName" should {
      "successfully update a created records agency name list" in {
        await(repository.create(agentReferenceRecord("SCX39TGT", "LARN7404004")))
        await(repository.updateAgentName("SCX39TGT", "chandler-bing"))

        await(repository.findByArn(Arn("LARN7404004"))) shouldBe Some(
          agentReferenceRecord("SCX39TGT", "LARN7404004").copy(normalisedAgentNames = Seq("stan-lee", "chandler-bing"))
        )
      }
    }

    "updateAgentName" should {
      "successfully update agent name" in {
        await(repository.collection.insertOne(agentReferenceRecord("SCX39TGT", "LARN7404004")).toFuture())
        await(repository.updateAgentName("SCX39TGT", "New Name")) shouldBe ()
      }

      "fail to update agent name when no matching record found" in {
        an[RuntimeException] shouldBe thrownBy {
          await(repository.updateAgentName("SCX39TGT", "New Name"))
        }
      }
    }

    "delete" should {
      "successfully delete" in {
        await(repository.collection.insertOne(agentReferenceRecord("SCX39TGT", "LARN7404004")).toFuture())
        await(repository.delete(Arn("LARN7404004"))) shouldBe ()
      }

      "fail to delete when no matching record found" in {
        an[RuntimeException] shouldBe thrownBy {
          await(repository.delete(Arn("LARN7404004")))
        }
      }
    }
  }
}

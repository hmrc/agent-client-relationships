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

package uk.gov.hmrc.agentclientrelationships.repository

import org.scalatest.BeforeAndAfterEach
import uk.gov.hmrc.agentclientrelationships.services.RelationshipCopyRecordRepositorySpec
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.Future

// TODO merge with MongoRelationshipCopyRecordRepositoryISpec - make them both extend a trait which contains most of the tests
class FakeRelationshipCopyRecordRepositorySpec extends UnitSpec with RelationshipCopyRecordRepositorySpec with BeforeAndAfterEach{

  override val repo = new FakeRelationshipCopyRecordRepository()
 // val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository(relationshipCopyRecord)

  override protected def beforeEach(): Unit = {
    repo.reset
  }
  // remove implicit
  override def liftFuture[A](v: A): Future[A] = super.liftFuture(v)

}

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

package uk.gov.hmrc.agentclientrelationships.support

import org.scalatest.{BeforeAndAfterEach, Suite}
import org.scalatestplus.play.OneServerPerSuite
import play.api.test.FakeApplication
import uk.gov.hmrc.mongo.{MongoSpecSupport, Awaiting => MongoAwaiting}

import scala.concurrent.ExecutionContext

trait MongoApp extends MongoSpecSupport with ResetMongoBeforeTest with OneServerPerSuite {
  me: Suite =>

  override implicit lazy val app: FakeApplication = FakeApplication(
    additionalConfiguration = additionalConfiguration
  )

  protected def additionalConfiguration = Map("mongodb.uri" -> mongoUri)
}

trait ResetMongoBeforeTest extends BeforeAndAfterEach {
  me: Suite with MongoSpecSupport =>

  override def beforeEach(): Unit = {
    super.beforeEach()
    dropMongoDb()
  }

  def dropMongoDb()(implicit ec: ExecutionContext = scala.concurrent.ExecutionContext.global): Unit = {
    Awaiting.await(mongo().drop())
  }
}

object Awaiting extends MongoAwaiting

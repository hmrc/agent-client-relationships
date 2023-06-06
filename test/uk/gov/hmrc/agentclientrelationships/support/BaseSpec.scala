/*
 * Copyright 2023 HM Revenue & Customs
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

import com.google.inject.AbstractModule
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.mvc.{AnyContentAsEmpty, Result}
import play.api.test.Helpers._
import play.api.test.{FakeRequest, Helpers}
import play.api.{Configuration, Environment}
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.agentmtdidentifiers.model.Service.HMRC_AS_AGENT
import uk.gov.hmrc.auth.core._
import uk.gov.hmrc.auth.core.syntax.retrieved.authSyntaxForRetrieved
import uk.gov.hmrc.http.{HeaderCarrier, SessionKeys}
import uk.gov.hmrc.mongo.TimestampSupport
import uk.gov.hmrc.mongo.test.CleanMongoCollectionSupport
import uk.gov.hmrc.play.http.HeaderCarrierConverter

import java.time.Instant
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

abstract class BaseSpec
    extends AnyWordSpec
    with Matchers
    with GuiceOneAppPerSuite
    with ScalaFutures
    with CleanMongoCollectionSupport
    with IntegrationPatience {

  implicit val ec: ExecutionContext =
    scala.concurrent.ExecutionContext.Implicits.global

  implicit val request: FakeRequest[AnyContentAsEmpty.type] = FakeRequest()
    .withHeaders("Authorization" -> "Bearer XYZ")
    .withSession(SessionKeys.sessionId -> "session-x")

  implicit val hc: HeaderCarrier =
    HeaderCarrierConverter.fromRequestAndSession(request, request.session)

  val agentEnrolment = HMRC_AS_AGENT
  val agentReferenceNumberIdentifier = "AgentReferenceNumber"
  val validArn = "TARN0000001"
  val arn = Arn(validArn)

  val agentEnrolmentIdentifiers: Seq[EnrolmentIdentifier] = Seq(
    EnrolmentIdentifier(agentReferenceNumberIdentifier, validArn))
  val mockedAuthResponse = Enrolments(Set(Enrolment(agentEnrolment, agentEnrolmentIdentifiers, "Activated"))) and Some(
    User)

  val mockedAssistantAuthResponse = Enrolments(Set(Enrolment(agentEnrolment, agentEnrolmentIdentifiers, "Activated"))) and Some(
    Assistant)

  lazy val conf: Configuration = GuiceApplicationBuilder().configuration
  lazy val env: Environment = GuiceApplicationBuilder().environment

  def moduleWithOverrides = new AbstractModule() {}

  def appBuilder =
    GuiceApplicationBuilder()
      .disable[com.kenshoo.play.metrics.PlayModule]
      .configure("auditing.enabled" -> false)
      .configure("metrics.enabled" -> true)
      .configure("metrics.jvm" -> false)
      .overrides(moduleWithOverrides)

  protected val ttl = 1000.millis
  protected val now = Instant.now()

  protected val timestampSupport = new TimestampSupport {
    override def timestamp(): Instant = now
  }

  def bodyOf(result: Result): String =
    Helpers.contentAsString(Future.successful(result))

  def status(result: Result): Int = result.header.status

  def status(result: Future[Result]): Int = Helpers.status(result)
}

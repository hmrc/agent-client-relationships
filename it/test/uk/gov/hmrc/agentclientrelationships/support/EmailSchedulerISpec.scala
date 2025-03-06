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

package uk.gov.hmrc.agentclientrelationships.support

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.concurrent.PatienceConfiguration.{Interval, Timeout}
import org.scalatest.time.{Seconds, Span}
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.http.Status.SERVICE_UNAVAILABLE
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.{Accepted, EmailInformation, Expired, Invitation}
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository
import uk.gov.hmrc.agentclientrelationships.services.EmailService
import uk.gov.hmrc.agentclientrelationships.stubs.EmailStubs
import uk.gov.hmrc.agentclientrelationships.util.DateTimeHelper
import uk.gov.hmrc.agentmtdidentifiers.model.Service.Vat
import uk.gov.hmrc.agentmtdidentifiers.model.Vrn

import java.time.{Instant, LocalDate}
import scala.concurrent.ExecutionContext

class EmailSchedulerISpec
    extends TestKit(ActorSystem("testSystem"))
    with UnitSpec
    with MongoApp
    with GuiceOneServerPerSuite
    with WireMockSupport
    with BeforeAndAfterEach
    with EmailStubs {

  protected def appBuilder: GuiceApplicationBuilder =
    new GuiceApplicationBuilder()
      .configure(
        "emailScheduler.enabled"                    -> true,
        "emailScheduler.warningEmailCronExpression" -> "*/5_*_*_?_*_*", // every 5 seconds
        "emailScheduler.expiredEmailCronExpression" -> "*/5_*_*_?_*_*", // every 5 seconds
        "microservice.services.email.port"          -> wireMockPort
      )
      .configure(mongoConfiguration)

  override implicit lazy val app: Application = appBuilder.build()

  val invitationsRepository: InvitationsRepository = app.injector.instanceOf[InvitationsRepository]
  val emailService: EmailService = app.injector.instanceOf[EmailService]
  implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]

  val timeout: Span = scaled(Span(20, Seconds))
  val interval: Span = scaled(Span(2, Seconds))

  val scheduler = new EmailScheduler(
    system,
    app.injector.instanceOf[AppConfig],
    emailService,
    invitationsRepository
  )

  val baseInvitation: Invitation = Invitation
    .createNew(
      "XARN1234567",
      Vat,
      Vrn("123456789"),
      Vrn("234567890"),
      "Macrosoft",
      "Will Gates",
      "agent@email.com",
      LocalDate.now().plusDays(1L),
      None
    )
    .copy(created = Instant.parse("2020-06-06T00:00:00.000Z"))

  override def beforeEach(): Unit = {
    super.beforeEach()
    await(invitationsRepository.collection.drop().toFuture())
  }

  "WarningEmailActor" should {

    "send warning emails for each ARN, and update the warningEmailSent flag to true" in {
      val differentAgentInvitation = baseInvitation.copy(
        arn = "TARN7654321",
        agencyName = "Will Fence",
        agencyEmail = "agent2@email.com",
        invitationId = "3",
        suppliedClientId = "3"
      )
      val invitations = Seq(
        baseInvitation,
        baseInvitation.copy(invitationId = "2", suppliedClientId = "2"),
        differentAgentInvitation
      )
      await(invitationsRepository.collection.insertMany(invitations).toFuture())
      await(invitationsRepository.findAllForWarningEmail).size shouldBe 3

      val expectedEmailInfo1 = EmailInformation(
        to = Seq("agent@email.com"),
        templateId = "agent_invitations_about_to_expire",
        parameters = Map(
          "agencyName"          -> "Will Gates",
          "numberOfInvitations" -> "2",
          "createdDate"         -> "6 June 2020",
          "expiryDate"          -> DateTimeHelper.displayDate(LocalDate.now().plusDays(1L))
        )
      )

      val expectedEmailInfo2 = EmailInformation(
        to = Seq("agent2@email.com"),
        templateId = "agent_invitation_about_to_expire_single",
        parameters = Map(
          "agencyName"          -> "Will Fence",
          "numberOfInvitations" -> "1",
          "createdDate"         -> "6 June 2020",
          "expiryDate"          -> DateTimeHelper.displayDate(LocalDate.now().plusDays(1L))
        )
      )

      givenEmailSent(expectedEmailInfo1)
      givenEmailSent(expectedEmailInfo2)

      eventually(Timeout(timeout), Interval(interval)) {
        await(invitationsRepository.findAllForWarningEmail).size shouldBe 0
      }
    }

    "do nothing" when {

      "there are no invitations with the warningEmailSent flag set to false" in {
        val invitation = baseInvitation.copy(warningEmailSent = true)
        await(invitationsRepository.collection.insertOne(invitation).toFuture())
        await(invitationsRepository.findAllForWarningEmail).size shouldBe 0

        eventually(Timeout(timeout), Interval(interval)) {
          await(invitationsRepository.findAllForWarningEmail).size shouldBe 0
        }
      }

      "an email failed to send" in {
        await(invitationsRepository.collection.insertOne(baseInvitation).toFuture())
        await(invitationsRepository.findAllForWarningEmail).size shouldBe 1

        val expectedEmailInfo = EmailInformation(
          to = Seq("agent@email.com"),
          templateId = "agent_invitation_about_to_expire_single",
          parameters = Map(
            "agencyName"          -> "Will Gates",
            "numberOfInvitations" -> "1",
            "createdDate"         -> "6 June 2020",
            "expiryDate"          -> DateTimeHelper.displayDate(LocalDate.now().plusDays(1L))
          )
        )

        givenEmailSent(expectedEmailInfo, SERVICE_UNAVAILABLE)

        eventually(Timeout(timeout), Interval(interval)) {
          await(invitationsRepository.findAllForWarningEmail).size shouldBe 1
        }
      }
    }
  }

  "ExpiredEmailActor" should {

    "send expired emails for each invitation, update the status to Expired and the expiredEmailSent flag to true" in {
      val differentAgentInvitation = baseInvitation.copy(
        arn = "TARN7654321",
        agencyName = "Will Fence",
        agencyEmail = "agent2@email.com",
        invitationId = "2",
        suppliedClientId = "2",
        expiryDate = LocalDate.now().minusDays(1L)
      )
      val invitations = Seq(
        baseInvitation.copy(expiryDate = LocalDate.now().minusDays(1L)),
        differentAgentInvitation
      )
      await(invitationsRepository.collection.insertMany(invitations).toFuture())
      await(invitationsRepository.findAllForExpiredEmail).size shouldBe 2

      val expectedEmailInfo1 = EmailInformation(
        to = Seq("agent@email.com"),
        templateId = "client_expired_authorisation_request",
        parameters = Map(
          "agencyName" -> "Will Gates",
          "clientName" -> "Macrosoft",
          "expiryDate" -> DateTimeHelper.displayDate(LocalDate.now().minusDays(1L)),
          "service"    -> "manage their Making Tax Digital for VAT."
        )
      )

      val expectedEmailInfo2 = EmailInformation(
        to = Seq("agent2@email.com"),
        templateId = "client_expired_authorisation_request",
        parameters = Map(
          "agencyName" -> "Will Fence",
          "clientName" -> "Macrosoft",
          "expiryDate" -> DateTimeHelper.displayDate(LocalDate.now().minusDays(1L)),
          "service"    -> "manage their Making Tax Digital for VAT."
        )
      )

      givenEmailSent(expectedEmailInfo1)
      givenEmailSent(expectedEmailInfo2)

      eventually(Timeout(timeout), Interval(interval)) {
        await(invitationsRepository.findAllForExpiredEmail).size shouldBe 0
        await(invitationsRepository.findOneById(baseInvitation.invitationId)).get.status shouldBe Expired
        await(invitationsRepository.findOneById(differentAgentInvitation.invitationId)).get.status shouldBe Expired
      }
    }

    "set the status to Expired even when an email fails to send" in {
      val invitation = baseInvitation.copy(expiryDate = LocalDate.now().minusDays(1L))
      await(invitationsRepository.collection.insertOne(invitation).toFuture())
      await(invitationsRepository.findAllForExpiredEmail).size shouldBe 1

      val expectedEmailInfo = EmailInformation(
        to = Seq("agent@email.com"),
        templateId = "client_expired_authorisation_request",
        parameters = Map(
          "agencyName" -> "Will Gates",
          "clientName" -> "Macrosoft",
          "expiryDate" -> DateTimeHelper.displayDate(LocalDate.now().minusDays(1L)),
          "service"    -> "manage their Making Tax Digital for VAT."
        )
      )

      givenEmailSent(expectedEmailInfo, SERVICE_UNAVAILABLE)

      eventually(Timeout(timeout), Interval(interval)) {
        await(invitationsRepository.findAllForExpiredEmail).size shouldBe 0
        await(invitationsRepository.findOneById(baseInvitation.invitationId)).get.status shouldBe Expired
      }
    }

    "do nothing" when {

      "there are no invitations with the expiredEmailSent flag set to false" in {
        val invitation = baseInvitation.copy(expiredEmailSent = true, expiryDate = LocalDate.now().minusDays(1L))
        await(invitationsRepository.collection.insertOne(invitation).toFuture())
        await(invitationsRepository.findAllForExpiredEmail).size shouldBe 0

        eventually(Timeout(timeout), Interval(interval)) {
          await(invitationsRepository.findAllForExpiredEmail).size shouldBe 0
        }
      }

      "there are no invitations with a Pending status" in {
        val invitation = baseInvitation.copy(status = Accepted, expiryDate = LocalDate.now().minusDays(1L))
        await(invitationsRepository.collection.insertOne(invitation).toFuture())
        await(invitationsRepository.findAllForExpiredEmail).size shouldBe 0

        eventually(Timeout(timeout), Interval(interval)) {
          await(invitationsRepository.findAllForExpiredEmail).size shouldBe 0
        }
      }
    }
  }
}

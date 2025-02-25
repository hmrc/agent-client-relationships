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

package uk.gov.hmrc.agentclientrelationships.controllers

import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model._
import uk.gov.hmrc.agentclientrelationships.repository.{InvitationsRepository, PartialAuthRepository}
import uk.gov.hmrc.agentclientrelationships.support.TestData
import uk.gov.hmrc.agentmtdidentifiers.model.Service.{CapitalGains, Cbc, CbcNonUk, MtdIt, MtdItSupp, PersonalIncomeRecord, Pillar2, Ppt, Trust, TrustNT, Vat}
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.domain.TaxIdentifier

import java.time.{Instant, ZoneOffset}
import scala.concurrent.ExecutionContext

class ChangeInvitationStatusByIdControllerISpec extends BaseControllerISpec with TestData {

  implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]
  implicit val appConfig: AppConfig = app.injector.instanceOf[AppConfig]

  val invitationRepo: InvitationsRepository = app.injector.instanceOf[InvitationsRepository]
  val partialAuthRepository: PartialAuthRepository = app.injector.instanceOf[PartialAuthRepository]

  def allActions: Map[String, Set[InvitationStatus]] = Map(
    "accept" -> Set(Accepted, PartialAuth),
    "reject" -> Set(Rejected),
    "cancel" -> Set(Cancelled)
  )

  def allServices: Map[Service, TaxIdentifier] = Map(
    MtdIt                -> mtdItId,
    PersonalIncomeRecord -> nino,
    Vat                  -> vrn,
    Trust                -> utr,
    TrustNT              -> urn,
    CapitalGains         -> cgtRef,
    Ppt                  -> pptRef,
    Cbc                  -> cbcId,
    CbcNonUk             -> cbcId,
    Pillar2              -> plrId,
    MtdItSupp            -> mtdItId
  )

  def requestPath(invitationId: String, action: String): String =
    s"/agent-client-relationships/authorisation-request/action-invitation/$invitationId/action/$action"
  allActions.foreach(testActionData =>
    allServices.foreach(testset =>
      s"/authorisation-request/action-invitation/:invitationId/action/:action change status to ${testActionData._1}" should {
        val action = testActionData._1
        val expectedStatus = testActionData._2
        val (service, taxIdentifier) = testset
        val clientId: ClientIdentifier[TaxIdentifier] = ClientIdentifier(taxIdentifier)
        val suppliedClientId = taxIdentifier match {
          case _: MtdItId => ClientIdentifier(nino)
          case taxId      => ClientIdentifier(taxId)
        }
        val clientName = "TestClientName"
        val agentName = "testAgentName"
        val agentEmail = "agent@email.com"
        val expiryDate = Instant.now().atZone(ZoneOffset.UTC).toLocalDateTime.plusSeconds(60).toLocalDate

        s"when no invitation record for ${service.id}" should {
          s"return 404 NOT_FOUND" in {
            val result = doAgentPutRequest(
              requestPath("FakeInvitationId", action),
              ""
            )
            result.status shouldBe NOT_FOUND
          }
        }

        s"when invitation exists with Pending status in invitationStore for ${service.id}" should {
          s"update status to " in {
            val newInvitation: Invitation = Invitation
              .createNew(
                arn.value,
                service,
                clientId,
                suppliedClientId,
                clientName,
                agentName,
                agentEmail,
                expiryDate,
                None
              )
            await(invitationRepo.collection.insertOne(newInvitation).toFuture())

            doAgentPutRequest(
              requestPath(newInvitation.invitationId, action),
              ""
            ).status shouldBe 204

            if (!newInvitation.isAltItsa)
              await(partialAuthRepository.findActive(service.id, nino, arn)) shouldBe None

            val newStatus = await(invitationRepo.findOneById(newInvitation.invitationId)).get.status
            expectedStatus.contains(newStatus) should be(true)

          }
        }

        s"when invitation exists with the status DeAuthorised in invitationStore for ${service.id}" should {
          s"update return NOT_FOUND 404 " in {
            val newInvitation = Invitation
              .createNew(
                arn.value,
                service,
                clientId,
                suppliedClientId,
                clientName,
                agentName,
                agentEmail,
                expiryDate,
                None
              )
              .copy(status = DeAuthorised)

            await(invitationRepo.collection.insertOne(newInvitation).toFuture())

            doAgentPutRequest(
              requestPath(newInvitation.invitationId, action),
              ""
            ).status shouldBe NOT_FOUND

            await(invitationRepo.findOneById(newInvitation.invitationId)).get.status == DeAuthorised
          }
        }

      }
    )
  )

  s"/authorisation-request/action-invitation/:invitationId/action/:action MtdIt PartialAuth" should {
    val service = MtdIt
    val taxIdentifier = nino
    val clientId: ClientIdentifier[TaxIdentifier] = ClientIdentifier(taxIdentifier)
    val suppliedClientId = ClientIdentifier(nino)
    val clientName = "TestClientName"
    val agentName = "testAgentName"
    val agentEmail = "agent@email.com"
    val expiryDate = Instant.now().atZone(ZoneOffset.UTC).toLocalDateTime.plusSeconds(60).toLocalDate

    s"when invitation exists with the status Pending in invitationStore for ${service.id} accept" should {
      s"update status to " in {
        givenUserIsAuthenticatedWithStride(NEW_STRIDE_ROLE, "strideId-1234456")
        givenUserIsAuthenticatedWithStride(STRIDE_ROLE, "strideId-1234456")

        val newInvitation = Invitation
          .createNew(
            arn.value,
            service,
            clientId,
            suppliedClientId,
            clientName,
            agentName,
            agentEmail,
            expiryDate,
            None
          )
        await(invitationRepo.collection.insertOne(newInvitation).toFuture())

        doAgentPutRequest(
          requestPath(newInvitation.invitationId, "accept"),
          ""
        ).status shouldBe 204

        await(partialAuthRepository.findActive(service.id, nino, arn)).get.active shouldBe true
        await(invitationRepo.findOneById(newInvitation.invitationId)).get.status == PartialAuth

      }
    }
  }

  "handle errors" should {
    "when request data are incorrect" should {
      "return BadRequest 400 status when action is not valid for service" in {
        doAgentPutRequest(
          requestPath("AnyInvitationId", "fakeAtion"),
          ""
        ).status shouldBe 400
      }
    }
  }
}

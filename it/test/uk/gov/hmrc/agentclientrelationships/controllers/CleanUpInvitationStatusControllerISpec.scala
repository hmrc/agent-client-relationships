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

import play.api.libs.json.Json
import play.api.libs.json.Json.toJson
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model._
import uk.gov.hmrc.agentclientrelationships.model.invitation.InvitationFailureResponse.ErrorBody
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository
import uk.gov.hmrc.agentclientrelationships.support.TestData
import uk.gov.hmrc.agentmtdidentifiers.model.Service.{CapitalGains, Cbc, CbcNonUk, MtdIt, MtdItSupp, PersonalIncomeRecord, Pillar2, Ppt, Trust, TrustNT, Vat}
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.domain.TaxIdentifier

import java.time.LocalDate
import scala.concurrent.ExecutionContext

class CleanUpInvitationStatusControllerISpec extends BaseControllerISpec with TestData {

  implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]
  implicit val appConfig: AppConfig = app.injector.instanceOf[AppConfig]

  val invitationRepo: InvitationsRepository = app.injector.instanceOf[InvitationsRepository]

  val baseInvitation: Invitation = Invitation
    .createNew(
      arn = arn.value,
      service = MtdIt,
      clientId = mtdItId,
      suppliedClientId = nino,
      clientName = "C Name",
      agencyName = "A Name",
      agencyEmail = "a@example.com",
      expiryDate = LocalDate.now().plusDays(21),
      clientType = None
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

  val requestPath: String =
    "/agent-client-relationships/cleanup-invitation-status"

  def requestJson(arn: String, clientId: String, service: String) = Json
    .toJson(
      CleanUpInvitationStatusRequest(
        arn = arn,
        clientId = clientId,
        service = service
      )
    )
    .toString()

  allServices.foreach(testset =>
    "/agent-client-relationships/cleanup-invitation-status" should {
      val (service, taxIdentifier) = testset
      val clientId: ClientIdentifier[TaxIdentifier] = ClientIdentifier(taxIdentifier)
      val serviceId = service match {
        case PersonalIncomeRecord => PersonalIncomeRecord.id
        case s                    => s.id
      }

      s"when no invitation record for ${service.id}" should {
        s"return 404 NOT_FOUND" in {
          val result = doAgentPutRequest(
            route = requestPath,
            body = requestJson(arn.value, clientId.value, service.id)
          )
          result.status shouldBe NOT_FOUND
        }

      }

      s"when invitation exists with the status Accepted in invitationStore for ${service.id}" should {
        s"update status to DeAuthorised" in {
          val newInvitation: Invitation = baseInvitation.copy(
            service = serviceId,
            clientId = clientId.value,
            status = Accepted
          )

          await(invitationRepo.collection.insertOne(newInvitation).toFuture())

          doAgentPutRequest(
            route = requestPath,
            body = requestJson(arn.value, clientId.value, serviceId)
          ).status shouldBe 204

          await(invitationRepo.findOneById(newInvitation.invitationId)).get.status == DeAuthorised
        }
      }

    }
  )

  "handle errors" should {
    "when request data are incorrect" should {

      "return NotImplemented 501 status and JSON Error If service is not supported" in {
        val result = doAgentPutRequest(
          route = requestPath,
          body = requestJson(arn.value, mtdItId.value, "INVALID-SERVICE-NAME")
        )
        result.status shouldBe 501

        val message = s"""Unsupported service "INVALID-SERVICE-NAME""""
        result.json shouldBe toJson(ErrorBody("UNSUPPORTED_SERVICE", message))
      }

    }
  }

}

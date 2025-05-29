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

package uk.gov.hmrc.agentclientrelationships.controllers

import play.api.libs.json.Json
import play.api.mvc.AnyContentAsEmpty
import play.api.test.FakeRequest
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.ActiveMainAgent
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.ClientDetailsStrideResponse
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.AgencyDetails
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.AgentDetailsDesResponse
import uk.gov.hmrc.agentclientrelationships.model.stride._
import uk.gov.hmrc.agentclientrelationships.model.Invitation
import uk.gov.hmrc.agentclientrelationships.model.PartialAuthRelationship
import uk.gov.hmrc.agentclientrelationships.model.Pending
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository
import uk.gov.hmrc.agentclientrelationships.repository.PartialAuthRepository
import uk.gov.hmrc.agentclientrelationships.stubs.AfiRelationshipStub
import uk.gov.hmrc.agentclientrelationships.stubs.ClientDetailsStub
import uk.gov.hmrc.agentclientrelationships.stubs.HipStub
import uk.gov.hmrc.agentmtdidentifiers.model.Service.Cbc
import uk.gov.hmrc.agentmtdidentifiers.model.Service.CbcNonUk
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.domain.Nino

import java.time.temporal.ChronoUnit
import java.time.Instant
import java.time.LocalDate

class StrideClientDetailsControllerISpec
extends BaseControllerISpec
with ClientDetailsStub
with HipStub
with AfiRelationshipStub {

  override def additionalConfig: Map[String, Any] = Map("hip.BusinessDetails.enabled" -> true)

  val partialAuthRepo: PartialAuthRepository = app.injector.instanceOf[PartialAuthRepository]
  val invitationsRepo: InvitationsRepository = app.injector.instanceOf[InvitationsRepository]

  val req: FakeRequest[AnyContentAsEmpty.type] = FakeRequest()

  val testAgentRecord: TestAgentDetailsDesResponse = TestAgentDetailsDesResponse(
    uniqueTaxReference = None,
    agencyDetails = Some(
      TestAgencyDetails(
        agencyName = Some("ABC Ltd"),
        agencyEmail = None,
        agencyTelephone = None,
        agencyAddress = None
      )
    ),
    suspensionDetails = None
  )

  val testAgentRecord2: TestAgentDetailsDesResponse = TestAgentDetailsDesResponse(
    uniqueTaxReference = None,
    agencyDetails = Some(
      TestAgencyDetails(
        agencyName = Some("DEF Ltd"),
        agencyEmail = None,
        agencyTelephone = None,
        agencyAddress = None
      )
    ),
    suspensionDetails = None
  )

  val pendingInvitation: Invitation = Invitation(
    "123",
    arn.value,
    "HMRC-MTD-VAT",
    vrn.value,
    "vrn",
    vrn.value,
    "vrn",
    "Macrosoft",
    "testAgentName",
    "agent@email.com",
    warningEmailSent = false,
    expiredEmailSent = false,
    Pending,
    None,
    Some("personal"),
    LocalDate.parse("2020-01-01"),
    Instant.now().truncatedTo(ChronoUnit.SECONDS),
    Instant.now().truncatedTo(ChronoUnit.SECONDS)
  )

  val partialAuthRelationship: PartialAuthRelationship = PartialAuthRelationship(
    Instant.now().truncatedTo(ChronoUnit.SECONDS),
    arn2.value,
    "HMRC-MTD-IT",
    nino.value,
    active = true,
    lastUpdated = Instant.now().truncatedTo(ChronoUnit.SECONDS)
  )

  def agentDetailsDesResponse(suspended: Boolean = false): AgentDetailsDesResponse = AgentDetailsDesResponse(
    agencyDetails = AgencyDetails("ABC Ltd", ""),
    suspensionDetails = Some(SuspensionDetails(suspended, Some(Set("AGSV"))))
  )

  def invitationWithAgentName(
    invitation: Invitation,
    suspended: Boolean = false
  ): InvitationWithAgentName = InvitationWithAgentName
    .fromInvitationAndAgentRecord(invitation, agentDetailsDesResponse(suspended))

  val testEndpoint = "/agent-client-relationships/stride/client-details/service/"

  def makeRequestUrl(
    service: String,
    clientIdType: String,
    clientId: String
  ): String = s"$testEndpoint$service/client/$clientIdType/$clientId"

  s"GET $testEndpoint" should {
    "return Unauthorised" when {
      "user is not signed in" in {

        requestIsNotAuthenticated()
        givenAuditConnector()

        val result = doGetRequest(
          makeRequestUrl(
            "HMRC-MTD-IT",
            "NI",
            s"$nino"
          )
        )
        result.status shouldBe 401
      }
    }

    "return BadRequest" when {
      "invalid parameters" in {
        givenAuditConnector()

        val result = doGetRequest(
          makeRequestUrl(
            "HMRC-MTD-IT",
            "VRN",
            s"$nino"
          )
        )
        result.status shouldBe 400
      }
    }

    "return Not Found" when {
      "no invitations exist for HMRC-MTD-VAT and client unknown in HOD" in {
        givenAuthorisedAsStrideUser(req, "user-123")
        givenAuditConnector()

        givenVatCustomerInfoError(vrn.value, 404)
        getActiveRelationshipsViaClient(vrn, arn)
        givenAgentRecordFound(arn, testAgentRecord)
        val result = doGetRequest(
          makeRequestUrl(
            "HMRC-MTD-VAT",
            "VRN",
            s"${vrn.value}"
          )
        )
        result.status shouldBe 404
      }
    }

    "return OK" when {
      "no invitations exist for HMRC-MTD-VAT but client is known in HOD" in {
        givenAuthorisedAsStrideUser(req, "user-123")
        givenAuditConnector()

        givenVatCustomerInfoExists(vrn.value)
        getActiveRelationshipsViaClient(vrn, arn)
        givenAgentRecordFound(arn, testAgentRecord)
        val result = doGetRequest(
          makeRequestUrl(
            "HMRC-MTD-VAT",
            "VRN",
            s"${vrn.value}"
          )
        )
        result.status shouldBe 200

        result.body shouldBe """{"clientName":"CFG Solutions","pendingInvitations":[],"activeMainAgent":{"agentName":"ABC Ltd","arn":"AARN0000002","service":"HMRC-MTD-VAT"}}"""
      }
    }

    "return OK" when {
      "no invitations exist for HMRC-MTD-VAT but client is known in HOD and no existing main agent" in {
        givenAuthorisedAsStrideUser(req, "user-123")
        givenAuditConnector()

        givenVatCustomerInfoExists(vrn.value)
        getActiveRelationshipFailsWith(vrn, 422)
        givenAgentRecordFound(arn, testAgentRecord)
        val result = doGetRequest(
          makeRequestUrl(
            "HMRC-MTD-VAT",
            "VRN",
            s"${vrn.value}"
          )
        )
        result.status shouldBe 200

        result.body shouldBe """{"clientName":"CFG Solutions","pendingInvitations":[]}"""
      }

      "pending invitations exist for HMRC-MTD-VAT and no active relationships" in {
        givenAuthorisedAsStrideUser(req, "user-123")
        givenAuditConnector()

        invitationsRepo.collection.insertOne(pendingInvitation).toFuture().futureValue

        getActiveRelationshipFailsWith(vrn, 422)
        givenAgentRecordFound(arn, testAgentRecord)
        val result = doGetRequest(
          makeRequestUrl(
            "HMRC-MTD-VAT",
            "VRN",
            s"${vrn.value}"
          )
        )
        result.status shouldBe 200

        result.body shouldBe Json
          .toJson(
            ClientDetailsStrideResponse(
              clientName = pendingInvitation.clientName,
              pendingInvitations = Seq(invitationWithAgentName(pendingInvitation)),
              activeMainAgent = None
            )
          )
          .toString()
      }

      "pending invitations exist for HMRC-MTD-VAT and there is an active relationship" in {
        givenAuthorisedAsStrideUser(req, "user-123")
        givenAuditConnector()

        invitationsRepo.collection.insertOne(pendingInvitation).toFuture().futureValue

        givenAgentRecordFound(arn, testAgentRecord)

        getActiveRelationshipsViaClient(vrn, arn2)

        givenAgentRecordFound(arn2, testAgentRecord)

        val result = doGetRequest(
          makeRequestUrl(
            "HMRC-MTD-VAT",
            "VRN",
            s"${vrn.value}"
          )
        )
        result.status shouldBe 200

        result.body shouldBe Json
          .toJson(
            ClientDetailsStrideResponse(
              clientName = pendingInvitation.clientName,
              pendingInvitations = Seq(invitationWithAgentName(pendingInvitation)),
              activeMainAgent = Some(
                ActiveMainAgent(
                  agentName = "ABC Ltd",
                  arn2.value,
                  "HMRC-MTD-VAT"
                )
              )
            )
          )
          .toString()
      }

      "pending invitations exist for HMRC-MTD-VAT from a suspended agent and there is an active relationship" in {
        givenAuthorisedAsStrideUser(req, "user-123")
        givenAuditConnector()

        givenVatCustomerInfoExists(vrn.value)

        invitationsRepo.collection.insertOne(pendingInvitation).toFuture().futureValue

        givenAgentRecordFound(
          arn,
          testAgentRecord
            .copy(suspensionDetails = Some(SuspensionDetails(suspensionStatus = true, regimes = Some(Set("AGSV")))))
        )

        getActiveRelationshipsViaClient(vrn, arn2)

        givenAgentRecordFound(arn2, testAgentRecord)

        val result = doGetRequest(
          makeRequestUrl(
            "HMRC-MTD-VAT",
            "VRN",
            s"${vrn.value}"
          )
        )
        result.status shouldBe 200

        result.body shouldBe Json
          .toJson(
            ClientDetailsStrideResponse(
              clientName = "CFG Solutions",
              pendingInvitations = Seq(),
              activeMainAgent = Some(
                ActiveMainAgent(
                  agentName = "ABC Ltd",
                  arn2.value,
                  "HMRC-MTD-VAT"
                )
              )
            )
          )
          .toString()
      }

      "pending invitations exist for HMRC-MTD-IT and there is an active partial-auth relationship" in {
        givenAuthorisedAsStrideUser(req, "user-123")
        givenAuditConnector()

        val altItsaPendingInvitation = pendingInvitation
          .copy(
            service = "HMRC-MTD-IT",
            suppliedClientId = nino.value,
            clientId = nino.value
          )

        invitationsRepo.collection.insertOne(altItsaPendingInvitation).toFuture().futureValue

        partialAuthRepo.collection.insertOne(partialAuthRelationship).toFuture().futureValue

        givenAgentRecordFound(arn, testAgentRecord)

        givenAgentRecordFound(arn2, testAgentRecord)

        val result = doGetRequest(
          makeRequestUrl(
            "HMRC-MTD-IT",
            "NI",
            s"${nino.value}"
          )
        )
        result.status shouldBe 200

        result.body shouldBe Json
          .toJson(
            ClientDetailsStrideResponse(
              clientName = altItsaPendingInvitation.clientName,
              pendingInvitations = Seq(invitationWithAgentName(altItsaPendingInvitation)),
              activeMainAgent = Some(
                ActiveMainAgent(
                  agentName = "ABC Ltd",
                  arn2.value,
                  "HMRC-MTD-IT"
                )
              )
            )
          )
          .toString()
      }

      "pending invitations exist for HMRC-MTD-IT and there is a MTD relationship" in {
        givenAuthorisedAsStrideUser(req, "user-123")
        givenAuditConnector()

        val itsaPendingInvitation = pendingInvitation
          .copy(
            service = "HMRC-MTD-IT",
            suppliedClientId = nino.value,
            clientId = mtdItId.value
          )

        invitationsRepo.collection.insertOne(itsaPendingInvitation).toFuture().futureValue

        givenAgentRecordFound(arn, testAgentRecord)

        givenMtdItIdIsKnownFor(nino, mtdItId)
        getActiveRelationshipsViaClient(mtdItId, arn2)

        givenAgentRecordFound(arn2, testAgentRecord)

        val result = doGetRequest(
          makeRequestUrl(
            "HMRC-MTD-IT",
            "NI",
            s"${nino.value}"
          )
        )
        result.status shouldBe 200

        result.body shouldBe Json
          .toJson(
            ClientDetailsStrideResponse(
              clientName = itsaPendingInvitation.clientName,
              pendingInvitations = Seq(invitationWithAgentName(itsaPendingInvitation)),
              activeMainAgent = Some(
                ActiveMainAgent(
                  agentName = "ABC Ltd",
                  arn2.value,
                  "HMRC-MTD-IT"
                )
              )
            )
          )
          .toString()
      }

      "pending invitations exist for PERSONAL-INCOME-RECORD and there is an IRV relationship" in {
        givenAuthorisedAsStrideUser(req, "user-123")
        givenAuditConnector()

        val irvPendingInvitation = pendingInvitation
          .copy(
            service = "PERSONAL-INCOME-RECORD",
            suppliedClientId = nino.value,
            clientId = nino.value
          )

        invitationsRepo.collection.insertOne(irvPendingInvitation).toFuture().futureValue

        givenAgentRecordFound(arn, testAgentRecord)

        givenAfiRelationshipForClientIsActive(
          arn2,
          "PERSONAL-INCOME-RECORD",
          nino.value,
          fromCesa = true
        )

        givenAgentRecordFound(arn2, testAgentRecord)

        val result = doGetRequest(
          makeRequestUrl(
            "PERSONAL-INCOME-RECORD",
            "NINO",
            s"${nino.value}"
          )
        )
        result.status shouldBe 200

        result.body shouldBe Json
          .toJson(
            ClientDetailsStrideResponse(
              clientName = irvPendingInvitation.clientName,
              pendingInvitations = Seq(invitationWithAgentName(irvPendingInvitation)),
              activeMainAgent = Some(
                ActiveMainAgent(
                  agentName = "ABC Ltd",
                  arn2.value,
                  "PERSONAL-INCOME-RECORD"
                )
              )
            )
          )
          .toString()
      }

      "pending invitations exist for HMRC-CBC-ORG and there is an active CBC relationship" in {
        givenAuthorisedAsStrideUser(req, "user-123")
        givenAuditConnector()

        val cbcPendingInvitation = pendingInvitation
          .copy(
            service = "HMRC-CBC-ORG",
            suppliedClientId = cbcId.value,
            clientId = cbcId.value
          )

        invitationsRepo.collection.insertOne(cbcPendingInvitation).toFuture().futureValue

        givenAgentRecordFound(arn, testAgentRecord)

        givenKnownFactsQuery(
          Cbc,
          cbcId,
          Some(Seq(Identifier("cbcId", cbcId.value)))
        )

        getActiveRelationshipsViaClient(cbcId, arn2)

        givenAgentRecordFound(arn2, testAgentRecord)

        val result = doGetRequest(
          makeRequestUrl(
            "HMRC-CBC-ORG",
            "cbcId",
            s"${cbcId.value}"
          )
        )
        result.status shouldBe 200

        result.body shouldBe Json
          .toJson(
            ClientDetailsStrideResponse(
              clientName = cbcPendingInvitation.clientName,
              pendingInvitations = Seq(invitationWithAgentName(cbcPendingInvitation)),
              activeMainAgent = Some(
                ActiveMainAgent(
                  agentName = "ABC Ltd",
                  arn2.value,
                  "HMRC-CBC-ORG"
                )
              )
            )
          )
          .toString()
      }

      "pending invitations exist for HMRC-CBC-NONUK-ORG and there is an active CBC relationship" in {
        givenAuthorisedAsStrideUser(req, "user-123")
        givenAuditConnector()

        val cbcPendingInvitation = pendingInvitation
          .copy(
            service = "HMRC-CBC-NONUK-ORG",
            suppliedClientId = cbcId.value,
            clientId = cbcId.value
          )

        invitationsRepo.collection.insertOne(cbcPendingInvitation).toFuture().futureValue

        givenAgentRecordFound(arn, testAgentRecord)

        givenCbcUkDoesNotExistInES(cbcId)
        givenKnownFactsQuery(
          CbcNonUk,
          cbcId,
          Some(Seq(Identifier("cbcId", cbcId.value)))
        )

        getActiveRelationshipsViaClient(cbcId, arn2)

        givenAgentRecordFound(arn2, testAgentRecord)

        val result = doGetRequest(
          makeRequestUrl(
            "HMRC-CBC-ORG",
            "cbcId",
            s"${cbcId.value}"
          )
        )
        result.status shouldBe 200

        result.body shouldBe Json
          .toJson(
            ClientDetailsStrideResponse(
              clientName = cbcPendingInvitation.clientName,
              pendingInvitations = Seq(invitationWithAgentName(cbcPendingInvitation)),
              activeMainAgent = Some(
                ActiveMainAgent(
                  agentName = "ABC Ltd",
                  arn2.value,
                  "HMRC-CBC-NONUK-ORG"
                )
              )
            )
          )
          .toString()
      }
    }
  }

  s"POST /stride/active-relationships" should {

    val requestPath: String = s"/agent-client-relationships/stride/active-relationships"

    "find relationships for all clientTypes and send back Json" in {

      val clientsRelationshipsRequest: ClientsRelationshipsRequest = ClientsRelationshipsRequest(
        Seq(
          ClientRelationshipRequest(NinoType.id, nino.value),
          ClientRelationshipRequest(VrnType.id, vrn.value),
          ClientRelationshipRequest(UrnType.id, urn.value),
          ClientRelationshipRequest(UtrType.id, utr.value),
          ClientRelationshipRequest(CgtRefType.id, cgtRef.value),
          ClientRelationshipRequest(PptRefType.id, pptRef.value),
          ClientRelationshipRequest(CbcIdType.id, cbcId.value),
          ClientRelationshipRequest(PlrIdType.id, plrId.value)
        )
      )

      // set all required stub calls
      givenAuthorisedAsStrideUser(req, "someStrideId")

      clientsRelationshipsRequest.clientRelationshipRequest
        .foreach { cr =>
          val taxIdentifier = ClientIdType.forId(cr.clientIdType).createUnderlying(cr.clientId)

          cr.clientIdType match {
            case NinoType.id =>
              givenMtdItIdIsKnownFor(Nino(cr.clientId), mtdItId)
              getItsaMainAndSupportingActiveRelationshipsViaClient(
                mtdItId,
                arn,
                arn2
              )
              givenAfiRelationshipForClientIsActive(
                arn,
                "PERSONAL-INCOME-RECORD",
                nino.value,
                true
              )
              givenItsaCitizenDetailsExists(nino.value)
              givenItsaDesignatoryDetailsExists(nino.value)
              givenAgentRecordFound(arn, testAgentRecord)
              givenAgentRecordFound(arn2, testAgentRecord2)
              partialAuthRepo.collection.insertOne(partialAuthRelationship).toFuture().futureValue
            case VrnType.id =>
              getVrnIsKnownInETMPFor2(vrn)
              getAllActiveRelationshipsViaClient(taxIdentifier, arn)
              givenAgentRecordFound(arn, testAgentRecord)
            case UtrType.id =>
              getAllActiveRelationshipsViaClient(taxIdentifier, arn)
              givenTrustDetailsExist(taxIdentifier.value, UtrType.id.toUpperCase)
              givenAgentRecordFound(arn, testAgentRecord)
            case UrnType.id =>
              getAllActiveRelationshipsViaClient(taxIdentifier, arn)
              givenTrustDetailsExist(taxIdentifier.value, UrnType.id.toUpperCase)
              givenAgentRecordFound(arn, testAgentRecord)
            case CgtRefType.id =>
              getAllActiveRelationshipsViaClient(taxIdentifier, arn)
              givenCgtDetailsExist(taxIdentifier.value)
              givenAgentRecordFound(arn, testAgentRecord)
            case PptRefType.id =>
              getAllActiveRelationshipsViaClient(taxIdentifier, arn)
              givenPptDetailsExist(taxIdentifier.value)
              givenAgentRecordFound(arn, testAgentRecord)
            case CbcIdType.id =>
              getAllActiveRelationshipsViaClient(taxIdentifier, arn)
              givenKnownFactsQuery(
                Service.CbcNonUk,
                taxIdentifier,
                Some(Seq(Identifier("cbcId", taxIdentifier.value)))
              )
              givenCbcDetailsExist(true)
              givenKnownFactsQuery(
                Service.Cbc,
                taxIdentifier,
                Some(Seq(Identifier("cbcId", taxIdentifier.value), Identifier("UTR", utr.value)))
              )
              givenAgentRecordFound(arn, testAgentRecord)
            case PlrIdType.id =>
              getAllActiveRelationshipsViaClient(taxIdentifier, arn)
              givenPillar2DetailsExist(taxIdentifier.value)
              givenAgentRecordFound(arn, testAgentRecord)
            case _ =>
          }

        }

      // Test
      val result = doAgentPostRequest(requestPath, Json.toJson(clientsRelationshipsRequest).toString())
      result.status shouldBe 200
      val response: ActiveClientsRelationshipResponse = result.json.as[ActiveClientsRelationshipResponse]

      response.activeClientRelationships.size shouldBe 11
      // validate result
      clientsRelationshipsRequest.clientRelationshipRequest
        .foreach { cr =>
          cr.clientIdType match {
            case NinoType.id =>
              val mainRelationships = response.activeClientRelationships
                .filter(x => x.clientId == cr.clientId && x.service == Service.MtdIt.id && x.arn == arn.value)

              val partialAuthRelationships = response.activeClientRelationships
                .filter(x => x.clientId == cr.clientId && x.service == Service.MtdIt.id && x.arn == arn2.value)

              val suppRelationships = response.activeClientRelationships
                .filter(x => x.clientId == cr.clientId && x.service == Service.MtdItSupp.id)

              val irvRelationships = response.activeClientRelationships
                .filter(x => x.clientId == cr.clientId && x.service == Service.PersonalIncomeRecord.id)

              mainRelationships.size shouldBe 1
              partialAuthRelationships.size shouldBe 1
              suppRelationships.size shouldBe 1
              irvRelationships.size shouldBe 1

            case VrnType.id =>
              val relationships = response.activeClientRelationships
                .filter(x => x.clientId == cr.clientId && x.service == Service.Vat.id)
              relationships.size shouldBe 1

            case UtrType.id =>
              val relationships = response.activeClientRelationships
                .filter(x => x.clientId == cr.clientId && x.service == Service.Trust.id)
              relationships.size shouldBe 1

            case UrnType.id =>
              val relationships = response.activeClientRelationships
                .filter(x => x.clientId == cr.clientId && x.service == Service.TrustNT.id)
              relationships.size shouldBe 1

            case CgtRefType.id =>
              val relationships = response.activeClientRelationships
                .filter(x => x.clientId == cr.clientId && x.service == Service.CapitalGains.id)
              relationships.size shouldBe 1

            case PptRefType.id =>
              val relationships = response.activeClientRelationships
                .filter(x => x.clientId == cr.clientId && x.service == Service.Ppt.id)
              relationships.size shouldBe 1

            case CbcIdType.id =>
              val relationships = response.activeClientRelationships
                .filter(x => x.clientId == cr.clientId && x.service == Service.Cbc.id)
              relationships.size shouldBe 1

            case PlrIdType.id =>
              val relationships = response.activeClientRelationships
                .filter(x => x.clientId == cr.clientId && x.service == Service.Pillar2.id)
              relationships.size shouldBe 1
            case _ =>

          }
        }

    }

    "find relationships for partialAuth only when MtdItId do not exists" in {
      val cr = ClientRelationshipRequest(NinoType.id, nino.value)

      givenAuthorisedAsStrideUser(req, "someStrideId")
      givenMtdItIdIsUnKnownFor(nino)
      getAllActiveRelationshipFailsWith(mtdItId, status = 404)
      givenAfiRelationshipForClientNotFound(nino.value)
      givenItsaCitizenDetailsExists(nino.value)
      givenItsaDesignatoryDetailsExists(nino.value)
      givenAgentRecordFound(arn2, testAgentRecord2)
      partialAuthRepo.collection.insertOne(partialAuthRelationship).toFuture().futureValue

      val expectedPartialAuthRelationship = ActiveClientRelationship(
        clientId = cr.clientId,
        clientName = "Matthew Kovacic",
        arn = arn2.value,
        agentName = "DEF Ltd",
        service = Service.MtdIt.id
      )

      // Test
      val result = doAgentPostRequest(requestPath, Json.toJson(ClientsRelationshipsRequest(Seq(cr))).toString())
      result.status shouldBe 200
      val response: ActiveClientsRelationshipResponse = result.json.as[ActiveClientsRelationshipResponse]

      response shouldBe ActiveClientsRelationshipResponse(activeClientRelationships = Seq(expectedPartialAuthRelationship))

    }

    "find relationships for partialAuth only when mtdItId exists" in {
      val cr = ClientRelationshipRequest(NinoType.id, nino.value)

      givenAuthorisedAsStrideUser(req, "someStrideId")
      givenMtdItIdIsKnownFor(nino, mtdItId)
      getAllActiveRelationshipFailsWith(mtdItId, status = 404)
      givenAfiRelationshipForClientNotFound(nino.value)
      givenItsaCitizenDetailsExists(nino.value)
      givenItsaDesignatoryDetailsExists(nino.value)
      givenAgentRecordFound(arn2, testAgentRecord2)
      partialAuthRepo.collection.insertOne(partialAuthRelationship).toFuture().futureValue

      val expectedPartialAuthRelationship = ActiveClientRelationship(
        clientId = cr.clientId,
        clientName = "Matthew Kovacic",
        arn = arn2.value,
        agentName = "DEF Ltd",
        service = Service.MtdIt.id
      )

      // Test
      val result = doAgentPostRequest(requestPath, Json.toJson(ClientsRelationshipsRequest(Seq(cr))).toString())
      result.status shouldBe 200
      val response: ActiveClientsRelationshipResponse = result.json.as[ActiveClientsRelationshipResponse]

      response shouldBe ActiveClientsRelationshipResponse(activeClientRelationships = Seq(expectedPartialAuthRelationship))

    }

    "return only records when relationship exists and do not return when relationship not found" in {
      val clientsRelationshipsRequest: ClientsRelationshipsRequest = ClientsRelationshipsRequest(
        Seq(ClientRelationshipRequest(NinoType.id, nino.value), ClientRelationshipRequest(VrnType.id, vrn.value))
      )

      // set all required stub calls
      givenAuthorisedAsStrideUser(req, "someStrideId")

      clientsRelationshipsRequest.clientRelationshipRequest
        .foreach { cr =>
          val taxIdentifier = ClientIdType.forId(cr.clientIdType).createUnderlying(cr.clientId)

          cr.clientIdType match {
            case NinoType.id =>
              givenMtdItIdIsKnownFor(Nino(cr.clientId), mtdItId)
              getAllActiveRelationshipFailsWith(mtdItId, status = 404)
              givenAfiRelationshipForClientNotFound(cr.clientId)
              givenItsaCitizenDetailsExists(nino.value)
              givenItsaDesignatoryDetailsExists(nino.value)
            case VrnType.id =>
              getVrnIsKnownInETMPFor2(vrn)
              getAllActiveRelationshipsViaClient(taxIdentifier, arn)
              givenAgentRecordFound(arn, testAgentRecord)
            case _ =>
          }

        }

      // Test
      val result = doAgentPostRequest(requestPath, Json.toJson(clientsRelationshipsRequest).toString())
      result.status shouldBe 200
      val response: ActiveClientsRelationshipResponse = result.json.as[ActiveClientsRelationshipResponse]

      // validate result
      clientsRelationshipsRequest.clientRelationshipRequest
        .foreach { cr =>
          cr.clientIdType match {
            case NinoType.id =>
              val mainRelationships = response.activeClientRelationships
                .filter(x => x.clientId == cr.clientId && x.service == Service.MtdIt.id)
              val suppRelationships = response.activeClientRelationships
                .filter(x => x.clientId == cr.clientId && x.service == Service.MtdItSupp.id)

              mainRelationships.size shouldBe 0
              suppRelationships.size shouldBe 0

            case VrnType.id =>
              val relationships = response.activeClientRelationships
                .filter(x => x.clientId == cr.clientId && x.service == Service.Vat.id)
              relationships.size shouldBe 1
            case _ =>

          }
        }

    }

    "return only records when relationship exists and do not return when relationship 422 code 009" in {
      val clientsRelationshipsRequest: ClientsRelationshipsRequest = ClientsRelationshipsRequest(
        Seq(ClientRelationshipRequest(NinoType.id, nino.value), ClientRelationshipRequest(VrnType.id, vrn.value))
      )

      // set all required stub calls
      givenAuthorisedAsStrideUser(req, "someStrideId")

      clientsRelationshipsRequest.clientRelationshipRequest
        .foreach { cr =>
          val taxIdentifier = ClientIdType.forId(cr.clientIdType).createUnderlying(cr.clientId)

          cr.clientIdType match {
            case NinoType.id =>
              givenMtdItIdIsKnownFor(Nino(cr.clientId), mtdItId)
              getAllActiveRelationshipFailsWithNotFound(mtdItId, status = 422)
              givenAfiRelationshipForClientNotFound(cr.clientId)
              givenItsaCitizenDetailsExists(nino.value)
              givenItsaDesignatoryDetailsExists(nino.value)
            case VrnType.id =>
              getVrnIsKnownInETMPFor2(vrn)
              getAllActiveRelationshipsViaClient(taxIdentifier, arn)
              givenAgentRecordFound(arn, testAgentRecord)
            case _ =>

          }

        }

      // Test
      val result = doAgentPostRequest(requestPath, Json.toJson(clientsRelationshipsRequest).toString())
      result.status shouldBe 200
      val response: ActiveClientsRelationshipResponse = result.json.as[ActiveClientsRelationshipResponse]

      // validate result
      clientsRelationshipsRequest.clientRelationshipRequest
        .foreach { cr =>
          cr.clientIdType match {
            case NinoType.id =>
              val mainRelationships = response.activeClientRelationships
                .filter(x => x.clientId == cr.clientId && x.service == Service.MtdIt.id)
              val suppRelationships = response.activeClientRelationships
                .filter(x => x.clientId == cr.clientId && x.service == Service.MtdItSupp.id)

              mainRelationships.size shouldBe 0
              suppRelationships.size shouldBe 0

            case VrnType.id =>
              val relationships = response.activeClientRelationships
                .filter(x => x.clientId == cr.clientId && x.service == Service.Vat.id)
              relationships.size shouldBe 1
            case _ =>

          }
        }

    }

    "return empty array and 200 when no relationships were found" in {
      val clientsRelationshipsRequest: ClientsRelationshipsRequest = ClientsRelationshipsRequest(
        Seq(ClientRelationshipRequest(NinoType.id, nino.value), ClientRelationshipRequest(VrnType.id, vrn.value))
      )

      // set all required stub calls
      givenAuthorisedAsStrideUser(req, "someStrideId")

      clientsRelationshipsRequest.clientRelationshipRequest
        .foreach { cr =>
          cr.clientIdType match {
            case NinoType.id =>
              givenMtdItIdIsKnownFor(Nino(cr.clientId), mtdItId)
              getAllActiveRelationshipFailsWith(mtdItId, status = 404)
              givenAfiRelationshipForClientNotFound(cr.clientId)
              givenItsaCitizenDetailsExists(nino.value)
              givenItsaDesignatoryDetailsExists(nino.value)
            case VrnType.id =>
              getVrnIsKnownInETMPFor2(vrn)
              getAllActiveRelationshipFailsWith(vrn, status = 404)
            case _ =>
          }

        }

      // Test
      val result = doAgentPostRequest(requestPath, Json.toJson(clientsRelationshipsRequest).toString())
      result.status shouldBe 200
      val response: ActiveClientsRelationshipResponse = result.json.as[ActiveClientsRelationshipResponse]

      // validate result
      clientsRelationshipsRequest.clientRelationshipRequest
        .foreach { cr =>
          cr.clientIdType match {
            case NinoType.id =>
              val mainRelationships = response.activeClientRelationships
                .filter(x => x.clientId == cr.clientId && x.service == Service.MtdIt.id)
              val suppRelationships = response.activeClientRelationships
                .filter(x => x.clientId == cr.clientId && x.service == Service.MtdItSupp.id)

              mainRelationships.size shouldBe 0
              suppRelationships.size shouldBe 0

            case VrnType.id =>
              val relationships = response.activeClientRelationships
                .filter(x => x.clientId == cr.clientId && x.service == Service.Vat.id)
              relationships.size shouldBe 0
            case _ =>

          }
        }

      response.activeClientRelationships.size shouldBe 0

    }

    "return empty array and 200 when agent is suspended" in {
      val clientsRelationshipsRequest: ClientsRelationshipsRequest = ClientsRelationshipsRequest(
        Seq(ClientRelationshipRequest(NinoType.id, nino.value), ClientRelationshipRequest(VrnType.id, vrn.value))
      )

      // set all required stub calls
      givenAuthorisedAsStrideUser(req, "someStrideId")

      clientsRelationshipsRequest.clientRelationshipRequest
        .foreach { cr =>
          cr.clientIdType match {
            case NinoType.id =>
              givenMtdItIdIsKnownFor(Nino(cr.clientId), mtdItId)
              getAllActiveRelationshipFailsWithSuspended(mtdItId)
              givenAfiRelationshipForClientNotFound(cr.clientId)
              givenItsaCitizenDetailsExists(nino.value)
              givenItsaDesignatoryDetailsExists(nino.value)
            case VrnType.id =>
              getVrnIsKnownInETMPFor2(vrn)
              getAllActiveRelationshipFailsWithSuspended(vrn)
            case _ =>
          }

        }

      // Test
      val result = doAgentPostRequest(requestPath, Json.toJson(clientsRelationshipsRequest).toString())
      result.status shouldBe 200
      val response: ActiveClientsRelationshipResponse = result.json.as[ActiveClientsRelationshipResponse]

      // validate result
      clientsRelationshipsRequest.clientRelationshipRequest
        .foreach { cr =>
          cr.clientIdType match {
            case NinoType.id =>
              val mainRelationships = response.activeClientRelationships
                .filter(x => x.clientId == cr.clientId && x.service == Service.MtdIt.id)
              val suppRelationships = response.activeClientRelationships
                .filter(x => x.clientId == cr.clientId && x.service == Service.MtdItSupp.id)
              mainRelationships.size shouldBe 0
              suppRelationships.size shouldBe 0

            case VrnType.id =>
              val relationships = response.activeClientRelationships
                .filter(x => x.clientId == cr.clientId && x.service == Service.Vat.id)
              relationships.size shouldBe 0
            case _ =>

          }
        }
      response.activeClientRelationships.size shouldBe 0

    }

    "return 400 when one of the clientId is incorrect" in {
      val clientsRelationshipsRequest: ClientsRelationshipsRequest = ClientsRelationshipsRequest(
        Seq(ClientRelationshipRequest(NinoType.id, "FAKENINO"), ClientRelationshipRequest(VrnType.id, vrn.value))
      )
      givenAuthorisedAsStrideUser(req, "someStrideId")
      clientsRelationshipsRequest.clientRelationshipRequest
        .foreach { cr =>
          cr.clientIdType match {
            case NinoType.id =>
            case VrnType.id =>
              getVrnIsKnownInETMPFor2(vrn)
              getAllActiveRelationshipFailsWithSuspended(vrn)
            case _ =>
          }

        }

      val result = doAgentPostRequest(requestPath, Json.toJson(clientsRelationshipsRequest).toString())
      result.status shouldBe 400

    }

    "return 500 when client details fail" in {
      val clientsRelationshipsRequest: ClientsRelationshipsRequest = ClientsRelationshipsRequest(
        Seq(ClientRelationshipRequest(NinoType.id, nino.value), ClientRelationshipRequest(VrnType.id, vrn.value))
      )

      // set all required stub calls
      givenAuthorisedAsStrideUser(req, "someStrideId")

      clientsRelationshipsRequest.clientRelationshipRequest
        .foreach { cr =>
          cr.clientIdType match {
            case NinoType.id =>
              givenMtdItIdIsKnownFor(Nino(cr.clientId), mtdItId)
              getItsaMainAndSupportingActiveRelationshipsViaClient(
                mtdItId,
                arn,
                arn2
              )
              givenAfiRelationshipForClientNotFound(cr.clientId)
              givenItsaCitizenDetailsError(nino.value, 403)
              givenItsaDesignatoryDetailsExists(nino.value)
              givenAgentRecordFound(arn, testAgentRecord)
              givenAgentRecordFound(arn2, testAgentRecord2)
            case VrnType.id =>
              getVrnIsKnownInETMPFor2(vrn)
              getAllActiveRelationshipsViaClient(vrn, arn)
              givenAgentRecordFound(arn, testAgentRecord)
            case _ =>
          }

        }

      // Test
      val result = doAgentPostRequest(requestPath, Json.toJson(clientsRelationshipsRequest).toString())
      result.status shouldBe 500

    }

    "return 500 when get agent data  fail" in {
      val clientsRelationshipsRequest: ClientsRelationshipsRequest = ClientsRelationshipsRequest(
        Seq(ClientRelationshipRequest(NinoType.id, nino.value), ClientRelationshipRequest(VrnType.id, vrn.value))
      )

      // set all required stub calls
      givenAuthorisedAsStrideUser(req, "someStrideId")

      clientsRelationshipsRequest.clientRelationshipRequest
        .foreach { cr =>
          cr.clientIdType match {
            case NinoType.id =>
              givenMtdItIdIsKnownFor(Nino(cr.clientId), mtdItId)
              getItsaMainAndSupportingActiveRelationshipsViaClient(
                mtdItId,
                arn,
                arn2
              )
              givenAfiRelationshipForClientNotFound(cr.clientId)
              givenItsaCitizenDetailsExists(nino.value)
              givenItsaDesignatoryDetailsExists(nino.value)
              givenAgentRecordFound(arn, testAgentRecord)
              givenAgentDetailsErrorResponse(arn2, 404)
            case VrnType.id =>
              getVrnIsKnownInETMPFor2(vrn)
              getAllActiveRelationshipsViaClient(vrn, arn)
              givenAgentRecordFound(arn, testAgentRecord)
            case _ =>
          }

        }

      // Test
      val result = doAgentPostRequest(requestPath, Json.toJson(clientsRelationshipsRequest).toString())
      result.status shouldBe 500
      result.body.contains("/agent-assurance/agent-record-with-checks/arn/AARN0000004' returned 404") shouldBe true
    }
    "return 404 when get client data fail" in {
      val clientsRelationshipsRequest: ClientsRelationshipsRequest = ClientsRelationshipsRequest(
        Seq(ClientRelationshipRequest(VrnType.id, vrn.value))
      )

      givenAuthorisedAsStrideUser(req, "someStrideId")

      clientsRelationshipsRequest.clientRelationshipRequest
        .foreach { cr =>
          val taxIdentifier = ClientIdType.forId(cr.clientIdType).createUnderlying(cr.clientId)
          cr.clientIdType match {
            case VrnType.id =>
              givenDESRespondsWithStatusForVrn(vrn, 404)
              getAllActiveRelationshipsViaClient(taxIdentifier, arn)
              givenAgentRecordFound(arn, testAgentRecord)
            case _ =>
          }
        }

      val result = doAgentPostRequest(requestPath, Json.toJson(clientsRelationshipsRequest).toString())
      result.status shouldBe 404
    }

  }

  "GET /stride/irv-relationships/:nino" should {

    def requestPath(nino: String) = s"/agent-client-relationships/stride/irv-relationships/$nino"

    "return 200 with expected JSON body when all calls are successful" in {
      givenAuthorisedAsStrideUser(req, "user-123")
      givenAuditConnector()
      givenAfiRelationshipForClientIsActive(
        arn,
        "PERSONAL-INCOME-RECORD",
        nino.value,
        fromCesa = true
      )
      givenItsaCitizenDetailsExists(nino.value)
      givenAgentRecordFound(arn, testAgentRecord)

      val expectedJsonBody = Json.obj(
        "clientName" -> "Matthew Kovacic",
        "nino" -> "AB123456C",
        "agents" -> Json.arr(Json.obj("name" -> "ABC Ltd", "arn" -> "AARN0000002"))
      )

      val result = doGetRequest(requestPath(nino.value))
      result.status shouldBe 200
      result.json shouldBe expectedJsonBody
    }

    "return 400 when there is an error validating the NINO" in {
      givenAuthorisedAsStrideUser(req, "user-123")
      givenAuditConnector()

      val result = doGetRequest(requestPath("INVALID_NINO"))
      result.status shouldBe 400
    }

    "return 401 when the request is unauthorised" in {
      requestIsNotAuthenticated()
      givenAuditConnector()

      val result = doGetRequest(requestPath(nino.value))
      result.status shouldBe 401
    }

    "return 404 when no client details were found for the given NINO" in {
      givenAuthorisedAsStrideUser(req, "user-123")
      givenAuditConnector()
      givenAfiRelationshipForClientNotFound(nino.value)
      givenItsaCitizenDetailsError(nino.value, 404)

      val result = doGetRequest(requestPath(nino.value))
      result.status shouldBe 404
    }

    "return 500" when {

      "there is an error retrieving client details" in {
        givenAuthorisedAsStrideUser(req, "user-123")
        givenAuditConnector()
        givenAfiRelationshipForClientNotFound(nino.value)
        givenItsaCitizenDetailsError(nino.value, 500)

        val result = doGetRequest(requestPath(nino.value))
        result.status shouldBe 500
        result.body shouldBe "Unexpected error during 'getItsaCitizenDetails'"
      }

      "there is an error retrieving agent details" in {
        givenAuthorisedAsStrideUser(req, "user-123")
        givenAuditConnector()
        givenAfiRelationshipForClientIsActive(
          arn,
          "PERSONAL-INCOME-RECORD",
          nino.value,
          fromCesa = true
        )
        givenItsaCitizenDetailsExists(nino.value)
        givenAgentDetailsErrorResponse(arn, 500)

        val result = doGetRequest(requestPath(nino.value))
        result.status shouldBe 500
        result.body should include("Unexpected error retrieving agent record")
      }

      "there is an error retrieving relationship details" in {
        givenAuthorisedAsStrideUser(req, "user-123")
        givenAuditConnector()
        givenAfiRelationshipForClientNotFound(nino.value, 500)
        givenItsaCitizenDetailsExists(nino.value)

        val result = doGetRequest(requestPath(nino.value))
        result.status shouldBe 500
        result.body shouldBe "Unexpected status 500 received from AFI get active relationship for client"
      }
    }
  }

}

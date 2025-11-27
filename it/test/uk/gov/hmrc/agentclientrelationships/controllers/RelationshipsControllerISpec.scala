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

import org.mongodb.scala.model.Filters
import play.api.mvc.RequestHeader
import play.api.test.FakeRequest
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.model.DeletionCount
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.model.TerminationResponse
import uk.gov.hmrc.agentclientrelationships.repository.DeleteRecord
import uk.gov.hmrc.agentclientrelationships.repository.RelationshipCopyRecord
import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus
import uk.gov.hmrc.agentclientrelationships.services.ValidationService
import uk.gov.hmrc.agentclientrelationships.stubs.HipStub
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Identifier
import uk.gov.hmrc.agentclientrelationships.model.identifiers.MtdItId
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service
import uk.gov.hmrc.agentclientrelationships.repository.RelationshipReference.SaRef
import uk.gov.hmrc.domain.SaAgentReference
import uk.gov.hmrc.http.HeaderNames

import java.nio.charset.StandardCharsets.UTF_8
import java.time.LocalDateTime
import java.util.Base64

class RelationshipsControllerISpec
extends RelationshipsBaseControllerISpec
with HipStub {

  val relationshipCopiedSuccessfully: RelationshipCopyRecord = RelationshipCopyRecord(
    arn.value,
    EnrolmentKey(Service.MtdIt, MtdItId("ABCDEF0000000001")),
    references = Some(Set(SaRef(SaAgentReference("foo")))),
    syncToETMPStatus = Some(SyncStatus.Success),
    syncToESStatus = Some(SyncStatus.Success)
  )

  "DELETE /agent/:arn/terminate" should {

    val requestPath: String = s"/agent-client-relationships/agent/${arn.value}/terminate"
    def basicAuth(string: String): String = Base64.getEncoder.encodeToString(string.getBytes(UTF_8))
    def doRequest() = ws
      .url(s"http://localhost:$port$requestPath")
      .addHttpHeaders(HeaderNames.authorisation -> s"Basic ${basicAuth("username:password")}")
      .delete()

    "return 200 after successful termination" in {

      // insert records first to have some state initially
      // insert delete-record document
      await(
        deleteRecordRepository.create(
          DeleteRecord(
            arn.value,
            EnrolmentKey(Service.MtdIt, MtdItId("ABCDEF0000000001")),
            dateTime = LocalDateTime.now.minusMinutes(1),
            syncToETMPStatus = Some(SyncStatus.Success),
            syncToESStatus = Some(SyncStatus.Failed)
          )
        )
      )

      // insert copy-relationship document
      await(relationshipCopyRecordRepository.collection.insertOne(relationshipCopiedSuccessfully).toFuture())

      val result = await(doRequest())
      result.status shouldBe 200
      val response = result.json.as[TerminationResponse]

      response shouldBe TerminationResponse(
        Seq(
          DeletionCount(
            "agent-client-relationships",
            "delete-record",
            1
          ),
          DeletionCount(
            "agent-client-relationships",
            "relationship-copy-record",
            1
          )
        )
      )

      // verify termination has deleted all record for that agent
      await(deleteRecordRepository.collection.find(Filters.equal("arn", arn.value)).toFuture()) shouldBe empty
      await(relationshipCopyRecordRepository.collection.find(Filters.equal("arn", arn.value)).toFuture()) shouldBe empty
    }
  }

  "sanitising a CBC enrolment key" should {
    implicit val request: RequestHeader = FakeRequest()
    "work for a HMRC-CBC-ORG enrolment key with a UTR stored in the enrolment store" in {
      val validationService = app.injector.instanceOf[ValidationService]
      givenCbcUkExistsInES(cbcId, utr.value)
      await(validationService.makeSanitisedCbcEnrolmentKey(cbcId)) shouldBe
        Right(EnrolmentKey(Service.Cbc.id, Seq(Identifier("cbcId", cbcId.value), Identifier("UTR", utr.value))))
    }
    "correct the service to HMRC-CBC-NONUK-ORG if the given cbcId corresponds to non-uk in the enrolment store" in {
      val validationService = app.injector.instanceOf[ValidationService]
      givenCbcUkDoesNotExistInES(cbcId)
      givenCbcNonUkExistsInES(cbcId)
      await(validationService.makeSanitisedCbcEnrolmentKey(cbcId)) shouldBe
        Right(EnrolmentKey(Service.CbcNonUk.id, Seq(Identifier("cbcId", cbcId.value))))
    }
    "fail if there is no match in enrolment store for either HMRC-CBC-ORG or HMRC-CBC-NONUK-ORG" in {
      val validationService = app.injector.instanceOf[ValidationService]
      givenCbcUkDoesNotExistInES(cbcId)
      givenCbcNonUkDoesNotExistInES(cbcId)
      await(validationService.makeSanitisedCbcEnrolmentKey(cbcId)) should matchPattern { case Left(_) => }
    }
  }

}

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

package uk.gov.hmrc.agentclientrelationships.model

import org.scalatest.Inside
import play.api.libs.json.Json
import play.api.mvc.RequestHeader
import uk.gov.hmrc.agentclientrelationships.repository.DeleteRecord
import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec
import uk.gov.hmrc.agentmtdidentifiers.model.Service
import uk.gov.hmrc.http.Authorization
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.SessionId

import java.time.Instant
import java.time.ZoneOffset

class DeleteRecordSpec extends UnitSpec with Inside {

  "DeleteRecord" should {
    "serialize and deserialize from and to json" in {
      val deleteRecord = DeleteRecord(
        "TARN0000001",
        Some(EnrolmentKey(s"${Service.Vat.id}~VRN~101747696")),
        Some("101747696"),
        Some("VRN"),
        Instant.now().atZone(ZoneOffset.UTC).toLocalDateTime,
        Some(SyncStatus.Failed),
        Some(SyncStatus.Failed),
        headerCarrier = Some(
          HeaderCarrier(
            authorization = Some(Authorization("foo1")),
            sessionId = Some(SessionId("foo2")),
            gaToken = Some("foo4")
          )
        )
      )

      val json = Json.toJson(deleteRecord)

      val result = json.as[DeleteRecord]

      result.enrolmentKey shouldBe deleteRecord.enrolmentKey
      result.clientIdentifier shouldBe deleteRecord.clientIdentifier
      result.clientIdentifierType shouldBe deleteRecord.clientIdentifierType
      result.syncToESStatus shouldBe deleteRecord.syncToESStatus
      result.syncToETMPStatus shouldBe deleteRecord.syncToETMPStatus
      result.syncToETMPStatus shouldBe deleteRecord.syncToETMPStatus
      result.lastRecoveryAttempt shouldBe deleteRecord.lastRecoveryAttempt
      result.numberOfAttempts shouldBe deleteRecord.numberOfAttempts

      inside(result.headerCarrier) { case Some(hc: HeaderCarrier) =>
        hc.authorization.map(_.value) shouldBe Some("foo1")
        hc.sessionId.map(_.value) shouldBe Some("foo2")
        hc.gaToken.get shouldBe "foo4"
      }
    }
  }
}

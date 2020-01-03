/*
 * Copyright 2020 HM Revenue & Customs
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

import org.joda.time.{DateTime, DateTimeZone}
import org.scalatest.Inside
import play.api.libs.json.Json
import uk.gov.hmrc.agentclientrelationships.repository.{DeleteRecord, SyncStatus}
import uk.gov.hmrc.http.{HeaderCarrier, Token}
import uk.gov.hmrc.http.logging.{Authorization, SessionId}
import uk.gov.hmrc.play.test.UnitSpec

class DeleteRecordSpec extends UnitSpec with Inside {

  "DeleteRecord" should {
    "serialize and deserialize from and to json" in {
      val deleteRecord = DeleteRecord(
        "TARN0000001",
        "101747696",
        "VRN",
        DateTime.now(DateTimeZone.UTC),
        Some(SyncStatus.Failed),
        Some(SyncStatus.Failed),
        headerCarrier = Some(
          HeaderCarrier(
            authorization = Some(Authorization("foo1")),
            sessionId = Some(SessionId("foo2")),
            token = Some(Token("foo3")),
            gaToken = Some("foo4")
          ))
      )

      val json = Json.toJson(deleteRecord)

      val result = json.as[DeleteRecord]

      result.clientIdentifier shouldBe deleteRecord.clientIdentifier
      result.clientIdentifierType shouldBe deleteRecord.clientIdentifierType
      result.syncToESStatus shouldBe deleteRecord.syncToESStatus
      result.syncToETMPStatus shouldBe deleteRecord.syncToETMPStatus
      result.syncToETMPStatus shouldBe deleteRecord.syncToETMPStatus
      result.lastRecoveryAttempt shouldBe deleteRecord.lastRecoveryAttempt
      result.numberOfAttempts shouldBe deleteRecord.numberOfAttempts

      inside(result.headerCarrier) {
        case Some(hc: HeaderCarrier) =>
          hc.authorization.map(_.value) shouldBe Some("foo1")
          hc.sessionId.map(_.value) shouldBe Some("foo2")
          hc.token.map(_.value) shouldBe Some("foo3")
          hc.gaToken.get shouldBe "foo4"
      }
    }
  }
}

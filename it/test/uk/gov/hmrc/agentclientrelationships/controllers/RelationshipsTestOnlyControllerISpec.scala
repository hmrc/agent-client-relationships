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

package uk.gov.hmrc.agentclientrelationships.controllers

import org.apache.pekko.Done
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.repository.RelationshipCopyRecordRepository
import uk.gov.hmrc.agentclientrelationships.repository.RelationshipCopyRecord
import uk.gov.hmrc.agentclientrelationships.model.identifiers.MtdItId
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service

class RelationshipsTestOnlyControllerISpec
extends BaseISpec {

  def repo: RelationshipCopyRecordRepository = app.injector.instanceOf[RelationshipCopyRecordRepository]

  "DELETE /test-only/db/agent/:arn/service/HMRC-MTD-IT/client/MTDITID/:identifierValue" should {

    val requestPath: String = s"/test-only/db/agent/${arn.value}/service/HMRC-MTD-IT/client/MTDITID/${mtdItId.value}"

    "return 204 for a valid arn and mtdItId" in {
      givenAuditConnector()
      await(repo.create(RelationshipCopyRecord(arn.value, EnrolmentKey(Service.MtdIt, mtdItId)))) shouldBe Done
      val result = doAgentDeleteRequest(requestPath)
      result.status shouldBe 204
    }

    "return 404 for an invalid mtdItId" in {
      givenAuditConnector()
      await(repo.create(RelationshipCopyRecord(arn.value, EnrolmentKey(Service.MtdIt, MtdItId("ABCDEF123456780"))))) shouldBe Done
      val result = doAgentDeleteRequest(requestPath)
      result.status shouldBe 404
    }

    "return 404 for an invalid arn and mtdItId" in {
      givenAuditConnector()
      val result = doAgentDeleteRequest(requestPath)
      result.status shouldBe 404
    }

  }

}

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

package uk.gov.hmrc.agentclientrelationships.mocks

import org.mockito.ArgumentMatchers.{any, eq => eqs}
import org.mockito.Mockito.when
import org.mockito.stubbing.OngoingStubbing
import play.api.mvc.Request
import uk.gov.hmrc.agentclientrelationships.audit.AuditData
import uk.gov.hmrc.agentclientrelationships.auth.CurrentUser
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.services.DeleteRelationshipsService
import uk.gov.hmrc.agentclientrelationships.support.ResettingMockitoSugar
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.auth.core.AffinityGroup
import play.api.mvc.RequestHeader

import scala.concurrent.Future

trait MockDeleteRelationshipsService {
  this: ResettingMockitoSugar =>

  val mockDeleteRelationshipsService: DeleteRelationshipsService = resettingMock[DeleteRelationshipsService]

  def mockDeleteRelationship(arn: Arn, enrolment: EnrolmentKey, affinityGroup: Option[AffinityGroup])(
    response: Future[Unit] = Future.unit
  ): OngoingStubbing[Future[Unit]] =
    when(
      mockDeleteRelationshipsService.deleteRelationship(eqs(arn), eqs(enrolment), eqs(affinityGroup))(
        any[RequestHeader],
        any[CurrentUser],
        any[AuditData]
      )
    ).thenReturn(response)

}

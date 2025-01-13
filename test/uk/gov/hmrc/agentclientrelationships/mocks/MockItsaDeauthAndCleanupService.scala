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
import uk.gov.hmrc.agentclientrelationships.auth.CurrentUser
import uk.gov.hmrc.agentclientrelationships.services.ItsaDeauthAndCleanupService
import uk.gov.hmrc.agentclientrelationships.support.ResettingMockitoSugar
import uk.gov.hmrc.http.HeaderCarrier

import java.time.Instant
import scala.concurrent.Future

trait MockItsaDeauthAndCleanupService {
  this: ResettingMockitoSugar =>

  val mockItsaDeauthAndCleanupService: ItsaDeauthAndCleanupService = resettingMock[ItsaDeauthAndCleanupService]

  def mockDeleteSameAgentRelationship(service: String, arn: String, mtdItId: Option[String], nino: String)(
    response: Future[Boolean]
  ): OngoingStubbing[Future[Boolean]] =
    when(
      mockItsaDeauthAndCleanupService.deleteSameAgentRelationship(
        eqs(service),
        eqs(arn),
        eqs(mtdItId),
        eqs(nino),
        any[Instant]
      )(any[HeaderCarrier], any[CurrentUser], any[Request[_]])
    )
      .thenReturn(response)

}

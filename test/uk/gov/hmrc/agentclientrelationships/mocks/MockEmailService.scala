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
import uk.gov.hmrc.agentclientrelationships.model.Invitation
import uk.gov.hmrc.agentclientrelationships.services.EmailService
import uk.gov.hmrc.agentclientrelationships.support.ResettingMockitoSugar
import play.api.mvc.RequestHeader

import scala.concurrent.{ExecutionContext, Future}

trait MockEmailService {
  this: ResettingMockitoSugar =>

  val mockEmailService: EmailService = resettingMock[EmailService]

  def mockSendAcceptedEmail(invitation: Invitation)(response: Boolean = true): OngoingStubbing[Future[Boolean]] = when(
    mockEmailService.sendAcceptedEmail(eqs(invitation))(any[RequestHeader])
  ).thenReturn(Future.successful(response))

}

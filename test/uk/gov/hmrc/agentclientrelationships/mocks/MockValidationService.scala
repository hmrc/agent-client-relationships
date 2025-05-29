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

import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.{eq => eqTo}
import org.mockito.Mockito.when
import org.mockito.stubbing.OngoingStubbing
import play.api.mvc.RequestHeader
import uk.gov.hmrc.agentclientrelationships.model.RelationshipFailureResponse
import uk.gov.hmrc.agentclientrelationships.model.stride.RelationshipSource
import uk.gov.hmrc.agentclientrelationships.services.ValidationService
import uk.gov.hmrc.agentclientrelationships.support.ResettingMockitoSugar
import uk.gov.hmrc.agentmtdidentifiers.model.Service
import uk.gov.hmrc.domain.TaxIdentifier

import scala.concurrent.Future

trait MockValidationService {
  this: ResettingMockitoSugar =>

  val mockValidationService: ValidationService = resettingMock[ValidationService]

  def mockValidateForTaxIdentifier(
    clientIdType: String,
    clientId: String
  )(response: Either[RelationshipFailureResponse, TaxIdentifier]): OngoingStubbing[Either[RelationshipFailureResponse, TaxIdentifier]] = when(
    mockValidationService.validateForTaxIdentifier(clientIdType, clientId)
  ).thenReturn(response)

  def mockValidateAuthProfileToService(taxIdentifier: TaxIdentifier)(response: Either[
    RelationshipFailureResponse,
    Service
  ]): OngoingStubbing[Future[Either[RelationshipFailureResponse, Service]]] = when(
    mockValidationService.validateAuthProfileToService(
      eqTo(taxIdentifier),
      any[Option[String]],
      any[RelationshipSource],
      any[Option[Service]]
    )(any[RequestHeader])
  ).thenReturn(Future.successful(response))

}

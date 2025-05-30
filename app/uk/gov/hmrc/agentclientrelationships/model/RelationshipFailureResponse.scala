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

package uk.gov.hmrc.agentclientrelationships.model

sealed trait RelationshipFailureResponse
object RelationshipFailureResponse {

  case object RelationshipNotFound
  extends RelationshipFailureResponse
  case object RelationshipSuspended
  extends RelationshipFailureResponse
  case object RelationshipBadRequest
  extends RelationshipFailureResponse
  case object TaxIdentifierError
  extends RelationshipFailureResponse
  case object ClientDetailsNotFound
  extends RelationshipFailureResponse
  case object AgentSuspended
  extends RelationshipFailureResponse
  case object RelationshipStartDateMissing
  extends RelationshipFailureResponse
  case class ErrorRetrievingClientDetails(
    status: Int,
    message: String
  )
  extends RelationshipFailureResponse
  case class ErrorRetrievingAgentDetails(message: String)
  extends RelationshipFailureResponse
  case class ErrorRetrievingRelationship(
    status: Int,
    message: String
  )
  extends RelationshipFailureResponse

}

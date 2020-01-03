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

package uk.gov.hmrc.agentclientrelationships.repository

import play.api.libs.json.{JsValue, Json, Writes, _}
import uk.gov.hmrc.agentclientrelationships.repository.RelationshipCopyRecord.formats
import uk.gov.hmrc.domain.{AgentCode, SaAgentReference, TaxIdentifier}

sealed trait RelationshipReference {
  val value: TaxIdentifier
}

object RelationshipReference {
  case class SaRef(value: SaAgentReference) extends RelationshipReference

  object SaRef {
    implicit val saReads = (__ \ "saAgentReference").read[SaAgentReference].map(SaRef.apply)

    val saWrites: Writes[SaRef] = new Writes[SaRef] {
      override def writes(o: SaRef): JsValue = Json.obj("saAgentReference" -> o.value)
    }
  }

  case class VatRef(value: AgentCode) extends RelationshipReference

  object VatRef {
    implicit val vatReads = (__ \ "oldAgentCode").read[AgentCode].map(VatRef.apply)
    val vatWrites: Writes[VatRef] = new Writes[VatRef] {
      override def writes(o: VatRef): JsValue = Json.obj("oldAgentCode" -> o.value)
    }
  }

  implicit val relationshipReferenceReads =
    __.read[SaRef].map(x => x: RelationshipReference) orElse __.read[VatRef].map(x => x: RelationshipReference)

  implicit val relationshipReferenceWrites = Writes[RelationshipReference] {
    case saRef: SaRef   => SaRef.saWrites.writes(saRef)
    case vatRef: VatRef => VatRef.vatWrites.writes(vatRef)
  }
}

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
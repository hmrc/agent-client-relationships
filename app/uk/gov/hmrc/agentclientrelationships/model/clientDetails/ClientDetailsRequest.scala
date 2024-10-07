package uk.gov.hmrc.agentclientrelationships.model.clientDetails

import play.api.libs.json.{Json, OFormat}


case class ClientDetailsRequest(clientDetails: Map[String, String])

object ClientDetailsRequest {
  implicit val format: OFormat[ClientDetailsRequest] = Json.format[ClientDetailsRequest]
}

package uk.gov.hmrc.agentclientrelationships.model.clientDetails

import play.api.libs.json.{Json, OFormat}


case class ClientDetailsResponse(name: String, status: ClientStatus, isOverseas: Boolean)

object ClientDetailsResponse {
  implicit val format: OFormat[ClientDetailsResponse] = Json.format[ClientDetailsResponse]
}

package uk.gov.hmrc.agentclientrelationships.model.clientDetails.itsa

import play.api.libs.json.{JsPath, Reads}

case class DesignatoryDetails(postCode: Option[String])

object DesignatoryDetails {
  implicit val reads: Reads[DesignatoryDetails] = for {
    postCode <- (JsPath \ "address" \ "postcode").readNullable[String]
  } yield DesignatoryDetails(postCode)
}

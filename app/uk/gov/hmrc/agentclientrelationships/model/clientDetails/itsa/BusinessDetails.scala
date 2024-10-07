package uk.gov.hmrc.agentclientrelationships.model.clientDetails.itsa

import play.api.libs.json.{JsPath, Reads}

case class BusinessDetails(name: String, postcode: String, countryCode: String) {
  def isOverseas: Boolean = countryCode.toUpperCase.contains("GB")
}

object BusinessDetails {
  implicit val reads: Reads[BusinessDetails] = for {
    name <- (JsPath \ "businessData" \ "tradingName").read[String]
    postcode <- (JsPath \ "businessData" \ "businessAddressDetails" \ "postalCode").read[String]
    countryCode <- (JsPath \ "businessData" \ "businessAddressDetails" \ "countryCode").read[String]
  } yield BusinessDetails(name, postcode, countryCode)
}

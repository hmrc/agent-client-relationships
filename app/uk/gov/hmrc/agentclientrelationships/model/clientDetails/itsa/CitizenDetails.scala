package uk.gov.hmrc.agentclientrelationships.model.clientDetails.itsa

import play.api.libs.json.{JsPath, Reads}

case class CitizenDetails(
  firstName: Option[String],
  lastName: Option[String],
) {
  lazy val name: Option[String] = for {
    first <- firstName
    last  <- lastName
    if first.nonEmpty && last.nonEmpty
  } yield s"$first $last"

}

object CitizenDetails {
  implicit val reads: Reads[CitizenDetails] = {
    for {
      firstName <- (JsPath \ "name" \ "current" \ "firstName").readNullable[String]
      lastName  <- (JsPath \ "name" \ "current" \ "lastName").readNullable[String]
    } yield CitizenDetails(firstName, lastName)
  }
}

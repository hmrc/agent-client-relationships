package uk.gov.hmrc.agentclientrelationships.services.clientDetails

import cats.data.EitherT
import uk.gov.hmrc.agentclientrelationships.connectors.clientDetails.itsa.{CitizenDetailsConnector, GetBusinessDetailsConnector}
import uk.gov.hmrc.agentclientrelationships.model.clientDetails._
import uk.gov.hmrc.http.HeaderCarrier

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ItsaClientDetailsService @Inject() (
  getBusinessDetailsConnector: GetBusinessDetailsConnector,
  citizenDetailsConnector: CitizenDetailsConnector
)(implicit ec: ExecutionContext) {

  private def postcodeMatches(submittedPostcode: String, retrievedPostcode: String): Boolean = {
    val postcodeWithoutSpacesRegex = "^[A-Za-z]{1,2}[0-9]{1,2}[A-Za-z]?[0-9][A-Za-z]{2}$".r

    val sanitisedSubmittedPostcode = submittedPostcode.replaceAll("\\s", "").toUpperCase
    val sanitisedRetrievedPostcode = retrievedPostcode.replaceAll("\\s", "").toUpperCase

    val validPostcodes = postcodeWithoutSpacesRegex.matches(sanitisedRetrievedPostcode) &&
      postcodeWithoutSpacesRegex.matches(sanitisedSubmittedPostcode)

    if (validPostcodes) sanitisedSubmittedPostcode == sanitisedRetrievedPostcode else false

    // TODO boolean logic may need a rework
  }

  def getClientDetails(nino: String, submittedPostcode: String, isAltItsa: Boolean)(implicit
    hc: HeaderCarrier
  ): Future[Either[ClientDetailsFailureResponse, ClientDetailsResponse]] = {
    if (isAltItsa)
      (for {
        optName     <- EitherT(citizenDetailsConnector.getCitizenDetails(nino)).map(_.name)
        optPostcode <- EitherT(citizenDetailsConnector.getDesignatoryDetails(nino)).map(_.postCode)
        // TODO overseas check?
      } yield (optName, optPostcode)).subflatMap {
        case (Some(name), Some(postcode)) if postcodeMatches(submittedPostcode, postcode) =>
          Right(ClientDetailsResponse(name, AltItsaClient, isOverseas = false))
        case (Some(_), Some(_)) =>
          Left(ClientDetailsDoNotMatch)
        case (None, None) =>
          Left(ClientDetailsNotFound)
      }
    else
      EitherT(getBusinessDetailsConnector.getDetails(nino)).subflatMap {
        case details if postcodeMatches(submittedPostcode, details.postcode) =>
          Right(ClientDetailsResponse(details.name, ItsaClient, details.isOverseas))
        case _ =>
          Left(ClientDetailsDoNotMatch)
      }
  }.value

}

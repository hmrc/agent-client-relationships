package uk.gov.hmrc.agentclientrelationships.services.clientDetails

import uk.gov.hmrc.agentclientrelationships.model.clientDetails.{ClientDetailsFailureResponse, ClientDetailsRequest, ClientDetailsResponse}
import uk.gov.hmrc.http.HeaderCarrier

import javax.inject.{Inject, Singleton}
import scala.concurrent.Future

@Singleton
class ClientDetailsOrchestratorService @Inject() (itsaService: ItsaClientDetailsService) {

  def findClientDetails(service: String, details: ClientDetailsRequest)(implicit
    hc: HeaderCarrier
  ): Future[Either[ClientDetailsFailureResponse, ClientDetailsResponse]] =
    service.toUpperCase match {
      case "HMRC-MTD-IT" =>
        itsaService.getClientDetails(
          details.clientDetails("nino"),
          details.clientDetails("postcode"),
          isAltItsa = false // TODO when does this apply?
        )
//      case "HMRC-MTD-VAT" || "HMCE-VATDEC-ORG"    => ???
//      case "HMRC-TERS-ORG" || "HMRC-TERSNT-ORG"   => ???
//      case "IR-SA"                                => ???
//      case "HMRC-CGT-PD"                          => ???
//      case "HMRC-PPT-ORG"                         => ???
//      case "HRMC-CBC-ORG" || "HMRC-CBC-NONUK-ORG" => ???
    }

}

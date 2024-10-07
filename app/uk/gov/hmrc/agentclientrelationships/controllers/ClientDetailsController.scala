package uk.gov.hmrc.agentclientrelationships.controllers

import play.api.libs.json.Json
import play.api.mvc.{Action, ControllerComponents}
import uk.gov.hmrc.agentclientrelationships.auth.AuthActions
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.clientDetails._
import uk.gov.hmrc.agentclientrelationships.services.clientDetails.ClientDetailsOrchestratorService
import uk.gov.hmrc.agentmtdidentifiers.model.Service
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.http.InternalServerException
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

@Singleton
class ClientDetailsController @Inject() (
  clientDetailsService: ClientDetailsOrchestratorService,
  val authConnector: AuthConnector,
  cc: ControllerComponents
)(implicit appConfig: AppConfig, ec: ExecutionContext)
    extends BackendController(cc)
    with AuthActions {

  val supportedServices: Seq[Service] = appConfig.supportedServices

  def findClientDetails(service: String): Action[ClientDetailsRequest] =
    Action.async(parse.json[ClientDetailsRequest]) { implicit request =>
      clientDetailsService.findClientDetails(service, request.body).map {
        case Right(details)                => Ok(Json.toJson(details))
        case Left(ClientDetailsDoNotMatch) => BadRequest
        case Left(ClientDetailsNotFound)   => NotFound
        case Left(UnexpectedErrorRetrievingClientDetails(status, message)) =>
          throw new InternalServerException(
            s"Downstream call for service: $service failed with status: $status and error message: $message"
          )
      }

    }

}

/*
 * Copyright 2023 HM Revenue & Customs
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

package uk.gov.hmrc.agentclientrelationships.connectors

import play.api.Logging
import play.api.http.Status
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}
import play.api.mvc.RequestHeader
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.{EnrolmentKey, SetRelationshipEndedPayload}
import uk.gov.hmrc.agentclientrelationships.util.HttpApiMonitor
import uk.gov.hmrc.agentclientrelationships.util.RequestSupport._
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.domain.{Nino, TaxIdentifier}
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse, StringContextOps}
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AgentClientAuthorisationConnector @Inject()(httpClient: HttpClientV2)(implicit
                                                                            val metrics: Metrics,
                                                                            val appConfig: AppConfig,
                                                                            val ec: ExecutionContext
) extends HttpApiMonitor
  with Logging {

   private val acaBaseUrl: String = appConfig.agentClientAuthorisationUrl

  /**
   * Retrieves a list of services for which a partial authorisation exists for a given client.
   *
   * @param clientId The Tax Identifier of the client for which partial authorisations are being retrieved.
   * @param arn      The Agent Reference Number (Arn) of the agency requesting
   */
  def getPartialAuth(
                      clientId: TaxIdentifier,
                      arn: Arn)(implicit
                                hc: HeaderCarrier
                    ): Future[List[String]] = {

    monitor(s"ConsumedAPI-ACA-getPartialAuthExistsFor-GET") {
      httpClient
        .get(
          url"${appConfig.agentClientAuthorisationUrl}/agent-client-authorisation/agencies/${arn.value}/invitations/sent?status=PartialAuth&clientId=${clientId.value}"
        )
        //TODO: Use proper Reads[T] instance from the play-json library to parse the response body and handle errors
        .execute[Option[JsObject]]
        .map { json: JsObject =>

          json.map(json =>
              ((json \ "_embedded" \ "invitations")
                .as[JsArray]
                .value
                .map(x => (x \ "service").as[String])
                .toList)
          ).getOrElse(List.empty)

        }
    }
  }


  //TODO: this method is not used in production code, can it be removed?
  //TODO: use proper return type describing what the call does. If nothing, then return Unit
  def updateAltItsaFor(nino: Nino, service: String)(implicit
    request: RequestHeader
  ): Future[Boolean] = {

    monitor(s"ConsumedAPI-ACA-updateAltItsaFor$service-PUT") {
      httpClient
        .put(url"$acaBaseUrl/agent-client-authorisation/alt-itsa/$service/update/${nino.value}")
        .execute[HttpResponse]
        .map { response =>
          response.status match {
            case Status.CREATED => true
            // TODO: This code suppresses errors,
            //  potentially masking critical issues like 5xx, 4xx, auth problems,
            //  and client-side errors.
            //  Instead of returning an empty list on error,
            //  use a proper `Reads[T]` to handle non-201  responses and invalid JSON.
            //  This will allow the global error handler to process the exceptions appropriately.
            case _ => false
          }
        }
    }
  }

  /**
   * Updates the invitation record to Deauthorised.
   */
  //TODO: use proper return type describing what the call does. If nothing, then return Unit
  def setRelationshipEnded(arn: Arn, enrolmentKey: EnrolmentKey, endedBy: String)(implicit
                                                                                  requestHeader: RequestHeader
  ): Future[Boolean] = {
    val payload = SetRelationshipEndedPayload(
      arn = arn,
      clientId = enrolmentKey.oneIdentifier().value,
      service = enrolmentKey.service,
      endedBy = Some(endedBy)
    )
    monitor(s"ConsumedAPI-ACA-setRelationshipEnded-PUT") {
      httpClient
        .put(url = url"$acaBaseUrl/agent-client-authorisation/invitations/set-relationship-ended")
        .withBody(Json.toJson(payload))
        .execute[HttpResponse]
        .map { response =>
          response.status match {
            case Status.NO_CONTENT => true
            // TODO: This code suppresses errors,
            //  potentially masking critical issues like 5xx, 4xx, auth problems,
            //  and client-side errors.
            //  Instead of returning an empty list on error,
            //  use a proper `Reads[T]` to handle non-201  responses and invalid JSON.
            //  This will allow the global error handler to process the exceptions appropriately.
            case _ => false
          }
        }
    }
  }

  //TODO: wrap service in a strong type
  //TODO: use proper return type describing what the call does. If nothing, then return Unit
  def updateStatusToAccepted(nino: Nino, service: String)(implicit
                                                          requestHeader: RequestHeader
  ): Future[Boolean] = {

    monitor(s"ConsumedAPI-ACA-updateStatusToAccepted-PUT") {
      httpClient
        .put(url"$acaBaseUrl/agent-client-authorisation/agent/alt-itsa/$service/update-status/accepted/${nino.value}")
        .withBody("")
        .execute[HttpResponse]
        .map { response =>
          response.status match {
            case Status.NO_CONTENT => true
            // TODO: This code suppresses errors,
            //  potentially masking critical issues like 5xx, 4xx, auth problems,
            //  and client-side errors.
            //  Instead of returning an empty list on error,
            //  use a proper `Reads[T]` to handle non-201  responses and invalid JSON.
            //  This will allow the global error handler to process the exceptions appropriately.
            case _ => false
          }
        }
    }
  }
}

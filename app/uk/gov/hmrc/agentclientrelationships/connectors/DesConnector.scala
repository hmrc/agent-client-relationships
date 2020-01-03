/*
 * Copyright 2020 HM Revenue & Customs
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

import java.net.URL

import com.codahale.metrics.MetricRegistry
import com.kenshoo.play.metrics.Metrics
import javax.inject.{Inject, Named, Singleton}
import org.joda.time.{DateTimeZone, LocalDate}
import play.api.libs.json._
import play.utils.UriEncoding
import uk.gov.hmrc.agent.kenshoo.monitoring.HttpAPIMonitor
import uk.gov.hmrc.agentclientrelationships.UriPathEncoding.encodePathSegment
import uk.gov.hmrc.agentclientrelationships.model._
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, CgtRef, MtdItId, Utr, Vrn}
import uk.gov.hmrc.domain.{Nino, SaAgentReference, TaxIdentifier}
import uk.gov.hmrc.http.logging.Authorization
import uk.gov.hmrc.http._

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.Duration

@Singleton
class DesConnector @Inject()(
  @Named("des-baseUrl") baseUrl: URL,
  @Named("des.authorizationToken") authorizationToken: String,
  @Named("des.environment") environment: String,
  @Named("inactive-relationships.show-last-days") showInactiveRelationshipsDuration: Duration,
  httpGet: HttpGet,
  httpPost: HttpPost,
  metrics: Metrics)
    extends HttpAPIMonitor {
  override val kenshooRegistry: MetricRegistry = metrics.defaultRegistry

  def getNinoFor(mtdbsa: MtdItId)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Nino] = {
    val url = new URL(baseUrl, s"/registration/business-details/mtdbsa/${encodePathSegment(mtdbsa.value)}")

    getWithDesHeaders[HttpResponse]("GetRegistrationBusinessDetailsByMtdbsa", url).map { result =>
      (result.json \ "nino").as[Nino]
    }
  }

  def getMtdIdFor(nino: Nino)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[MtdItId] = {
    val url = new URL(baseUrl, s"/registration/business-details/nino/${encodePathSegment(nino.value)}")

    getWithDesHeaders[HttpResponse]("GetRegistrationBusinessDetailsByNino", url).map { result =>
      (result.json \ "mtdbsa").as[MtdItId]
    }
  }

  def getClientSaAgentSaReferences(
    nino: Nino)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Seq[SaAgentReference]] = {
    val url = new URL(baseUrl, s"/registration/relationship/nino/${encodePathSegment(nino.value)}")

    getWithDesHeaders[Agents]("GetStatusAgentRelationship", url).map(
      _.agents
        .filter(agent => agent.hasAgent && agent.agentCeasedDate.isEmpty)
        .flatMap(_.agentId))
  }

  def getActiveClientRelationships(taxIdentifier: TaxIdentifier)(
    implicit hc: HeaderCarrier,
    ec: ExecutionContext): Future[Option[ActiveRelationship]] = {
    val encodedClientId = UriEncoding.encodePathSegment(taxIdentifier.value, "UTF-8")
    val url = taxIdentifier match {
      case MtdItId(_) =>
        new URL(
          s"$baseUrl/registration/relationship?ref-no=$encodedClientId&agent=false&active-only=true&regime=${getRegimeFor(taxIdentifier)}")
      case Vrn(_) =>
        new URL(
          s"$baseUrl/registration/relationship?idtype=VRN&ref-no=$encodedClientId&agent=false&active-only=true&regime=${getRegimeFor(taxIdentifier)}")
      case Utr(_) =>
        new URL(
          s"$baseUrl/registration/relationship?idtype=UTR&ref-no=$encodedClientId&agent=false&active-only=true&regime=${getRegimeFor(taxIdentifier)}")
      case CgtRef(_) =>
        new URL(
          s"$baseUrl/registration/relationship?idtype=ZCGT&ref-no=$encodedClientId&agent=false&active-only=true&regime=${getRegimeFor(taxIdentifier)}")
    }

    getWithDesHeaders[ActiveRelationshipResponse]("GetActiveClientItSaRelationships", url)
      .map(_.relationship.find(isActive))
      .recover {
        case _: BadRequestException => None
        case _: NotFoundException   => None
      }
  }

  def getInactiveRelationships(arn: Arn, service: String)(
    implicit hc: HeaderCarrier,
    ec: ExecutionContext): Future[Seq[InactiveRelationship]] = {
    val encodedAgentId = UriEncoding.encodePathSegment(arn.value, "UTF-8")
    val now = LocalDate.now().toString
    val from: String = LocalDate.now().minusDays(showInactiveRelationshipsDuration.toDays.toInt).toString
    val url = new URL(
      s"$baseUrl/registration/relationship?arn=$encodedAgentId&agent=true&active-only=false&regime=${getRegimeFor(
        service)}&from=$from&to=$now")

    getWithDesHeaders[InactiveRelationshipResponse](s"GetAllAgent${getRegimeFor(service)}Relationships", url)
      .map(_.relationship.filter(isNotActive))
      .recover {
        case _: BadRequestException => Seq.empty
        case _: NotFoundException   => Seq.empty
      }
  }

  def isActive(r: ActiveRelationship): Boolean = r.dateTo match {
    case None    => true
    case Some(d) => d.isAfter(LocalDate.now(DateTimeZone.UTC))
  }

  def isNotActive(r: InactiveRelationship): Boolean = r.dateTo match {
    case None    => false
    case Some(d) => d.isBefore(LocalDate.now(DateTimeZone.UTC)) || d.equals(LocalDate.now(DateTimeZone.UTC))
  }

  def createAgentRelationship(clientId: TaxIdentifier, arn: Arn)(
    implicit hc: HeaderCarrier,
    ec: ExecutionContext): Future[RegistrationRelationshipResponse] = {

    val url = new URL(baseUrl, s"/registration/relationship")
    val requestBody = createAgentRelationshipInputJson(clientId.value, arn.value, getRegimeFor(clientId))

    postWithDesHeaders[JsValue, RegistrationRelationshipResponse]("CreateAgentRelationship", url, requestBody)
  }

  def deleteAgentRelationship(clientId: TaxIdentifier, arn: Arn)(
    implicit hc: HeaderCarrier,
    ec: ExecutionContext): Future[RegistrationRelationshipResponse] = {

    val url = new URL(baseUrl, s"/registration/relationship")
    postWithDesHeaders[JsValue, RegistrationRelationshipResponse](
      "DeleteAgentRelationship",
      url,
      deleteAgentRelationshipInputJson(clientId.value, arn.value, getRegimeFor(clientId)))
  }

  private def getRegimeFor(service: String): String =
    service match {
      case "HMRC-MTD-IT"   => "ITSA"
      case "HMRC-MTD-VAT"  => "VATC"
      case "HMRC-TERS-ORG" => "TRS"
      case "HMRC-CGT-PD"   => "CGT"
      case _               => throw new IllegalArgumentException(s"Service not supported $service")
    }

  private def getRegimeFor(clientId: TaxIdentifier): String =
    clientId match {
      case MtdItId(_) => "ITSA"
      case Vrn(_)     => "VATC"
      case Utr(_)     => "TRS"
      case CgtRef(_)  => "CGT"
      case _          => throw new IllegalArgumentException(s"Tax identifier not supported $clientId")
    }

  private def getWithDesHeaders[A: HttpReads](apiName: String, url: URL)(
    implicit hc: HeaderCarrier,
    ec: ExecutionContext): Future[A] = {
    val desHeaderCarrier = hc.copy(
      authorization = Some(Authorization(s"Bearer $authorizationToken")),
      extraHeaders = hc.extraHeaders :+ "Environment" -> environment)
    monitor(s"ConsumedAPI-DES-$apiName-GET") {
      httpGet.GET[A](url.toString)(implicitly[HttpReads[A]], desHeaderCarrier, ec)
    }
  }

  private def postWithDesHeaders[A: Writes, B: HttpReads](apiName: String, url: URL, body: A)(
    implicit hc: HeaderCarrier,
    ec: ExecutionContext): Future[B] = {
    val desHeaderCarrier = hc.copy(
      authorization = Some(Authorization(s"Bearer $authorizationToken")),
      extraHeaders = hc.extraHeaders :+ "Environment" -> environment)
    monitor(s"ConsumedAPI-DES-$apiName-POST") {
      httpPost.POST[A, B](url.toString, body)(implicitly[Writes[A]], implicitly[HttpReads[B]], desHeaderCarrier, ec)
    }
  }

  private def createAgentRelationshipInputJson(refNum: String, agentRefNum: String, regime: String) =
    includeIdTypeIfNeeded(Json.parse(s"""{
         "acknowledgmentReference": "${java.util.UUID.randomUUID().toString.replace("-", "").take(32)}",
          "agentReferenceNumber": "$agentRefNum",
          "refNumber": "$refNum",
          "regime": "$regime",
          "authorisation": {
            "action": "Authorise",
            "isExclusiveAgent": true
          }
       }""").as[JsObject])

  private def deleteAgentRelationshipInputJson(refNum: String, agentRefNum: String, regime: String) =
    includeIdTypeIfNeeded(Json.parse(s"""{
         "acknowledgmentReference": "${java.util.UUID.randomUUID().toString.replace("-", "").take(32)}",
          "refNumber": "$refNum",
          "agentReferenceNumber": "$agentRefNum",
          "regime": "$regime",
          "authorisation": {
            "action": "De-Authorise"
          }
     }""").as[JsObject])

  private val includeIdTypeIfNeeded: JsObject => JsObject = { request =>
    (request \ "regime").asOpt[String] match {
      case Some("VATC") =>
        request +
          (("idType", JsString("VRN"))) +
          (("relationshipType", JsString("ZA01"))) +
          (("authProfile", JsString("ALL00001")))
      case Some("TRS") =>
        request +
          (("idType", JsString("UTR")))
      case Some("CGT") =>
        request +
          (("relationshipType", JsString("ZA01"))) +
          (("authProfile", JsString("ALL00001"))) +
          (("idType", JsString("ZCGT")))
      case _ =>
        request
    }
  }
}

/*
 * Copyright 2019 HM Revenue & Customs
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
import javax.inject.{Inject, Named, Singleton}

import com.codahale.metrics.MetricRegistry
import com.kenshoo.play.metrics.Metrics
import org.joda.time.{DateTimeZone, LocalDate}
import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import play.api.libs.json._
import play.utils.UriEncoding
import uk.gov.hmrc.agent.kenshoo.monitoring.HttpAPIMonitor
import uk.gov.hmrc.agentclientrelationships.UriPathEncoding.encodePathSegment
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId, Vrn}
import uk.gov.hmrc.domain.{Nino, SaAgentReference, TaxIdentifier}
import uk.gov.hmrc.http._
import uk.gov.hmrc.http.logging.Authorization

import scala.concurrent.{ExecutionContext, Future}

case class NinoBusinessDetails(nino: Nino)

object NinoBusinessDetails {
  implicit val reads = Json.reads[NinoBusinessDetails]
}

case class MtdItIdBusinessDetails(mtdbsa: MtdItId)

object MtdItIdBusinessDetails {
  implicit val reads = Json.reads[MtdItIdBusinessDetails]
}

trait Relationship {
  val arn: Arn
  val dateTo: Option[LocalDate]
  val dateFrom: Option[LocalDate]
}

case class ItsaRelationship(arn: Arn, dateTo: Option[LocalDate], dateFrom: Option[LocalDate]) extends Relationship

object ItsaRelationship {
  implicit val relationshipWrites = Json.writes[ItsaRelationship]

  implicit val reads: Reads[ItsaRelationship] = ((JsPath \ "agentReferenceNumber").read[Arn] and
    (JsPath \ "dateTo").readNullable[LocalDate] and
    (JsPath \ "dateFrom").readNullable[LocalDate])(ItsaRelationship.apply _)
}

case class ItsaInactiveRelationship(
  arn: Arn,
  dateTo: Option[LocalDate],
  dateFrom: Option[LocalDate],
  referenceNumber: String)
    extends Relationship

object ItsaInactiveRelationship {
  implicit val relationshipWrites = Json.writes[ItsaInactiveRelationship]

  implicit val reads: Reads[ItsaInactiveRelationship] = ((JsPath \ "agentReferenceNumber").read[Arn] and
    (JsPath \ "dateTo").readNullable[LocalDate] and
    (JsPath \ "dateFrom").readNullable[LocalDate] and
    (JsPath \ "referenceNumber").read[String])(ItsaInactiveRelationship.apply _)
}

case class VatRelationship(arn: Arn, dateTo: Option[LocalDate], dateFrom: Option[LocalDate]) extends Relationship

object VatRelationship {
  implicit val relationshipWrites = Json.writes[VatRelationship]

  implicit val reads: Reads[VatRelationship] = ((JsPath \ "agentReferenceNumber").read[Arn] and
    (JsPath \ "dateTo").readNullable[LocalDate] and
    (JsPath \ "dateFrom").readNullable[LocalDate])(VatRelationship.apply _)
}

case class VatInactiveRelationship(
  arn: Arn,
  dateTo: Option[LocalDate],
  dateFrom: Option[LocalDate],
  referenceNumber: String)
    extends Relationship

object VatInactiveRelationship {
  implicit val relationshipWrites = Json.writes[VatInactiveRelationship]

  implicit val reads: Reads[VatInactiveRelationship] = ((JsPath \ "agentReferenceNumber").read[Arn] and
    (JsPath \ "dateTo").readNullable[LocalDate] and
    (JsPath \ "dateFrom").readNullable[LocalDate] and
    (JsPath \ "referenceNumber").read[String])(VatInactiveRelationship.apply _)
}

case class ItsaRelationshipResponse(relationship: Seq[ItsaRelationship])

case class ItsaInactiveRelationshipResponse(relationship: Seq[ItsaInactiveRelationship])

case class VatRelationshipResponse(relationship: Seq[VatRelationship])

case class VatInactiveRelationshipResponse(relationship: Seq[VatInactiveRelationship])

object ItsaRelationshipResponse {
  implicit val relationshipResponseFormat = Json.format[ItsaRelationshipResponse]
}

object ItsaInactiveRelationshipResponse {
  implicit val relationshipResponseFormat = Json.format[ItsaInactiveRelationshipResponse]
}

object VatRelationshipResponse {
  implicit val vatRelationshipResponseFormat = Json.format[VatRelationshipResponse]
}

object VatInactiveRelationshipResponse {
  implicit val vatRelationshipResponseFormat = Json.format[VatInactiveRelationshipResponse]
}

case class ClientRelationship(agents: Seq[Agent])

case class Agent(hasAgent: Boolean, agentId: Option[SaAgentReference], agentCeasedDate: Option[String])

object ClientRelationship {
  implicit val agentReads = Json.reads[Agent]

  implicit val readClientRelationship =
    (JsPath \ "agents")
      .readNullable[Seq[Agent]]
      .map(optionalAgents => ClientRelationship(optionalAgents.getOrElse(Seq.empty)))
}

case class RegistrationRelationshipResponse(processingDate: String)

object RegistrationRelationshipResponse {
  implicit val reads = Json.reads[RegistrationRelationshipResponse]
}

@Singleton
class DesConnector @Inject()(
  @Named("des-baseUrl") baseUrl: URL,
  @Named("des.authorizationToken") authorizationToken: String,
  @Named("des.environment") environment: String,
  httpGet: HttpGet,
  httpPost: HttpPost,
  metrics: Metrics)
    extends HttpAPIMonitor {
  override val kenshooRegistry: MetricRegistry = metrics.defaultRegistry

  def getNinoFor(mtdbsa: MtdItId)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Nino] = {
    val url = new URL(baseUrl, s"/registration/business-details/mtdbsa/${encodePathSegment(mtdbsa.value)}")

    getWithDesHeaders[NinoBusinessDetails]("GetRegistrationBusinessDetailsByMtdbsa", url).map(_.nino)
  }

  def getMtdIdFor(nino: Nino)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[MtdItId] = {
    val url = new URL(baseUrl, s"/registration/business-details/nino/${encodePathSegment(nino.value)}")

    getWithDesHeaders[MtdItIdBusinessDetails]("GetRegistrationBusinessDetailsByNino", url).map(_.mtdbsa)
  }

  def getClientSaAgentSaReferences(
    nino: Nino)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Seq[SaAgentReference]] = {
    val url = new URL(baseUrl, s"/registration/relationship/nino/${encodePathSegment(nino.value)}")

    getWithDesHeaders[ClientRelationship]("GetStatusAgentRelationship", url).map(
      _.agents
        .filter(agent => agent.hasAgent && agent.agentCeasedDate.isEmpty)
        .flatMap(_.agentId))
  }

  def getActiveClientItsaRelationships(
    mtdItId: MtdItId)(implicit c: HeaderCarrier, ec: ExecutionContext): Future[Option[ItsaRelationship]] = {
    val encodedClientId = UriEncoding.encodePathSegment(mtdItId.value, "UTF-8")
    val url = new URL(
      s"$baseUrl/registration/relationship?ref-no=$encodedClientId&agent=false&active-only=true&regime=ITSA")

    getWithDesHeaders[ItsaRelationshipResponse]("GetActiveClientItSaRelationships", url)
      .map(_.relationship.find(isActive))
      .recover {
        case e: BadRequestException => None
        case e: NotFoundException   => None
      }
  }

  def getActiveClientVatRelationships(
    vrn: Vrn)(implicit c: HeaderCarrier, ec: ExecutionContext): Future[Option[VatRelationship]] = {
    val encodedClientId = UriEncoding.encodePathSegment(vrn.value, "UTF-8")
    val url = new URL(
      s"$baseUrl/registration/relationship?idtype=VRN&ref-no=$encodedClientId&agent=false&active-only=true&regime=VATC")

    getWithDesHeaders[VatRelationshipResponse]("GetActiveClientVatRelationships", url)
      .map(_.relationship.find(isActive))
      .recover {
        case e: BadRequestException => None
        case e: NotFoundException   => None
      }
  }

  def getInactiveAgentItsaRelationships(
    arn: Arn)(implicit c: HeaderCarrier, ec: ExecutionContext): Future[Seq[ItsaInactiveRelationship]] = {
    val encodedClientId = UriEncoding.encodePathSegment(arn.value, "UTF-8")
    val now = LocalDate.now().toString
    val url = new URL(
      s"$baseUrl/registration/relationship?arn=$encodedClientId&agent=true&active-only=false&regime=ITSA&from=1970-01-01&to=$now")

    getWithDesHeaders[ItsaInactiveRelationshipResponse]("GetAllAgentItsaRelationships", url)
      .map(_.relationship.filter(isNotActive))
      .recover {
        case e: BadRequestException => Seq.empty
        case e: NotFoundException   => Seq.empty
      }
  }

  def getInactiveAgentVatRelationships(
    arn: Arn)(implicit c: HeaderCarrier, ec: ExecutionContext): Future[Seq[VatInactiveRelationship]] = {
    val encodedClientId = UriEncoding.encodePathSegment(arn.value, "UTF-8")
    val now = LocalDate.now().toString
    val url = new URL(
      s"$baseUrl/registration/relationship?arn=$encodedClientId&agent=true&active-only=false&regime=VATC&from=1970-01-01&to=$now")

    getWithDesHeaders[VatInactiveRelationshipResponse]("GetAllAgentVatRelationships", url)
      .map(_.relationship.filter(isNotActive))
      .recover {
        case e: BadRequestException => Seq.empty
        case e: NotFoundException   => Seq.empty
      }
  }

  def isActive(r: Relationship): Boolean = r.dateTo match {
    case None    => true
    case Some(d) => d.isAfter(LocalDate.now(DateTimeZone.UTC))
  }

  def isNotActive(r: Relationship): Boolean = r.dateTo match {
    case None    => false
    case Some(d) => d.isBefore(LocalDate.now(DateTimeZone.UTC)) || d.equals(LocalDate.now(DateTimeZone.UTC))
  }

  def createAgentRelationship(clientId: TaxIdentifier, arn: Arn)(
    implicit hc: HeaderCarrier,
    ec: ExecutionContext): Future[RegistrationRelationshipResponse] = {
    val regime = clientId match {
      case MtdItId(_) => "ITSA"
      case Vrn(_)     => "VATC"
      case _          => throw new IllegalArgumentException(s"Tax identifier not supported $clientId")
    }

    val url = new URL(baseUrl, s"/registration/relationship")
    val requestBody = createAgentRelationshipInputJson(clientId.value, arn.value, regime)

    postWithDesHeaders[JsValue, RegistrationRelationshipResponse]("CreateAgentRelationship", url, requestBody)
  }

  def deleteAgentRelationship(clientId: TaxIdentifier, arn: Arn)(
    implicit hc: HeaderCarrier,
    ec: ExecutionContext): Future[RegistrationRelationshipResponse] = {
    val regime = clientId match {
      case MtdItId(_) => "ITSA"
      case Vrn(_)     => "VATC"
      case _          => throw new IllegalArgumentException(s"Tax identifier not supported $clientId")
    }
    val url = new URL(baseUrl, s"/registration/relationship")

    postWithDesHeaders[JsValue, RegistrationRelationshipResponse](
      "DeleteAgentRelationship",
      url,
      deleteAgentRelationshipInputJson(clientId.value, arn.value, regime))
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
          ("idType", JsString("VRN")) +
          ("relationshipType", JsString("ZA01")) +
          ("authProfile", JsString("ALL00001"))
      case _ =>
        request
    }
  }
}

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

package uk.gov.hmrc.agentclientrelationships.services

import com.google.inject.{Inject, Singleton}
import org.apache.pekko.Done
import play.api.Logger
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors.PlatformAnalyticsConnector
import uk.gov.hmrc.agentclientrelationships.model.{Accepted, AnalyticsRequest, Cancelled, DeAuthorised, DimensionValue, Event, Expired, Invitation, PartialAuth, Pending, Rejected}
import uk.gov.hmrc.agentmtdidentifiers.model.Service.MtdIt
import uk.gov.hmrc.http.HeaderCarrier

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.util.hashing.{MurmurHash3 => MH3}

@Singleton
class PlatformAnalyticsService @Inject() (connector: PlatformAnalyticsConnector, appConfig: AppConfig) {

  private val trackingId = appConfig.gaTrackingId
  private val clientTypeIndex = appConfig.gaClientTypeIndex
  private val invitationIdIndex = appConfig.gaInvitationIdIndex
  private val originIndex = appConfig.gaOriginIndex
  private val altItsaIndex = appConfig.gaAltItsaIndex

  val logger = Logger(getClass)

  def reportSingleEventAnalyticsRequest(i: Invitation, origin: Option[String])(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Done] = {
    logger.info(s"sending GA event for invitation: ${i.invitationId} with status: ${i.status} and origin: ${origin
      .getOrElse("origin_not_set")}")
    val maybeGAClientId: Option[String] = if (hc.sessionId.isDefined) None else Some(makeGAClientId)
    sendAnalyticsRequest(List((i, origin)), maybeGAClientId, origin)
  }

  private def sendAnalyticsRequest(
    invitations: List[(Invitation, Option[String])],
    clientId: Option[String],
    origin: Option[String]
  )(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Done] =
    connector.sendEvent(
      AnalyticsRequest(
        gaClientId = clientId,
        gaTrackingId = Some(trackingId),
        events = invitations.map(i => createEventFor(i))
      )
    )

  private def createEventFor(i: (Invitation, Option[String])): Event =
    i._1.status match {
      case Pending | PartialAuth => makeAuthRequestEvent("created", i)
      case Accepted              => makeAuthRequestEvent("accepted", i)
      case Rejected              => makeAuthRequestEvent("declined", i)
      case Expired               => makeAuthRequestEvent("expired", i)
      case Cancelled             => makeAuthRequestEvent("cancelled", i)
      case DeAuthorised          => makeAuthRequestEvent("deauthorised", i)
    }

  private def makeAuthRequestEvent(action: String, i: (Invitation, Option[String])): Event =
    Event(
      category = "authorisation request",
      action = action,
      label = i._1.service.toLowerCase,
      dimensions = List(
        DimensionValue(clientTypeIndex, i._1.clientIdType),
        DimensionValue(invitationIdIndex, i._1.invitationId),
        DimensionValue(originIndex, i._2.getOrElse("unknown"))
      ) ++ altItsa(i._1).map(v => List(DimensionValue(altItsaIndex, v.toString))).getOrElse(List.empty)
    )

  // platform analytics will make a client ID from the session ID but when there is no session (eg for expired status) use this to make a client ID.
  private def makeGAClientId: String = {
    val uuid = UUID.randomUUID().toString
    MH3.stringHash(uuid, MH3.stringSeed).abs.toString match {
      case uuidHash =>
        "GA1.1." + (uuidHash + "000000000")
          .substring(0, 9) + "." + ("0000000000" + uuidHash).substring(uuidHash.length, 10 + uuidHash.length).reverse
    }
  }

  private def altItsa(i: Invitation): Option[Boolean] =
    if (i.service == MtdIt.id) Some(i.clientId == i.suppliedClientId) else None

}

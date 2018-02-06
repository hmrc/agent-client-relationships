/*
 * Copyright 2018 HM Revenue & Customs
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
import javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING
import javax.xml.parsers.SAXParserFactory

import com.codahale.metrics.MetricRegistry
import com.kenshoo.play.metrics.Metrics
import org.apache.xerces.impl.Constants._
import play.api.http.ContentTypes.XML
import play.api.http.HeaderNames.CONTENT_TYPE
import uk.gov.hmrc.agent.kenshoo.monitoring.HttpAPIMonitor
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId, Vrn}
import uk.gov.hmrc.domain.{AgentCode, Nino, TaxIdentifier}
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext._

import scala.concurrent.Future
import scala.xml.Elem
import scala.xml.XML.withSAXParser
import uk.gov.hmrc.http.{HeaderCarrier, HttpPost}

@Singleton
class GovernmentGatewayProxyConnector @Inject()(@Named("government-gateway-proxy-baseUrl") baseUrl: URL, httpPost: HttpPost, metrics: Metrics)
  extends HttpAPIMonitor {
  override val kenshooRegistry: MetricRegistry = metrics.defaultRegistry

  private def path(method: String): String = new URL(baseUrl, s"/government-gateway-proxy/api/admin/$method").toString

  def getCredIdFor(arn: Arn)(implicit hc: HeaderCarrier): Future[String] = {
    monitor("ConsumedAPI-GGW-GsoAdminGetCredentialsForDirectEnrolments-POST") {
      httpPost.POSTString(path("GsoAdminGetCredentialsForDirectEnrolments"), GsoAdminGetCredentialsForDirectEnrolmentsXmlInput(arn), Seq(CONTENT_TYPE -> XML))
    }.map({ response =>
      val xml = toXmlElement(response.body)
      (xml \\ "CredentialIdentifier").headOption.map(_.text).getOrElse(throw RelationshipNotFound("INVALID_ARN"))
    })
  }

  def getAgentCodeFor(credentialIdentifier: String)(implicit hc: HeaderCarrier): Future[AgentCode] = {
    monitor("ConsumedAPI-GGW-GsoAdminGetUserDetails-POST") {
      httpPost.POSTString(path("GsoAdminGetUserDetails"), GsoAdminGetUserDetailsXmlInput(credentialIdentifier), Seq(CONTENT_TYPE -> XML))
    }.map({ response =>
      val xml = toXmlElement(response.body)
      AgentCode((xml \\ "AgentCode").headOption.map(_.text).getOrElse(throw RelationshipNotFound("UNKNOWN_AGENT_CODE")))
    })
  }

  def getAllocatedAgentCodes(identifier: TaxIdentifier)(implicit hc: HeaderCarrier): Future[Set[AgentCode]] = {
    monitor("ConsumedAPI-GGW-GsoAdminGetAssignedAgents-POST") {
      httpPost.POSTString(path("GsoAdminGetAssignedAgents"), GsoAdminGetAssignedAgentsXmlInput(identifier), Seq(CONTENT_TYPE -> XML))
    }.map({ response =>
      val xml: Elem = toXmlElement(response.body)
      val agentDetails = xml \ "AllocatedAgents" \ "AgentDetails"
      agentDetails.map(agency => (agency \ "AgentCode").text).filterNot(_.isEmpty).map(AgentCode(_)).toSet
    })
  }

  def getAllocatedAgentCodesForHmceVatDec(vrn: Vrn)(implicit hc: HeaderCarrier): Future[Seq[Vrn]] = {
    monitor("ConsumedAPI-GGW-GsoAdminGetAssignedAgents-POST") {
      httpPost.POSTString(path("GsoAdminGetAssignedAgents"), GsoAdminGetAssignedAgentsXmlInputForHmceVatDec(vrn), Seq(CONTENT_TYPE -> XML))
    }.map({ response =>
      val xml: Elem = toXmlElement(response.body)
      val agentDetails = xml \ "AllocatedAgents" \ "AgentDetails"
      agentDetails.map(agency => (agency \ "AgentCode").text).filterNot(_.isEmpty).map(Vrn(_))
    })
  }

  def allocateAgent(agentCode: AgentCode, identifier: TaxIdentifier)(implicit hc: HeaderCarrier): Future[Boolean] = {
    monitor("ConsumedAPI-GGW-GsoAdminAllocateAgent-POST") {
      httpPost.POSTString(path("GsoAdminAllocateAgent"), GsoAdminAllocateAgentXmlInput(identifier, agentCode.value), Seq(CONTENT_TYPE -> XML))
    }.map({ response =>
      val xml: Elem = toXmlElement(response.body) ensuring (_.label == "GsoAdminAllocateAgentXmlOutput")
      (xml \ "PrincipalEnrolmentAlreadyExisted").text.toBoolean
    })
  }

  def deallocateAgent(agentCode: AgentCode, mtdItId: MtdItId)(implicit hc: HeaderCarrier): Future[Unit] = {
    monitor("ConsumedAPI-GGW-GsoAdminDeallocateAgent-POST") {
      httpPost.POSTString(path("GsoAdminDeallocateAgent"), GsoAdminDeallocateAgentXmlInput(mtdItId.value,"MTDITID",agentCode.value), Seq(CONTENT_TYPE -> XML))
    }.map({ response =>
      toXmlElement(response.body) ensuring (_.label == "GsoAdminDeallocateAgentXmlOutput")
    })
  }


  private def toXmlElement(xmlString: String): Elem = {
    val factory = SAXParserFactory.newInstance("org.apache.xerces.jaxp.SAXParserFactoryImpl", this.getClass.getClassLoader)
    factory.setFeature(SAX_FEATURE_PREFIX + EXTERNAL_GENERAL_ENTITIES_FEATURE, false)
    factory.setFeature(SAX_FEATURE_PREFIX + EXTERNAL_PARAMETER_ENTITIES_FEATURE, false)
    factory.setFeature(XERCES_FEATURE_PREFIX + DISALLOW_DOCTYPE_DECL_FEATURE, true)
    factory.setFeature(FEATURE_SECURE_PROCESSING, true)
    withSAXParser(factory.newSAXParser())loadString xmlString
  }

  private def GsoAdminGetCredentialsForDirectEnrolmentsXmlInput(arn: Arn): String =
    <GsoAdminGetCredentialsForDirectEnrolmentsXmlInput xmlns="urn:GSO-System-Services:external:1.67:GsoAdminGetCredentialsForDirectEnrolmentsXmlInput">
      <ServiceName>HMRC-AS-AGENT</ServiceName>
      <IdentifierSets>
        <IdentifierSet>
          <Identifiers>
            <Identifier IdentifierType="AgentReferenceNumber">{arn.value}</Identifier>
          </Identifiers>
        </IdentifierSet>
      </IdentifierSets>
    </GsoAdminGetCredentialsForDirectEnrolmentsXmlInput>.toString()

  private def GsoAdminGetUserDetailsXmlInput(credentialIdentifier: String): String =
    <GsoAdminGetUserDetailsXmlInput xmlns="urn:GSO-System-Services:external:2.14.4:GsoAdminGetUserDetailsXmlInput">
      <DelegatedAccessIdentifier>HMRC</DelegatedAccessIdentifier>
      <CredentialIdentifier>{credentialIdentifier}</CredentialIdentifier>
    </GsoAdminGetUserDetailsXmlInput>.toString()

  private def GsoAdminGetAssignedAgentsXmlInput(identifier: TaxIdentifier):String = {
    <GsoAdminGetAssignedAgentsXmlInput xmlns="urn:GSO-System-Services:external:2.13.3:GsoAdminGetAssignedAgentsXmlInput">
      <DelegatedAccessIdentifier>HMRC</DelegatedAccessIdentifier>
      {xmlServiceAndIdentifier(identifier)}
    </GsoAdminGetAssignedAgentsXmlInput>.toString()
  }

  private def GsoAdminGetAssignedAgentsXmlInputForHmceVatDec(vrn: Vrn):String = {
    <GsoAdminGetAssignedAgentsXmlInput xmlns="urn:GSO-System-Services:external:2.13.3:GsoAdminGetAssignedAgentsXmlInput">
      <DelegatedAccessIdentifier>HMRC</DelegatedAccessIdentifier>
      <ServiceName>HMCE-VATDEC-ORG</ServiceName>
      <Identifiers>
        <Identifier IdentifierType="VATRegNo">{vrn.value}</Identifier>
      </Identifiers>
    </GsoAdminGetAssignedAgentsXmlInput>.toString()
  }

  private def xmlServiceAndIdentifier(identifier: TaxIdentifier) = identifier match {
    case MtdItId(value) =>
      <ServiceName>HMRC-MTD-IT</ServiceName>
      <Identifiers>
        <Identifier IdentifierType="MTDITID">{value}</Identifier>
      </Identifiers>

    case Nino(value) =>
      <ServiceName>HMRC-MTD-IT</ServiceName>
      <Identifiers>
        <Identifier IdentifierType="NINO">{value}</Identifier>
      </Identifiers>

    case Vrn(value) =>
      <ServiceName>HMRC-MTD-VAT</ServiceName>
      <Identifiers>
        <Identifier IdentifierType="MTDVATID">{value}</Identifier>
      </Identifiers>
  }

  private def GsoAdminAllocateAgentXmlInput(identifier: TaxIdentifier, agentCode: String): String =
    <GsoAdminAllocateAgentXmlInput xmlns="urn:GSO-System-Services:external:1.65:GsoAdminAllocateAgentXmlInput">
      {xmlServiceAndIdentifier(identifier)}
      <AgentCode>{agentCode}</AgentCode>
    </GsoAdminAllocateAgentXmlInput>.toString()

  private def GsoAdminDeallocateAgentXmlInput(identifier: String, identifierType: String, agentCode: String): String =
    <GsoAdminDeallocateAgentXmlInput xmlns="urn:GSO-System-Services:external:1.65:GsoAdminDeallocateAgentXmlInput">
      <ServiceName>HMRC-MTD-IT</ServiceName>
      <Identifiers>
        <Identifier IdentifierType={identifierType}>{identifier}</Identifier>
      </Identifiers>
      <AgentCode>{agentCode}</AgentCode>
    </GsoAdminDeallocateAgentXmlInput>.toString()
}

case class RelationshipNotFound(errorCode: String) extends Exception(errorCode)

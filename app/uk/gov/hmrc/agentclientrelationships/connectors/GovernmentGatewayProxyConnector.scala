package uk.gov.hmrc.agentclientrelationships.connectors

import java.net.URL
import javax.inject.{Inject, Named, Singleton}
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory

import org.apache.xerces.impl.Constants
import play.api.http.ContentTypes.XML
import play.api.http.HeaderNames.CONTENT_TYPE
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import uk.gov.hmrc.agent.kenshoo.monitoring.HttpAPIMonitor
import uk.gov.hmrc.play.http.{HeaderCarrier, HttpPost}

import scala.concurrent.Future
import scala.xml.Elem
import com.kenshoo.play.metrics.Metrics
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.domain.AgentCode

@Singleton
class GovernmentGatewayProxyConnector @Inject()(@Named("government-gateway-proxy-baseUrl") baseUrl: URL, httpPost: HttpPost, metrics: Metrics)
  extends HttpAPIMonitor {
  override val kenshooRegistry = metrics.defaultRegistry

  private def path(method: String): String = new URL(baseUrl, s"/government-gateway-proxy/api/admin/$method").toString

  def getCredIdFor(arn: Arn)(implicit hc: HeaderCarrier): Future[String] = {
    monitor("ConsumedAPI-GGW-GsoAdminGetCredentialsForDirectEnrolments-POST") {
      httpPost.POSTString(path("GsoAdminGetCredentialsForDirectEnrolments"), GsoAdminGetCredentialsForDirectEnrolmentsXmlInput(arn), Seq(CONTENT_TYPE -> XML))
    }.map({ response =>
      val xml = toXmlElement(response.body)
      (xml \\ "CredentialIdentifier").head.text
    })
  }

  def getAgentCodeFor(credentialIdentifier: String)(implicit hc: HeaderCarrier): Future[AgentCode] = {
    monitor("ConsumedAPI-GGW-GsoAdminGetUserDetails-POST") {
      httpPost.POSTString(path("GsoAdminGetUserDetails"), GsoAdminGetUserDetailsXmlInput(credentialIdentifier), Seq(CONTENT_TYPE -> XML))
    }.map({ response =>
      val xml = toXmlElement(response.body)
      AgentCode((xml \\ "AgentCode").head.text)
    })
  }

  def getAllocatedAgentCodes(mtditid: String)(implicit hc: HeaderCarrier): Future[Set[AgentCode]] = {
    monitor("ConsumedAPI-GGW-GsoAdminGetAssignedAgents-POST") {
      httpPost.POSTString(path("GsoAdminGetAssignedAgents"), GsoAdminGetAssignedAgentsXmlInput(mtditid), Seq(CONTENT_TYPE -> XML))
    }.map({ response =>
      val xml: Elem = toXmlElement(response.body)
      val agentDetails = xml \ "AllocatedAgents" \ "AgentDetails"
      agentDetails.map(agency => (agency \ "AgentCode").text).filterNot(_.isEmpty).map(AgentCode(_)).toSet
    })
  }

  private def toXmlElement(xmlString: String): Elem = {
    val factory = SAXParserFactory.newInstance("org.apache.xerces.jaxp.SAXParserFactoryImpl", this.getClass.getClassLoader)
    factory.setFeature(Constants.SAX_FEATURE_PREFIX + Constants.EXTERNAL_GENERAL_ENTITIES_FEATURE, false)
    factory.setFeature(Constants.SAX_FEATURE_PREFIX + Constants.EXTERNAL_PARAMETER_ENTITIES_FEATURE, false)
    factory.setFeature(Constants.XERCES_FEATURE_PREFIX + Constants.DISALLOW_DOCTYPE_DECL_FEATURE, true)
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
    val XML = scala.xml.XML.withSAXParser(factory.newSAXParser())

    XML.loadString(xmlString)
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
    <GsoAdminGetUserDetailsXmlInput xmlns="urn:GSO-SystemServices:external:2.13.3:GsoAdminGetUserDetailsXmlInput">
      <DelegatedAccessIdentifier>HMRC</DelegatedAccessIdentifier>
      <CredentialIdentifier>{credentialIdentifier}</CredentialIdentifier>
    </GsoAdminGetUserDetailsXmlInput>.toString()

  private def GsoAdminGetAssignedAgentsXmlInput(mtditid: String):String =
    <GsoAdminGetAssignedAgentsXmlInput xmlns="urn:GSO-System-Services:external:2.13.3:GsoAdminGetAssignedAgentsXmlInput">
      <DelegatedAccessIdentifier>HMRC</DelegatedAccessIdentifier>
      <ServiceName>HMRC-MTD-IT</ServiceName>
      <Identifiers>
        <Identifier IdentifierType="MTDITID">{mtditid}</Identifier>
      </Identifiers>
    </GsoAdminGetAssignedAgentsXmlInput>.toString()
}


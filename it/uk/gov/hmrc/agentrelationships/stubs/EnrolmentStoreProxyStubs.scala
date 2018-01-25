package uk.gov.hmrc.agentrelationships.stubs

import com.github.tomakehurst.wiremock.client.WireMock._
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}

trait EnrolmentStoreProxyStubs {

  private val esBaseUrl = s"/enrolment-store-proxy/enrolment-store/enrolments/"

  private def es0GetAssignedEnrolmentUsers(clientId: String, service: String, serviceIdentifier: String, groupType: String) = {
    post(urlPathEqualTo(s"$esBaseUrl$service~$serviceIdentifier~$clientId/groups?type=$groupType"))
      .withRequestBody(matching(s"$clientId"))
      .withHeader("Content-Type", equalTo("application/json"))
  }

  private def es1GetAllocatedEnrolmentUsers(clientId: String, service: String, serviceIdentifier: String) = {
    post(urlPathEqualTo(s"$esBaseUrl$service~$serviceIdentifier~$clientId/groups"))
      .withRequestBody(matching(s"$clientId"))
      .withHeader("Content-Type", equalTo("application/json"))
  }

  private def getCredentialsForAgent(arn: Arn) = es0GetAssignedEnrolmentUsers(arn.value, "HMRC-AS-AGENT", "AgentReferenceNumber", "principal")

  private def getCredentialsForClient(mtdItId: MtdItId) = es0GetAssignedEnrolmentUsers(mtdItId.value, "HMRC-MTD-IT", "MTDITID", "delegated")


  def givenAgentCredentialsAreFoundFor(arn: Arn, credentialIdentifier: String): EnrolmentStoreProxyStubs = {
    stubFor(getCredentialsForAgent(arn)
      .willReturn(aResponse()
        .withBody(
          s"""
             |{
             |  "principalUserIds":[],
             |  "delegatedUserIds":[
             |  "F30B23E3-B664-46D2-91B9-012F6EE164DC"
             |  ]
             |
             |}
           """.stripMargin)))
    this
  }

  def givenAgentCredentialsAreNotFoundFor(arn: Arn): EnrolmentStoreProxyStubs = {
    stubFor(getCredentialsForAgent(arn)
      .willReturn(aResponse()
        .withBody(
          s"""
             |{
             |  "principalUserIds":[],
             |  "delegatedUserIds":[]
             |}
           """.stripMargin)))
    this
  }

  private def getAgentCodeFor(credentialIdentifier: String) = {
    post(urlPathEqualTo("/government-gateway-proxy/api/admin/GsoAdminGetUserDetails"))
      .withRequestBody(matching(s".*>$credentialIdentifier<.*"))
      .withHeader("Content-Type", equalTo("application/xml; charset=utf-8"))
  }

  def givenAgentCodeIsFoundFor(credentialIdentifier: String, agentCode: String): EnrolmentStoreProxyStubs = {
    stubFor(getAgentCodeFor(credentialIdentifier)
      .willReturn(aResponse()
        .withBody(
          s"""
             |<GsoAdminGetUserDetailsXmlOutput xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" RequestID="650EC864CCD14B3EAB866F7876250D28" xmlns="urn:GSO-SystemServices:external:2.14.4:GsoAdminGetUserDetailsXmlOutput">
             |  <CredentialName>Some Agency</CredentialName>
             |  <Description />
             |  <EmailAddress />
             |  <RegistrationCategory>Agent</RegistrationCategory>
             |  <CredentialRole>User</CredentialRole>
             |  <AgentDetails>
             |    <AgentCode>$agentCode</AgentCode>
             |    <AgentFriendlyName>Friendly Name</AgentFriendlyName>
             |  </AgentDetails>
             |</GsoAdminGetUserDetailsXmlOutput>
             |""".stripMargin)))
    this
  }

  def givenAgentCodeIsNotInTheResponseFor(credentialIdentifier: String): EnrolmentStoreProxyStubs = {
    stubFor(getAgentCodeFor(credentialIdentifier)
      .willReturn(aResponse()
        .withBody(
          s"""
             |<GsoAdminGetUserDetailsXmlOutput xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" RequestID="650EC864CCD14B3EAB866F7876250D28" xmlns="urn:GSO-SystemServices:external:2.14.4:GsoAdminGetUserDetailsXmlOutput">
             |</GsoAdminGetUserDetailsXmlOutput>
             |""".stripMargin)))
    this
  }

  private def getAssignedAgentsFor(identifier: String) = {
    post(urlEqualTo("/government-gateway-proxy/api/admin/GsoAdminGetAssignedAgents"))
      .withRequestBody(matching(s".*>$identifier<.*"))
      .withHeader("Content-Type", equalTo("application/xml; charset=utf-8"))
  }

  def givenAgentIsAllocatedAndAssignedToClient(identifier: String, agentCode: String): EnrolmentStoreProxyStubs = {
    stubFor(getAssignedAgentsFor(identifier)
      .willReturn(aResponse()
        .withBody(
          s"""
             |<GsoAdminGetAssignedAgentsXmlOutput RequestID="E665D904F81C4AC89AAB34B562A98966" xmlns="urn:GSO-System-Services:external:2.13.3:GsoAdminGetAssignedAgentsXmlOutput" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
             |	<AllocatedAgents>
             |		<AgentDetails>
             |			<AgentId>GGWCESAtests</AgentId>
             |			<AgentCode>$agentCode</AgentCode>
             |			<AgentFriendlyName>GGWCESA tests</AgentFriendlyName>
             |			<AssignedCredentials>
             |				<Credential>
             |					<CredentialName>GGWCESA tests</CredentialName>
             |					<CredentialIdentifier>89741987654312</CredentialIdentifier>
             |					<Role>User</Role>
             |				</Credential>
             |				<Credential>
             |					<CredentialName>GGWCESA tests1</CredentialName>
             |					<CredentialIdentifier>98741987654321</CredentialIdentifier>
             |					<Role>User</Role>
             |				</Credential>
             |			</AssignedCredentials>
             |		</AgentDetails>
             |		<AgentDetails>
             |			<AgentId>GGWCESAtests1</AgentId>
             |			<AgentCode>123ABCD12345</AgentCode>
             |			<AgentFriendlyName>GGWCESA test1</AgentFriendlyName>
             |			<AssignedCredentials>
             |				<Credential>
             |					<CredentialName>GGWCESA test1</CredentialName>
             |					<CredentialIdentifier>98741987654322</CredentialIdentifier>
             |					<Role>User</Role>
             |				</Credential>
             |			</AssignedCredentials>
             |		</AgentDetails>
             |	</AllocatedAgents>
             |</GsoAdminGetAssignedAgentsXmlOutput>
                 """.stripMargin)))
    this
  }

  def givenAgentIsAllocatedButNotAssignedToClient(identifier: String): EnrolmentStoreProxyStubs = {
    stubFor(getAssignedAgentsFor(identifier)
      .willReturn(aResponse()
        .withBody(
          s"""
             |<GsoAdminGetAssignedAgentsXmlOutput RequestID="E665D904F81C4AC89AAB34B562A98966" xmlns="urn:GSO-System-Services:external:2.13.3:GsoAdminGetAssignedAgentsXmlOutput" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
             |	<AllocatedAgents>
             |		<AgentDetails>
             |			<AgentId>GGWCESAtests</AgentId>
             |			<AgentCode>other</AgentCode>
             |			<AgentFriendlyName>GGWCESA tests</AgentFriendlyName>
             |			<AssignedCredentials>
             |				<Credential>
             |					<CredentialName>GGWCESA tests</CredentialName>
             |					<CredentialIdentifier>98741987654323</CredentialIdentifier>
             |					<Role>User</Role>
             |				</Credential>
             |				<Credential>
             |					<CredentialName>GGWCESA tests1</CredentialName>
             |					<CredentialIdentifier>98741987654324</CredentialIdentifier>
             |					<Role>User</Role>
             |				</Credential>
             |			</AssignedCredentials>
             |		</AgentDetails>
             |		<AgentDetails>
             |			<AgentId>GGWCESAtests1</AgentId>
             |			<AgentCode>123ABCD12345</AgentCode>
             |			<AgentFriendlyName>GGWCESA test1</AgentFriendlyName>
             |			<AssignedCredentials>
             |				<Credential>
             |					<CredentialName>GGWCESA test1</CredentialName>
             |					<CredentialIdentifier>98741987654325</CredentialIdentifier>
             |					<Role>User</Role>
             |				</Credential>
             |			</AssignedCredentials>
             |		</AgentDetails>
             |	</AllocatedAgents>
             |</GsoAdminGetAssignedAgentsXmlOutput>
                 """.stripMargin)))
    this
  }

  def givenAgentIsNotAllocatedToClient(mtdItId: String): EnrolmentStoreProxyStubs = {
    stubFor(getAssignedAgentsFor(mtdItId)
      .willReturn(aResponse()
        .withBody(
          s"""
             |<GsoAdminGetAssignedAgentsXmlOutput RequestID="E080C4891B8F4717A2788DA540AAC7A5" xmlns="urn:GSO-System-Services:external:2.13.3:GsoAdminGetAssignedAgentsXmlOutput" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
             | <AllocatedAgents/>
             |</GsoAdminGetAssignedAgentsXmlOutput>
          """.stripMargin)))
    this
  }

  private def allocateAgentForClient(mtdItId: String, agentCode: String) = {
    post(urlEqualTo("/government-gateway-proxy/api/admin/GsoAdminAllocateAgent"))
      .withRequestBody(containing("GsoAdminAllocateAgentXmlInput"))
      .withRequestBody(matching(s".*>$mtdItId<.*"))
      .withRequestBody(matching(s".*<AgentCode>$agentCode</AgentCode>.*"))
      .withHeader("Content-Type", equalTo("application/xml; charset=utf-8"))
  }

  def givenAgentCanBeAllocatedInGovernmentGateway(mtdItId: String, agentCode: String): EnrolmentStoreProxyStubs = {
    stubFor(allocateAgentForClient(mtdItId, agentCode)
      .willReturn(aResponse()
        .withBody(
          s"""
             |<GsoAdminAllocateAgentXmlOutput xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" RequestID="00598B2790D24E718F810004E8E95AA5" xmlns="urn:GSO-System- Services:external:1.65:GsoAdminAllocateAgentXmlOutput">
             |<PrincipalEnrolmentAlreadyExisted>true</PrincipalEnrolmentAlreadyExisted>
             |</GsoAdminAllocateAgentXmlOutput>
           """.stripMargin)))
    this
  }

  def givenAgentCannotBeAllocatedInGovernmentGateway(mtdItId: String, agentCode: String): EnrolmentStoreProxyStubs = {
    stubFor(allocateAgentForClient(mtdItId, agentCode)
      .willReturn(aResponse().withStatus(404)))
    this
  }

  private def deallocateAgentForClient(mtdItId: String, agentCode: String) = {
    post(urlEqualTo("/government-gateway-proxy/api/admin/GsoAdminDeallocateAgent"))
      .withRequestBody(containing("GsoAdminDeallocateAgentXmlInput"))
      .withRequestBody(matching(s".*>$mtdItId<.*"))
      .withRequestBody(matching(s".*<AgentCode>$agentCode</AgentCode>.*"))
      .withHeader("Content-Type", equalTo("application/xml; charset=utf-8"))
  }

  def givenAgentCanBeDeallocatedInGovernmentGateway(mtdItId: String, agentCode: String): EnrolmentStoreProxyStubs = {
    stubFor(deallocateAgentForClient(mtdItId, agentCode)
      .willReturn(aResponse()
        .withBody(
          s"""<GsoAdminDeallocateAgentXmlOutput xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" RequestID="00598B2790D24E718F810004E8E95AA5" xmlns="urn:GSO-System- Services:external:1.65:GsoAdminDeallocateAgentXmlInput">
             |</GsoAdminDeallocateAgentXmlOutput>""".stripMargin)))
    this
  }

  def givenAgentCannotBeDeallocatedInGovernmentGateway(mtdItId: String, agentCode: String): EnrolmentStoreProxyStubs = {
    stubFor(deallocateAgentForClient(mtdItId, agentCode)
      .willReturn(aResponse().withStatus(404)))
    this
  }

  def whenGetAssignedAgentsReturns(status: Int): EnrolmentStoreProxyStubs = {
    stubFor(post(urlEqualTo("/government-gateway-proxy/api/admin/GsoAdminGetAssignedAgents"))
      .willReturn(aResponse().withStatus(status)))
    this
  }

  def whenGetCredentialsReturns(status: Int): EnrolmentStoreProxyStubs = {
    stubFor(post(urlPathEqualTo("/government-gateway-proxy/api/admin/GsoAdminGetCredentialsForDirectEnrolments"))
      .willReturn(aResponse().withStatus(status)))
    this
  }

  def whenGetUserDetailReturns(status: Int): EnrolmentStoreProxyStubs = {
    stubFor(post(urlPathEqualTo("/government-gateway-proxy/api/admin/GsoAdminGetUserDetails"))
      .willReturn(aResponse().withStatus(status)))
    this
  }

  def givenGgIsUnavailable(): EnrolmentStoreProxyStubs = {
    stubFor(any(urlMatching("/government-gateway-proxy/.*"))
      .willReturn(aResponse().withStatus(503)))
    this
  }
}

package uk.gov.hmrc.agentrelationships.stubs

import com.github.tomakehurst.wiremock.client.WireMock._
import uk.gov.hmrc.agentmtdidentifiers.model.Arn

trait GovernmentGatewayProxyStubs {

  private def getCredentialsFor(arn: Arn) = {
    post(urlPathEqualTo("/government-gateway-proxy/api/admin/GsoAdminGetCredentialsForDirectEnrolments"))
      .withRequestBody(matching(s".*>${arn.value}<.*"))
      .withHeader("Content-Type", equalTo("application/xml; charset=utf-8"))
  }

  def givenAgentCredentialsAreFoundFor(arn: Arn, credentialIdentifier: String): GovernmentGatewayProxyStubs = {
    stubFor(getCredentialsFor(arn)
      .willReturn(aResponse()
        .withBody(
          s"""
             |<GsoAdminGetCredentialsForDirectEnrolmentsXmlOutput xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema" RequestID="6D261BCBD2374B6593B89357E162B15F" xmlns="urn:GSO-System-" Services:external:1.67:GsoAdminGetCredentialsForDirectEnrolmentsXmlOutput="">
             |  <ServiceName>HMRC-AS-AGENT</ServiceName>
             |  <CredentialAndIdentifiersSets>
             |    <CredentialAndIdentifiersSet>
             |      <Identifiers>
             |        <Identifier IdentifierType="AgentReferenceNumber">${arn.value}</Identifier>
             |      </Identifiers>
             |      <CredentialIdentifiers>
             |        <CredentialIdentifier>$credentialIdentifier</CredentialIdentifier>
             |      </CredentialIdentifiers>
             |    </CredentialAndIdentifiersSet>
             |  </CredentialAndIdentifiersSets>
             |</GsoAdminGetCredentialsForDirectEnrolmentsXmlOutput>""".stripMargin)))
    this
  }

  def givenAgentCredentialsAreNotFoundFor(arn: Arn): GovernmentGatewayProxyStubs = {
    stubFor(getCredentialsFor(arn)
      .willReturn(aResponse()
        .withBody(
          s"""
             |<GsoAdminGetCredentialsForDirectEnrolmentsXmlOutput xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema" RequestID="6D261BCBD2374B6593B89357E162B15F" xmlns="urn:GSO-System-" Services:external:1.67:GsoAdminGetCredentialsForDirectEnrolmentsXmlOutput="">
             |  <ServiceName>HMRC-AS-AGENT</ServiceName>
             |  <CredentialAndIdentifiersSets>
             |    <CredentialAndIdentifiersSet>
             |      <Identifiers>
             |        <Identifier IdentifierType="AgentReferenceNumber">${arn.value}</Identifier>
             |      </Identifiers>
             |      <CredentialIdentifiers>
             |      </CredentialIdentifiers>
             |    </CredentialAndIdentifiersSet>
             |  </CredentialAndIdentifiersSets>
             |</GsoAdminGetCredentialsForDirectEnrolmentsXmlOutput>
             |""".stripMargin)))
    this
  }

  private def getAgentCodeFor(credentialIdentifier: String) = {
    post(urlPathEqualTo("/government-gateway-proxy/api/admin/GsoAdminGetUserDetails"))
      .withRequestBody(matching(s".*>$credentialIdentifier<.*"))
      .withHeader("Content-Type", equalTo("application/xml; charset=utf-8"))
  }

  def givenAgentCodeIsFoundFor(credentialIdentifier: String, agentCode: String): GovernmentGatewayProxyStubs = {
    stubFor(getAgentCodeFor(credentialIdentifier)
      .willReturn(aResponse()
        .withBody(
          s"""
             |<GsoAdminGetUserDetailsXmlOutput xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" RequestID="650EC864CCD14B3EAB866F7876250D28" xmlns="urn:GSO-SystemServices:external:2.13.3:GsoAdminGetUserDetailsXmlOutput">
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

  def givenAgentCodeIsNotInTheResponseFor(credentialIdentifier: String): GovernmentGatewayProxyStubs = {
    stubFor(getAgentCodeFor(credentialIdentifier)
      .willReturn(aResponse()
        .withBody(
          s"""
             |<GsoAdminGetUserDetailsXmlOutput xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" RequestID="650EC864CCD14B3EAB866F7876250D28" xmlns="urn:GSO-SystemServices:external:2.13.3:GsoAdminGetUserDetailsXmlOutput">
             |  <CredentialName>Some Agency</CredentialName>
             |  <Description />
             |  <EmailAddress />
             |  <RegistrationCategory>Individual</RegistrationCategory>
             |  <CredentialRole>User</CredentialRole>
             |</GsoAdminGetUserDetailsXmlOutput>
             |""".stripMargin)))
    this
  }

  private def getAssignedAgentsFor(mtdItId: String) = {
    post(urlEqualTo("/government-gateway-proxy/api/admin/GsoAdminGetAssignedAgents"))
      .withRequestBody(matching(s".*>$mtdItId<.*"))
      .withHeader("Content-Type", equalTo("application/xml; charset=utf-8"))
  }

  def givenAgentIsAllocatedAndAssignedToClient(mtdItId: String, agentCode: String): GovernmentGatewayProxyStubs = {
    stubFor(getAssignedAgentsFor(mtdItId)
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

  def givenAgentIsAllocatedButNotAssignedToClient(mtdItId: String): GovernmentGatewayProxyStubs = {
    stubFor(getAssignedAgentsFor(mtdItId)
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

  def givenAgentIsNotAllocatedToClient(mtdItId: String): GovernmentGatewayProxyStubs = {
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


  def whenGetAssignedAgentsReturns(status: Int): GovernmentGatewayProxyStubs = {
    stubFor(post(urlEqualTo("/government-gateway-proxy/api/admin/GsoAdminGetAssignedAgents"))
      .willReturn(aResponse().withStatus(status)))
    this
  }

  def whenGetCredentialsReturns(status: Int): GovernmentGatewayProxyStubs = {
    stubFor(post(urlPathEqualTo("/government-gateway-proxy/api/admin/GsoAdminGetCredentialsForDirectEnrolments"))
      .willReturn(aResponse().withStatus(status)))
    this
  }

  def whenGetUserDetailReturns(status: Int): GovernmentGatewayProxyStubs = {
    stubFor(post(urlPathEqualTo("/government-gateway-proxy/api/admin/GsoAdminGetUserDetails"))
      .willReturn(aResponse().withStatus(status)))
    this
  }

}

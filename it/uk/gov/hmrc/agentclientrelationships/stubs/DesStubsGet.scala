package uk.gov.hmrc.agentclientrelationships.stubs

import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import uk.gov.hmrc.agentclientrelationships.support.WireMockSupport
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.domain.TaxIdentifier

trait DesStubsGet {

  me: WireMockSupport =>

  // Via Client


  private val agentRecordUrl: TaxIdentifier => String = {
    case Arn(arn) => s"/registration/personal-details/arn/$arn"
    case Utr(utr) => s"/registration/personal-details/utr/$utr"
  }

  def getAgentRecordForClient(taxIdentifier: TaxIdentifier): StubMapping = {
    stubFor(get(urlEqualTo(agentRecordUrl(taxIdentifier)))
      .willReturn(aResponse()
      .withStatus(200)
        .withBody(
          s"""
             | {
             |  "suspensionDetails" : {
             |    "suspensionStatus": false,
             |    "regimes": []
             |  }
             | }
             |""".stripMargin)
      )
    )
  }

  def getSuspendedAgentRecordForClient(taxIdentifier: TaxIdentifier): StubMapping = {
    stubFor(get(urlEqualTo(agentRecordUrl(taxIdentifier)))
      .willReturn(aResponse()
        .withStatus(200)
        .withBody(
          s"""
             | {
             |  "suspensionDetails" : {
             |    "suspensionStatus": true,
             |    "regimes": ["ITSA"]
             |  }
             | }
             |""".stripMargin)
      )
    )
  }



  def getVrnIsKnownInETMPFor(vrn: Vrn): StubMapping =
    stubFor(
      get(urlEqualTo(s"/vat/customer/vrn/${vrn.value}/information"))
      .willReturn(aResponse().withBody(s"""{ "vrn": "${vrn.value}"}""").withStatus(200)))

  def getVrnIsNotKnownInETMPFor(vrn: Vrn): StubMapping =
    stubFor(
      get(urlEqualTo(s"/vat/customer/vrn/${vrn.value}/information"))
        .willReturn(aResponse().withBody(s"""{}""").withStatus(200)))

  def givenDESRespondsWithStatusForVrn(vrn: Vrn, status: Int): StubMapping =
    stubFor(
      get(urlEqualTo(s"/vat/customer/vrn/${vrn.value}/information"))
        .willReturn(aResponse().withStatus(status)))

}

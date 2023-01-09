package uk.gov.hmrc.agentclientrelationships.stubs

import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, get, stubFor, urlEqualTo}
import uk.gov.hmrc.agentclientrelationships.support.WireMockSupport
import uk.gov.hmrc.agentmtdidentifiers.model.Arn

trait AUCDStubs {

  me: WireMockSupport =>

  def givenCacheRefresh(arn: Arn, response: Int = 204) =
    stubFor(
      get(urlEqualTo(s"/agent-user-client-details/arn/${arn.value}/cache-refresh"))
        .willReturn(aResponse()
          .withStatus(response)))

}

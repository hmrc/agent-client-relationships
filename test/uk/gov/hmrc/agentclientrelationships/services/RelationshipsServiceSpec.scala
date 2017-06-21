package uk.gov.hmrc.agentclientrelationships.services

import org.scalatest.mock.MockitoSugar
import org.mockito.Mockito.{verify, when}

import uk.gov.hmrc.agentclientrelationships.connectors.{DesConnector, GovernmentGatewayProxyConnector, MappingConnector}
import uk.gov.hmrc.agentclientrelationships.repository.RelationshipCopyRecordRepository
import uk.gov.hmrc.play.http.HeaderCarrier
import uk.gov.hmrc.play.test.UnitSpec

class RelationshipsServiceSpec extends UnitSpec with MockitoSugar {

  private val ggConnector = mock[GovernmentGatewayProxyConnector]
  private val desConector = mock[DesConnector]
  private val mappingConnector = mock[MappingConnector]
  private val repository = mock[RelationshipCopyRecordRepository]

  private val service =
    new RelationshipsService(ggConnector, desConector, mappingConnector, repository)

  private val hc = HeaderCarrier()

  "RelationshipsService" should {
    "check for old relationships" in {


    }
  }





}

/*
 * Copyright 2024 HM Revenue & Customs
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

package uk.gov.hmrc.agentclientrelationships.controllers
import com.google.inject.AbstractModule
import org.scalatest.concurrent.IntegrationPatience
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.ws.WSClient
import play.api.test.Helpers._
import play.utils.UriEncoding
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.repository.{DeleteRecord, MongoDeleteRecordRepository, MongoRelationshipCopyRecordRepository, SyncStatus}
import uk.gov.hmrc.agentclientrelationships.services.MongoRecoveryLockService
import uk.gov.hmrc.agentclientrelationships.stubs._
import uk.gov.hmrc.agentclientrelationships.support._
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.auth.core.{AuthConnector, PlayAuthConnector}
import uk.gov.hmrc.domain.{Nino, TaxIdentifier}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.lock.MongoLockRepository
import uk.gov.hmrc.mongo.test.MongoSupport
import uk.gov.hmrc.mongo.{CurrentTimestampSupport, MongoComponent}

trait RelationshipsBaseControllerISpec
    extends UnitSpec
    with MongoSupport
    with GuiceOneServerPerSuite
    with WireMockSupport
    with RelationshipStubs
    with DesStubs
    with IFStubs
    with DesStubsGet
    with MappingStubs
    with DataStreamStub
    with AuthStub
    with MockitoSugar
    with JsonMatchers
    with ACAStubs
    with AUCDStubs
    with IntegrationPatience {

  lazy val mockAuthConnector: AuthConnector = mock[PlayAuthConnector]
  override implicit lazy val app: Application = appBuilder
    .build()

  import scala.concurrent.ExecutionContext.Implicits.global

  val additionalConfig: Map[String, Any] = Map.empty

  val mongoRecoveryLockService = new MongoRecoveryLockService(mongoLockRepository)
  def mongoLockRepository = new MongoLockRepository(mongoComponent, new CurrentTimestampSupport)

  val moduleWithOverrides = new AbstractModule {
    override def configure(): Unit = {
      bind(classOf[MongoComponent]).toInstance(mongoComponent)
      bind(classOf[MongoRecoveryLockService]).toInstance(mongoRecoveryLockService)
    }
  }

  protected def appBuilder: GuiceApplicationBuilder =
    new GuiceApplicationBuilder()
      .configure(
        "microservice.services.enrolment-store-proxy.port"      -> wireMockPort,
        "microservice.services.tax-enrolments.port"             -> wireMockPort,
        "microservice.services.users-groups-search.port"        -> wireMockPort,
        "microservice.services.des.port"                        -> wireMockPort,
        "microservice.services.if.port"                         -> wireMockPort,
        "microservice.services.auth.port"                       -> wireMockPort,
        "microservice.services.agent-mapping.port"              -> wireMockPort,
        "microservice.services.agent-client-authorisation.port" -> wireMockPort,
        "microservice.services.agent-permissions.port"          -> wireMockPort,
        "auditing.consumer.baseUri.host"                        -> wireMockHost,
        "auditing.consumer.baseUri.port"                        -> wireMockPort,
        "microservice.services.agent-user-client-details.port"  -> wireMockPort,
        "features.copy-relationship.mtd-it"                     -> true,
        "features.copy-relationship.mtd-vat"                    -> true,
        "features.recovery-enable"                              -> false,
        "agent.cache.expires"                                   -> "1 millis",
        "agent.cache.enabled"                                   -> true,
        "agent.trackPage.cache.expires"                         -> "1 millis",
        "agent.trackPage.cache.enabled"                         -> true,
        "alt-itsa.enabled"                                      -> true,
        "mongodb.uri"                                           -> mongoUri
      )
      .overrides(moduleWithOverrides)
      .configure(additionalConfig)

  implicit lazy val ws: WSClient = app.injector.instanceOf[WSClient]
  implicit val hc: HeaderCarrier = HeaderCarrier()

  def repo: MongoRelationshipCopyRecordRepository = new MongoRelationshipCopyRecordRepository(mongoComponent)
  def deleteRecordRepository: MongoDeleteRecordRepository = new MongoDeleteRecordRepository(mongoComponent)

  override def beforeEach() = {
    super.beforeEach()
    givenAuditConnector()
    await(mongoComponent.database.drop().toFuture())
    ()
  }

  val arn = Arn("AARN0000002")
  val arnEncoded = UriEncoding.encodePathSegment(arn.value, "UTF-8")
  val arn2 = Arn("AARN0000004")
  val arn3 = Arn("AARN0000006")
  val mtdItId = MtdItId("ABCDEF123456789")
  val mtdItEnrolmentKey: EnrolmentKey = EnrolmentKey(Service.MtdIt, mtdItId)
  val mtdItSuppEnrolmentKey: EnrolmentKey = EnrolmentKey(Service.MtdItSupp, mtdItId)
  val mtdItIdUriEncoded: String = UriEncoding.encodePathSegment(mtdItId.value, "UTF-8")
  val vrn = Vrn("101747641")
  val vatEnrolmentKey: EnrolmentKey = EnrolmentKey(Service.Vat, vrn)
  val vrnUriEncoded: String = UriEncoding.encodePathSegment(vrn.value, "UTF-8")
  val nino = Nino("AB123456C")
  val mtdItIdType = "MTDITID"
  val mtdVatIdType = "VRN"
  val oldAgentCode = "oldAgentCode"
  val testAgentUser = "testAgentUser"
  val testAgentGroup = "testAgentGroup"
  val STRIDE_ROLE = "maintain agent relationships"
  val NEW_STRIDE_ROLE = "maintain_agent_relationships"
  val TERMINATION_STRIDE_ROLE = "caat"

  val utr = Utr("3087612352")
  val urn = Urn("XXTRUST12345678")
  val utrUriEncoded: String = UriEncoding.encodePathSegment(utr.value, "UTF-8")
  val saUtrType = "SAUTR"
  val urnType = "URN"

  val cgtRef = CgtRef("XMCGTP123456789")

  val pptRef = PptRef("XAPPT0004567890")
  val cbcId = CbcId("XACBC1234567890")
  val plrId = PlrId("XAPLR2222222222")
  val otherTaxIdentifier: TaxIdentifier => TaxIdentifier = {
    case MtdItId(_) => MtdItId("ABCDE1234567890")
    case Vrn(_)     => Vrn("101747641")
    case Utr(_)     => Utr("2134514321")
    case Urn(_)     => Urn("XXTRUST12345678")
    case CgtRef(_)  => cgtRef
    case PptRef(_)  => pptRef
    case PlrId(_)   => plrId
    case x          => throw new IllegalArgumentException(s"Tax identifier not supported $x")
  }

  protected def doAgentGetRequest(route: String) = new Resource(route, port).get()

  protected def doAgentPutRequest(route: String) = Http.putEmpty(s"http://localhost:$port$route")

  protected def doAgentDeleteRequest(route: String) = Http.delete(s"http://localhost:$port$route")

  protected def verifyDeleteRecordHasStatuses(
    etmpStatus: Option[SyncStatus.Value],
    esStatus: Option[SyncStatus.Value]
  ) =
    await(deleteRecordRepository.findBy(arn, mtdItEnrolmentKey)) should matchPattern {
      case Some(DeleteRecord(arn.value, Some(ek), _, _, _, `etmpStatus`, `esStatus`, _, _, _, _))
          if ek == EnrolmentKey(Service.MtdIt, MtdItId("ABCDEF123456789")) =>
    }

  protected def verifyDeleteRecordNotExists =
    await(deleteRecordRepository.findBy(arn, mtdItEnrolmentKey)) shouldBe None

}

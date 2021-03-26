package uk.gov.hmrc.agentrelationships.controllers
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.ws.WSClient
import play.utils.UriEncoding
import uk.gov.hmrc.agentclientrelationships.repository.{DeleteRecord, MongoDeleteRecordRepository, MongoRelationshipCopyRecordRepository, SyncStatus}
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.agentrelationships.stubs._
import uk.gov.hmrc.agentrelationships.support._
import uk.gov.hmrc.auth.core.{AuthConnector, PlayAuthConnector}
import uk.gov.hmrc.domain.{Nino, TaxIdentifier}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Random

trait RelationshipsBaseControllerISpec
    extends UnitSpec
    with MongoApp
    with GuiceOneServerPerSuite
    with WireMockSupport
    with RelationshipStubs
    with DesStubs
    with DesStubsGet
    with MappingStubs
    with DataStreamStub
    with AuthStub
    with MockitoSugar
    with JsonMatchers {

  override lazy val port: Int = Random.nextInt(1000) + 19000

  lazy val mockAuthConnector: AuthConnector = mock[PlayAuthConnector]
  override implicit lazy val app: Application = appBuilder
    .build()

  val additionalConfig: Map[String, Any] = Map("des-if.enabled" -> false)

  protected def appBuilder: GuiceApplicationBuilder =
    new GuiceApplicationBuilder()
      .configure(
        "microservice.services.enrolment-store-proxy.port" -> wireMockPort,
        "microservice.services.tax-enrolments.port"        -> wireMockPort,
        "microservice.services.users-groups-search.port"   -> wireMockPort,
        "microservice.services.des.port"                   -> wireMockPort,
        "microservice.services.if.port"                    -> wireMockPort,
        "microservice.services.auth.port"                  -> wireMockPort,
        "microservice.services.agent-mapping.port"         -> wireMockPort,
        "auditing.consumer.baseUri.host"                   -> wireMockHost,
        "auditing.consumer.baseUri.port"                   -> wireMockPort,
        "features.copy-relationship.mtd-it"                -> true,
        "features.copy-relationship.mtd-vat"               -> true,
        "features.recovery-enable"                         -> false,
        "agent.cache.size"                                 -> 1,
        "agent.cache.expires"                              -> "1 millis",
        "agent.cache.enabled"                              -> true,
        "agent.trackPage.cache.size"                                 -> 1,
        "agent.trackPage.cache.expires"                              -> "1 millis",
        "agent.trackPage.cache.enabled"                              -> true
      )
      .configure(mongoConfiguration ++ additionalConfig)

  implicit lazy val ws: WSClient = app.injector.instanceOf[WSClient]
  implicit val hc: HeaderCarrier = HeaderCarrier()

  def repo: MongoRelationshipCopyRecordRepository = app.injector.instanceOf[MongoRelationshipCopyRecordRepository]
  def deleteRecordRepository: MongoDeleteRecordRepository = app.injector.instanceOf[MongoDeleteRecordRepository]

  override def beforeEach() {
    super.beforeEach()
    givenAuditConnector()
    await(repo.ensureIndexes)
    await(deleteRecordRepository.ensureIndexes)
    ()
  }

  val HMRCMTDIT = "HMRC-MTD-IT"

  val HMRCPIR = "PERSONAL-INCOME-RECORD"

  val HMRCMTDVAT = "HMRC-MTD-VAT"

  val HMRCTERSORG = "HMRC-TERS-ORG"

  val HMRCCGTPD = "HMRC-CGT-PD"

  val arn = Arn("AARN0000002")
  val arnEncoded = UriEncoding.encodePathSegment(arn.value, "UTF-8")
  val arn2 = Arn("AARN0000004")
  val arn3 = Arn("AARN0000006")
  val mtdItId = MtdItId("ABCDEF123456789")
  val mtdItIdUriEncoded: String = UriEncoding.encodePathSegment(mtdItId.value, "UTF-8")
  val vrn = Vrn("101747641")
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

  val otherTaxIdentifier: TaxIdentifier => TaxIdentifier = {
    case MtdItId(_) => MtdItId("ABCDE1234567890")
    case Vrn(_) => Vrn("101747641")
    case Utr(_) => Utr("2134514321")
    case CgtRef(_) => cgtRef
  }

  protected def doAgentGetRequest(route: String) = new Resource(route, port).get()

  protected def doAgentPutRequest(route: String) = Http.putEmpty(s"http://localhost:$port$route")

  protected def doAgentDeleteRequest(route: String) = Http.delete(s"http://localhost:$port$route")

  protected def verifyDeleteRecordHasStatuses(
                                               etmpStatus: Option[SyncStatus.Value],
                                               esStatus: Option[SyncStatus.Value]) =
    await(deleteRecordRepository.findBy(arn, mtdItId)) should matchPattern {
      case Some(DeleteRecord(arn.value, mtdItId.value, `mtdItIdType`, _, `etmpStatus`, `esStatus`, _, _, _)) =>
    }

  protected def verifyDeleteRecordNotExists =
    await(deleteRecordRepository.findBy(arn, mtdItId)) shouldBe None


}

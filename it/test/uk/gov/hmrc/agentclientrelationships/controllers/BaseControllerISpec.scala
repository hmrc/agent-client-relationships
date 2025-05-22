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
import play.api.libs.json.JsValue
import play.api.libs.ws.WSClient
import play.api.mvc.RequestHeader
import play.api.test.FakeRequest
import play.api.test.Helpers._
import play.utils.UriEncoding
import uk.gov.hmrc.agentclientrelationships.model.{EnrolmentKey => LocalEnrolmentKey}
import uk.gov.hmrc.agentclientrelationships.repository.DeleteRecord
import uk.gov.hmrc.agentclientrelationships.repository.MongoDeleteRecordRepository
import uk.gov.hmrc.agentclientrelationships.repository.MongoRelationshipCopyRecordRepository
import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus
import uk.gov.hmrc.agentclientrelationships.services.MongoLockService
import uk.gov.hmrc.agentclientrelationships.services.MongoLockServiceImpl
import uk.gov.hmrc.agentclientrelationships.stubs._
import uk.gov.hmrc.agentclientrelationships.support._
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.auth.core.PlayAuthConnector
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.domain.TaxIdentifier
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.mongo.lock.MongoLockRepository
import uk.gov.hmrc.mongo.test.MongoSupport
import uk.gov.hmrc.mongo.CurrentTimestampSupport
import uk.gov.hmrc.mongo.MongoComponent

trait BaseControllerISpec
extends UnitSpec
with MongoSupport
with GuiceOneServerPerSuite
with WireMockSupport
with RelationshipStubs
with DesStubs
with DesStubsGet
with MappingStubs
with DataStreamStub
with AuthStub
with MockitoSugar
with JsonMatchers
with AUCDStubs
with AgentAssuranceStubs
with IntegrationPatience {

  lazy val mockAuthConnector: AuthConnector = mock[PlayAuthConnector]
  override implicit lazy val app: Application = appBuilder.build()

  import scala.concurrent.ExecutionContext.Implicits.global

  def additionalConfig: Map[String, Any] = Map.empty

  lazy val mongoRecoveryLockService: MongoLockService = new MongoLockServiceImpl(mongoLockRepository)
  def mongoLockRepository = new MongoLockRepository(mongoComponent, new CurrentTimestampSupport)

  lazy val moduleWithOverrides =
    new AbstractModule {
      override def configure(): Unit = {
        bind(classOf[MongoComponent]).toInstance(mongoComponent)
        bind(classOf[MongoLockService]).toInstance(mongoRecoveryLockService)
      }
    }

  protected def appBuilder: GuiceApplicationBuilder = new GuiceApplicationBuilder()
    .configure(
      "microservice.services.enrolment-store-proxy.port" -> wireMockPort,
      "microservice.services.tax-enrolments.port" -> wireMockPort,
      "microservice.services.users-groups-search.port" -> wireMockPort,
      "microservice.services.des.port" -> wireMockPort,
      "microservice.services.if.port" -> wireMockPort,
      "microservice.services.eis.port" -> wireMockPort,
      "microservice.services.hip.port" -> wireMockPort,
      "microservice.services.citizen-details.port" -> wireMockPort,
      "microservice.services.auth.port" -> wireMockPort,
      "microservice.services.agent-mapping.port" -> wireMockPort,
      "microservice.services.agent-client-authorisation.port" -> wireMockPort,
      "microservice.services.agent-permissions.port" -> wireMockPort,
      "auditing.consumer.baseUri.host" -> wireMockHost,
      "auditing.consumer.baseUri.port" -> wireMockPort,
      "microservice.services.agent-user-client-details.port" -> wireMockPort,
      "microservice.services.agent-assurance.port" -> wireMockPort,
      "microservice.services.agent-fi-relationship.port" -> wireMockPort,
      "microservice.services.email.port" -> wireMockPort,
      "features.copy-relationship.mtd-it" -> true,
      "features.copy-relationship.mtd-vat" -> true,
      "features.recovery-enable" -> false,
      "agent.cache.expires" -> "1 millis",
      "agent.cache.enabled" -> true,
      "agent.trackPage.cache.expires" -> "1 millis",
      "agent.trackPage.cache.enabled" -> true,
      "mongodb.uri" -> mongoUri,
      "internal-auth.token" -> "internalAuthToken",
      "new.auth.stride.role" -> NEW_STRIDE_ROLE,
      "old.auth.stride.role" -> STRIDE_ROLE
    )
    .overrides(moduleWithOverrides)
    .configure(additionalConfig)

  implicit lazy val ws: WSClient = app.injector.instanceOf[WSClient]
  implicit val request: RequestHeader = FakeRequest()

  def repo: MongoRelationshipCopyRecordRepository = new MongoRelationshipCopyRecordRepository(mongoComponent)
  def deleteRecordRepository: MongoDeleteRecordRepository = new MongoDeleteRecordRepository(mongoComponent)

  override def beforeEach() = {
    super.beforeEach()
    givenAuditConnector()
    await(mongoComponent.database.drop().toFuture())
  }

  val arn = Arn("AARN0000002")
  val arnEncoded = UriEncoding.encodePathSegment(arn.value, "UTF-8")
  val arn2 = Arn("AARN0000004")
  val arn3 = Arn("AARN0000006")
  val existingAgentArn = Arn("AARN0000007")
  val mtdItId = MtdItId("ABCDEF123456789")
  val testPostcode = "AA1 1AA"
  val utr = Utr("3087612352")
  val urn = Urn("XXTRUST12345678")
  val utrUriEncoded: String = UriEncoding.encodePathSegment(utr.value, "UTF-8")
  val saUtrType = "SAUTR"
  val urnType = "URN"
  val cgtRef = CgtRef("XMCGTP123456789")
  val pptRef = PptRef("XAPPT0004567890")
  val cbcId = CbcId("XACBC1234567890")
  val plrId = PlrId("XAPLR2222222222")
  val mtdItEnrolmentKey: LocalEnrolmentKey = LocalEnrolmentKey(Service.MtdIt, mtdItId)
  val cbcUkEnrolmentKey: LocalEnrolmentKey = LocalEnrolmentKey(
    Service.Cbc.id,
    Seq(Identifier("UTR", utr.value), Identifier("cbcId", cbcId.value))
  )
  val mtdItSuppEnrolmentKey: LocalEnrolmentKey = LocalEnrolmentKey(Service.MtdItSupp, mtdItId)
  val mtdItIdUriEncoded: String = UriEncoding.encodePathSegment(mtdItId.value, "UTF-8")
  val vrn = Vrn("101747641")
  val testVatRegDate = "2020-01-01"
  val vatEnrolmentKey: LocalEnrolmentKey = LocalEnrolmentKey(Service.Vat, vrn)
  val vrnUriEncoded: String = UriEncoding.encodePathSegment(vrn.value, "UTF-8")
  val nino = Nino("AB123456C")
  val mtdItIdType = "MTDITID"
  val mtdVatIdType = "VRN"
  val oldAgentCode = "oldAgentCode"
  val testAgentUser = "testAgentUser"
  val testAgentGroup = "testAgentGroup"
  val testExistingAgentGroup = "testExistingAgentGroup"
  val STRIDE_ROLE = "maintain agent relationships"
  val NEW_STRIDE_ROLE = "maintain_agent_relationships"
  val TERMINATION_STRIDE_ROLE = "caat"

  val otherTaxIdentifier: TaxIdentifier => TaxIdentifier = {
    case MtdItId(_) => MtdItId("ABCDE1234567890")
    case Vrn(_) => Vrn("101747641")
    case Utr(_) => Utr("2134514321")
    case Urn(_) => Urn("XXTRUST12345678")
    case CgtRef(_) => cgtRef
    case PptRef(_) => pptRef
    case PlrId(_) => plrId
    case x => throw new IllegalArgumentException(s"Tax identifier not supported $x")
  }

  protected def doGetRequest(route: String): HttpResponse = new Resource(route, port).get()

  protected def doAgentPostRequest(
    route: String,
    json: JsValue
  ) = new Resource(route, port).postAsJson(json.toString())

  protected def doAgentPutRequest(route: String) = Http.putEmpty(s"http://localhost:$port$route")
  protected def doAgentPutRequest(
    route: String,
    body: JsValue
  ) = new Resource(route, port).putAsJson(body.toString())

  protected def doAgentPutRequest(
    route: String,
    body: String
  ) = new Resource(route, port).putAsJson(body)

  protected def doAgentDeleteRequest(route: String) = Http.delete(s"http://localhost:$port$route")

  protected def doAgentPostRequest(
    route: String,
    body: String
  ) = new Resource(route, port).postAsJson(body)

  protected def verifyDeleteRecordHasStatuses(
    etmpStatus: Option[SyncStatus.Value],
    esStatus: Option[SyncStatus.Value]
  ) =
    await(deleteRecordRepository.findBy(arn, mtdItEnrolmentKey)) should matchPattern {
      case Some(
            DeleteRecord(
              arn.value,
              Some(ek),
              _,
              _,
              _,
              `etmpStatus`,
              `esStatus`,
              _,
              _,
              _,
              _
            )
          ) if ek == LocalEnrolmentKey(Service.MtdIt, MtdItId("ABCDEF123456789")) =>
    }

  protected def verifyDeleteRecordNotExists = await(deleteRecordRepository.findBy(arn, mtdItEnrolmentKey)) shouldBe None

}

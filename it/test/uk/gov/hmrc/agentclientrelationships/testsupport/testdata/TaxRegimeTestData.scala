/*
 * Copyright 2026 HM Revenue & Customs
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

package uk.gov.hmrc.agentclientrelationships.testsupport.testdata

import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Arn
import uk.gov.hmrc.agentclientrelationships.model.identifiers.CbcId
import uk.gov.hmrc.agentclientrelationships.model.identifiers.CgtRef
import uk.gov.hmrc.agentclientrelationships.model.identifiers.MtdItId
import uk.gov.hmrc.agentclientrelationships.model.identifiers.NinoWithoutSuffix
import uk.gov.hmrc.agentclientrelationships.model.identifiers.PlrId
import uk.gov.hmrc.agentclientrelationships.model.identifiers.PptRef
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Urn
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Utr
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Vrn
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.CapitalGains
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.Cbc
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.CbcNonUk
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.MtdIt
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.MtdItSupp
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.PersonalIncomeRecord
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.Pillar2
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.Ppt
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.Trust
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.TrustNT
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.Vat
import uk.gov.hmrc.agentclientrelationships.stubs.AuthStub
import uk.gov.hmrc.agentclientrelationships.stubs.CitizenDetailsStub
import uk.gov.hmrc.agentclientrelationships.stubs.DesStubs
import uk.gov.hmrc.agentclientrelationships.stubs.EnrolmentStoreProxyStubs
import uk.gov.hmrc.agentclientrelationships.stubs.HipStub
import uk.gov.hmrc.agentclientrelationships.stubs.IfStubs
import uk.gov.hmrc.agentclientrelationships.testsupport.testdata.TestData._
import uk.gov.hmrc.domain.TaxIdentifier

object TestData {

  val groupId = "group-1"
  val groupId2 = "group-2"
  val adminUser = "user-1"
  val agentCode = "legacyCode"
  val arn = Arn("AARN0000001")
  val arn2 = Arn("AARN0000002")
  val arn3 = Arn("AARN0000003")
  val mtdItId = MtdItId("ABCDEF123456789")
  val postcode = "AA1 1AA"
  val utr = Utr("3087612352")
  val urn = Urn("XXTRUST12345678")
  val cgtRef = CgtRef("XMCGTP123456789")
  val pptRef = PptRef("XAPPT0004567890")
  val cbcId = CbcId("XACBC1234567890")
  val plrId = PlrId("XAPLR2222222222")
  val vrn = Vrn("101747641")
  val nino = NinoWithoutSuffix("AB123456")

  val services: Seq[TaxRegimeTestData] = Seq(
    ItsaTestData,
    ItsaSuppTestData,
    VatTestData,
    CgtTestData,
    PptTestData,
    TrustTestData,
    TrustNtTestData,
    Pillar2TestData,
    CbcTestData,
    CbcNonUkTestData
  )

}
trait TaxRegimeTestData {

  val service: Service
  val supportingService: Option[Service] = None
  val multipleAgents: Boolean = false
  val clientId: TaxIdentifier
  lazy val suppliedClientId: TaxIdentifier = clientId
  val secondaryClientId: Option[String] = None
  val secondaryClientIdType: Option[String] = None
  lazy val enrolment = EnrolmentKey(
    s"${service.id}~${service.supportedClientIdType.enrolmentId}~${clientId.value}" +
      s"${secondaryClientIdType.map(id => s"~$id").getOrElse("")}${secondaryClientId.map(id => s"~$id").getOrElse("")}"
  )
  // Not real enrolment, used internally for simplicity
  lazy val partialAuthEnrolment = EnrolmentKey(
    s"${service.id}~${service.supportedSuppliedClientIdType.enrolmentId}~${suppliedClientId.value}"
  )

  // Stubs for default behaviour defined in trait, for non-standard regimes override any of the functions in the regime definition.

  // Auth for client initiated auth/deauth
  def clientAuthStubs(): Unit = AuthStub.givenAuthorisedAsClientWithEnrolment(enrolment)
  // Auth for known fact check to retrieve secondaryClientId (only CBC)
  def clientKnownFactCheckStubs(): Unit = ()
  // Client details lookup (Must be defined)
  def clientExistsStubs(): Unit
  // Client details lookup not found (Must be defined)
  def clientDoesNotExistStubs(): Unit
  // Client ID lookup (only for ITSA and ITSA Supp)
  def clientIdLookupStubs(): Unit = ()
  def clientIdLookupFailureStubs(): Unit = ()

}

object IrvTestData // Non standard regime, does not use etmp/eacd
extends TaxRegimeTestData {

  val service: Service = PersonalIncomeRecord
  val clientId: TaxIdentifier = nino

  override def clientAuthStubs(): Unit = AuthStub.givenAuthorisedAsClientWithNino(nino)
  override def clientExistsStubs(): Unit = CitizenDetailsStub.givenCitizenDetailsExists(nino)
  override def clientDoesNotExistStubs(): Unit = CitizenDetailsStub.givenCitizenDetailsError(nino, 404)

}

object ItsaTestData
extends TaxRegimeTestData {

  val service: Service = MtdIt
  override val supportingService = Some(MtdItSupp)
  val clientId: TaxIdentifier = mtdItId
  override lazy val suppliedClientId: TaxIdentifier = nino

  override def clientAuthStubs(): Unit = AuthStub.givenAuthorisedAsClientWithNino(nino)
  override def clientExistsStubs(): Unit = {
    CitizenDetailsStub.givenCitizenDetailsExists(nino)
    CitizenDetailsStub.givenItsaDesignatoryDetailsExists(nino)
  }
  override def clientDoesNotExistStubs(): Unit = CitizenDetailsStub.givenCitizenDetailsError(nino, 404)
  override def clientIdLookupStubs(): Unit = {
    HipStub.givenMtdItIdIsKnownFor(nino, mtdItId)
    HipStub.givenNinoIsKnownFor(mtdItId, nino)
  }
  override def clientIdLookupFailureStubs(): Unit = HipStub.givenMtdItIdIsUnKnownFor(nino)

}

object ItsaSuppTestData
extends TaxRegimeTestData {

  val service: Service = MtdItSupp
  override val supportingService = Some(MtdIt)
  override val multipleAgents: Boolean = true
  val clientId: TaxIdentifier = mtdItId
  override lazy val suppliedClientId: TaxIdentifier = nino

  override def clientAuthStubs(): Unit = AuthStub.givenAuthorisedAsClientWithNino(nino)
  override def clientExistsStubs(): Unit = {
    CitizenDetailsStub.givenCitizenDetailsExists(nino)
    CitizenDetailsStub.givenItsaDesignatoryDetailsExists(nino)
  }
  override def clientDoesNotExistStubs(): Unit = CitizenDetailsStub.givenCitizenDetailsError(nino, 404)
  override def clientIdLookupStubs(): Unit = {
    HipStub.givenMtdItIdIsKnownFor(nino, mtdItId)
    HipStub.givenNinoIsKnownFor(mtdItId, nino)
  }
  override def clientIdLookupFailureStubs(): Unit = HipStub.givenMtdItIdIsUnKnownFor(nino)

}

object VatTestData
extends TaxRegimeTestData {

  val service: Service = Vat
  val clientId: TaxIdentifier = vrn

  override def clientExistsStubs(): Unit = DesStubs.givenVatCustomerInfoExists(vrn.value, postcode)
  override def clientDoesNotExistStubs(): Unit = DesStubs.givenVatCustomerInfoError(vrn.value, 404)

}

object CgtTestData
extends TaxRegimeTestData {

  val service: Service = CapitalGains
  val clientId: TaxIdentifier = cgtRef

  override def clientExistsStubs(): Unit = DesStubs.givenCgtDetailsExist(cgtRef.value)
  override def clientDoesNotExistStubs(): Unit = DesStubs.givenCgtDetailsError(cgtRef.value, 404)

}

object PptTestData
extends TaxRegimeTestData {

  val service: Service = Ppt
  val clientId: TaxIdentifier = pptRef

  override def clientExistsStubs(): Unit = IfStubs.givenPptDetailsExist(pptRef.value)
  override def clientDoesNotExistStubs(): Unit = IfStubs.givenPptDetailsError(pptRef.value, 404)

}

object TrustTestData
extends TaxRegimeTestData {

  val service: Service = Trust
  val clientId: TaxIdentifier = utr

  override def clientExistsStubs(): Unit = IfStubs.givenTrustDetailsExist(utr.value, Trust.supportedClientIdType.id.toUpperCase)
  override def clientDoesNotExistStubs(): Unit = IfStubs.givenTrustDetailsError(
    utr.value,
    Trust.supportedClientIdType.id.toUpperCase,
    404
  )

}

object TrustNtTestData
extends TaxRegimeTestData {

  val service: Service = TrustNT
  val clientId: TaxIdentifier = urn

  override def clientExistsStubs(): Unit = IfStubs.givenTrustDetailsExist(urn.value, TrustNT.supportedClientIdType.id.toUpperCase)
  override def clientDoesNotExistStubs(): Unit = IfStubs.givenTrustDetailsError(
    urn.value,
    TrustNT.supportedClientIdType.id.toUpperCase,
    404
  )

}

object Pillar2TestData
extends TaxRegimeTestData {

  val service: Service = Pillar2
  val clientId: TaxIdentifier = plrId

  override def clientExistsStubs(): Unit = IfStubs.givenPillar2DetailsExist(plrId.value)
  override def clientDoesNotExistStubs(): Unit = IfStubs.givenPillar2DetailsError(plrId.value, 404)

}

object CbcTestData
extends TaxRegimeTestData {

  val service: Service = Cbc
  val clientId: TaxIdentifier = cbcId
  override val secondaryClientId: Option[String] = Some(utr.value)
  override val secondaryClientIdType: Option[String] = Some("UTR")

  override def clientExistsStubs(): Unit = IfStubs.givenCbcDetailsExist()
  override def clientDoesNotExistStubs(): Unit = IfStubs.givenCbcDetailsError(404)
  override def clientKnownFactCheckStubs(): Unit = EnrolmentStoreProxyStubs.givenCbcUkExistsInES(cbcId, utr.value)

}

object CbcNonUkTestData
extends TaxRegimeTestData {

  val service: Service = CbcNonUk
  val clientId: TaxIdentifier = cbcId

  override def clientExistsStubs(): Unit = IfStubs.givenCbcDetailsExist(isGBUser = false)
  override def clientDoesNotExistStubs(): Unit = IfStubs.givenCbcDetailsError(404)

}

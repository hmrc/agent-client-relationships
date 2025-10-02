/*
 * Copyright 2025 HM Revenue & Customs
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

import play.api.i18n.Lang
import play.api.i18n.Langs
import play.api.i18n.MessagesApi
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository
import uk.gov.hmrc.agentclientrelationships.repository.PartialAuthRepository
import uk.gov.hmrc.agentclientrelationships.stubs.EmailStubs
import uk.gov.hmrc.agentclientrelationships.stubs.HipStub

import java.time.format.DateTimeFormatter
import java.util.Locale

class AuthorisationAcceptControllerISpec
extends BaseControllerISpec
with AuthorisationAcceptGenericBehaviours
with AuthorisationAcceptAltItsaBehaviours
with AuthorisationAcceptItsaBehaviours
with HipStub
with EmailStubs {

  override def additionalConfig: Map[String, Any] = Map("hip.BusinessDetails.enabled" -> true)

  override val invitationRepo: InvitationsRepository = app.injector.instanceOf[InvitationsRepository]
  override val partialAuthRepository: PartialAuthRepository = app.injector.instanceOf[PartialAuthRepository]
  override val messagesApi: MessagesApi = app.injector.instanceOf[MessagesApi]
  val langs: Langs = app.injector.instanceOf[Langs]
  override val lang: Lang = langs.availables.head
  override val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.UK)

  // Income Tax Main
  behave like authorisationAccept(
    serviceId = Service.MtdIt.id,
    suppliedClientId = nino,
    suppliedClientIdType = "NINO"
  )

  // Additional for Itsa Main, Supp
  behave like authorisationAcceptItsa(
    serviceIdAccept = Service.MtdIt.id,
    serviceIdCheck = Service.MtdItSupp.id
  )

  behave like authorisationAcceptItsa(
    serviceIdAccept = Service.MtdItSupp.id,
    serviceIdCheck = Service.MtdIt.id
  )

  // Additional for Alt Itsa Main, Supp
  behave like authorisationAcceptAltItsa(
    serviceIdAccept = Service.MtdIt.id,
    serviceIdCheck = Service.MtdItSupp.id
  )

  behave like authorisationAcceptAltItsa(
    serviceIdAccept = Service.MtdItSupp.id,
    serviceIdCheck = Service.MtdIt.id
  )

  // VAT
  behave like authorisationAccept(
    serviceId = Service.Vat.id,
    suppliedClientId = vrn,
    suppliedClientIdType = "VRN"
  )

  // Trust
  behave like authorisationAccept(
    serviceId = Service.Trust.id,
    suppliedClientId = utr,
    suppliedClientIdType = "SAUTR"
  )

  // TrustNT
  behave like authorisationAccept(
    serviceId = Service.TrustNT.id,
    suppliedClientId = urn,
    suppliedClientIdType = "URN"
  )

  // CGT
  behave like authorisationAccept(
    serviceId = Service.CapitalGains.id,
    suppliedClientId = cgtRef,
    suppliedClientIdType = "CGTPDRef"
  )

  // PPT
  behave like authorisationAccept(
    serviceId = Service.Ppt.id,
    suppliedClientId = pptRef,
    suppliedClientIdType = "EtmpRegistrationNumber"
  )

  // CBC
  behave like authorisationAccept(
    serviceId = Service.Cbc.id,
    suppliedClientId = cbcId,
    suppliedClientIdType = "cbcId"
  )

  // CBC (non-UK)
  behave like authorisationAccept(
    serviceId = Service.CbcNonUk.id,
    suppliedClientId = cbcId,
    suppliedClientIdType = "cbcId"
  )

  // Pillar2
  behave like authorisationAccept(
    serviceId = Service.Pillar2.id,
    suppliedClientId = plrId,
    suppliedClientIdType = "PLRID"
  )

}

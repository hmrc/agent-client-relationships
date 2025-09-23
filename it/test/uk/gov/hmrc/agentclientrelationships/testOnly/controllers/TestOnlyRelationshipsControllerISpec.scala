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

package uk.gov.hmrc.agentclientrelationships.testOnly.controllers

import uk.gov.hmrc.agentclientrelationships.controllers.BaseControllerISpec
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service
import uk.gov.hmrc.agentclientrelationships.stubs.HipStub

class TestOnlyRelationshipsControllerISpec
extends BaseControllerISpec
with TestOnlyRelationshipsControllerGenericBehaviours
with HipStub {

  override def additionalConfig: Map[String, Any] = Map("hip.BusinessDetails.enabled" -> true)

  // Income Tax
  behave like relationshipsControllerPutISpec(
    Service.MtdIt.id,
    mtdItId,
    "MTDITID"
  )
  behave like relationshipsControllerDeleteISpec(
    Service.MtdIt.id,
    mtdItId,
    "MTDITID"
  )

  behave like relationshipsControllerPutISpec(
    Service.MtdItSupp.id,
    mtdItId,
    "MTDITID"
  )
  behave like relationshipsControllerDeleteISpec(
    Service.MtdItSupp.id,
    mtdItId,
    "MTDITID"
  )

  // VAT
  behave like relationshipsControllerPutISpec(
    Service.Vat.id,
    vrn,
    "VRN"
  )
  behave like relationshipsControllerDeleteISpec(
    Service.Vat.id,
    vrn,
    "VRN"
  )

  // Trust
  behave like relationshipsControllerPutISpec(
    Service.Trust.id,
    utr,
    "SAUTR"
  )
  behave like relationshipsControllerDeleteISpec(
    Service.Trust.id,
    utr,
    "SAUTR"
  )

  // TrustNT
  behave like relationshipsControllerPutISpec(
    Service.TrustNT.id,
    urn,
    "URN"
  )
  behave like relationshipsControllerDeleteISpec(
    Service.TrustNT.id,
    urn,
    "URN"
  )

  // CGT
  behave like relationshipsControllerPutISpec(
    Service.CapitalGains.id,
    cgtRef,
    "CGTPDRef"
  )
  behave like relationshipsControllerDeleteISpec(
    Service.CapitalGains.id,
    cgtRef,
    "CGTPDRef"
  )

  // PPT
  behave like relationshipsControllerPutISpec(
    Service.Ppt.id,
    pptRef,
    "EtmpRegistrationNumber"
  )
  behave like relationshipsControllerDeleteISpec(
    Service.Ppt.id,
    pptRef,
    "EtmpRegistrationNumber"
  )

  // CBC
  behave like relationshipsControllerPutISpec(
    Service.Cbc.id,
    cbcId,
    "cbcId"
  )
  behave like relationshipsControllerDeleteISpec(
    Service.Cbc.id,
    cbcId,
    "cbcId"
  )

  // CBC (non-UK)
  behave like relationshipsControllerPutISpec(
    Service.CbcNonUk.id,
    cbcId,
    "cbcId"
  )
  behave like relationshipsControllerDeleteISpec(
    Service.CbcNonUk.id,
    cbcId,
    "cbcId"
  )

  // Pillar2
  behave like relationshipsControllerPutISpec(
    Service.Pillar2.id,
    plrId,
    "PLRID"
  )
  behave like relationshipsControllerDeleteISpec(
    Service.Pillar2.id,
    plrId,
    "PLRID"
  )

}

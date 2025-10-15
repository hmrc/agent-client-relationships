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

import uk.gov.hmrc.agentclientrelationships.stubs.HipStub
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service

class RelationshipsControllerServiceISpec
extends RelationshipsBaseControllerISpec
with RelationshipsControllerGenericBehaviours
with RelationshipsControllerVATBehaviours
with RelationshipsControllerITSABehaviours
with HipStub {

  // Income Tax
  behave like relationshipsControllerGetISpec(
    Service.MtdIt.id,
    mtdItId,
    "MTDITID"
  )
  behave like relationshipsControllerGetISpec(
    Service.MtdIt.id,
    nino,
    "NI"
  )

  behave like relationshipControllerITSASpecificBehaviours()

  behave like relationshipsControllerGetISpec(
    Service.MtdItSupp.id,
    mtdItId,
    "MTDITID"
  )
  behave like relationshipsControllerGetISpec(
    Service.MtdItSupp.id,
    nino,
    "NI"
  )

  // VAT
  behave like relationshipsControllerGetISpec(
    Service.Vat.id,
    vrn,
    "VRN"
  )

  behave like relationshipControllerVATSpecificBehaviours()

  // Trust
  behave like relationshipsControllerGetISpec(
    Service.Trust.id,
    utr,
    "SAUTR"
  )

  // TrustNT
  behave like relationshipsControllerGetISpec(
    Service.TrustNT.id,
    urn,
    "URN"
  )

  // CGT
  behave like relationshipsControllerGetISpec(
    Service.CapitalGains.id,
    cgtRef,
    "CGTPDRef"
  )

  // PPT
  behave like relationshipsControllerGetISpec(
    Service.Ppt.id,
    pptRef,
    "EtmpRegistrationNumber"
  )

  // CBC
  behave like relationshipsControllerGetISpec(
    Service.Cbc.id,
    cbcId,
    "cbcId"
  )

  // CBC (non-UK)
  behave like relationshipsControllerGetISpec(
    Service.CbcNonUk.id,
    cbcId,
    "cbcId"
  )

  // Pillar2
  behave like relationshipsControllerGetISpec(
    Service.Pillar2.id,
    plrId,
    "PLRID"
  )

}

package uk.gov.hmrc.agentclientrelationships.controllers

import uk.gov.hmrc.agentmtdidentifiers.model.Service

class RelationshipsControllerServiceISpec
  extends RelationshipsBaseControllerISpec
    with RelationshipsControllerGenericBehaviours
    with RelationshipsControllerVATBehaviours
    with RelationshipsControllerITSABehaviours {
  // Income Tax
  behave like relationshipsControllerGetISpec(Service.MtdIt.id, mtdItId, "MTDITID")
  behave like relationshipsControllerPutISpec(Service.MtdIt.id, mtdItId, "MTDITID")
  behave like relationshipsControllerDeleteISpec(Service.MtdIt.id, mtdItId, "MTDITID")
  behave like strideEndpointISpec(Service.MtdIt.id, nino, "NI")
  behave like relationshipControllerITSASpecificBehaviours()

  // VAT
  behave like relationshipsControllerGetISpec(Service.Vat.id, vrn, "VRN")
  behave like relationshipsControllerPutISpec(Service.Vat.id, vrn, "VRN")
  behave like relationshipsControllerDeleteISpec(Service.Vat.id, vrn, "VRN")
  behave like strideEndpointISpec(Service.Vat.id, vrn, "VRN")
  behave like relationshipControllerVATSpecificBehaviours()

  // Trust
  behave like relationshipsControllerGetISpec(Service.Trust.id, utr, "SAUTR")
  behave like relationshipsControllerPutISpec(Service.Trust.id, utr, "SAUTR")
  behave like relationshipsControllerDeleteISpec(Service.Trust.id, utr, "SAUTR")
  behave like strideEndpointISpec(Service.Trust.id, utr, "SAUTR")

  // TrustNT
  behave like relationshipsControllerGetISpec(Service.TrustNT.id, urn, "URN")
  behave like relationshipsControllerPutISpec(Service.TrustNT.id, urn, "URN")
  behave like relationshipsControllerDeleteISpec(Service.TrustNT.id, urn, "URN")
  behave like strideEndpointISpec(Service.TrustNT.id, urn, "URN")

  // CGT
  behave like relationshipsControllerGetISpec(Service.CapitalGains.id, cgtRef, "CGTPDRef")
  behave like relationshipsControllerPutISpec(Service.CapitalGains.id, cgtRef, "CGTPDRef")
  behave like relationshipsControllerDeleteISpec(Service.CapitalGains.id, cgtRef, "CGTPDRef")
  behave like strideEndpointISpec(Service.CapitalGains.id, cgtRef, "CGTPDRef")

  // PPT
  behave like relationshipsControllerGetISpec(Service.Ppt.id, pptRef, "EtmpRegistrationNumber")
  behave like relationshipsControllerPutISpec(Service.Ppt.id, pptRef, "EtmpRegistrationNumber")
  behave like relationshipsControllerDeleteISpec(Service.Ppt.id, pptRef, "EtmpRegistrationNumber")
  behave like strideEndpointISpec(Service.Ppt.id, pptRef, "EtmpRegistrationNumber")
}

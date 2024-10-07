package uk.gov.hmrc.agentclientrelationships.model.clientDetails

sealed trait ClientStatus

// case object VatClientInsolvent extends ClientStatus
case object AltItsaClient extends ClientStatus
case object ItsaClient extends ClientStatus

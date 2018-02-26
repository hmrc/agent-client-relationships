package uk.gov.hmrc.agentclientrelationships.support

import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId, Vrn}
import uk.gov.hmrc.domain.{Nino, TaxIdentifier}

trait TaxIdentifierSupport {

  protected def enrolmentKeyPrefixFor(taxIdentifier: TaxIdentifier): String = taxIdentifier match {
    case _: MtdItId => "HMRC-MTD-IT~MTDITID"
    case _: Arn => "HMRC-AS-AGENT~AgentReferenceNumber"
    case _: Nino => "HMRC-MTD-IT~NINO"
    case _: Vrn => "HMCE-VATDEC-ORG~VatRefNo"
    case _ => throw new IllegalArgumentException(s"Tax identifier not supported $taxIdentifier")
  }

}

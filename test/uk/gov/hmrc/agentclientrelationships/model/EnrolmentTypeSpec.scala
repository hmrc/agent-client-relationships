package uk.gov.hmrc.agentclientrelationships.model

import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId, Vrn}
import uk.gov.hmrc.auth.core.{Enrolment, EnrolmentIdentifier}
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.play.test.UnitSpec

class EnrolmentTypeSpec extends UnitSpec {
  "enrolmentTypeFor" should {
    "return EnrolmentMtdIt for an MtdItId identifier" in {
      EnrolmentType.enrolmentTypeFor(MtdItId("")) shouldBe EnrolmentMtdIt
    }

    "return EnrolmentMtdVat for an Vrn identifier" in {
      EnrolmentType.enrolmentTypeFor(Vrn("101747696")) shouldBe EnrolmentMtdVat
    }

    "return EnrolmentAsAgent for an Arn identifier" in {
      EnrolmentType.enrolmentTypeFor(Arn("TARN000001")) shouldBe EnrolmentAsAgent
    }

    "throw an exception for an unhandled identifier" in {
      intercept[IllegalArgumentException] {
        EnrolmentType.enrolmentTypeFor(Nino("AA000000A"))
      }
    }
  }

  "findEnrolmentIdentifier" should {
    val allEnrolmentTypes = Seq(EnrolmentMtdIt, EnrolmentMtdVat, EnrolmentAsAgent)

    "return Arn for EnrolmentAsAgent if the HMRC-AS-AGENT enrolment exists with an AgentReferenceNumber identifier" in {
      val enrolments = Set(Enrolment("HMRC-AS-AGENT", Seq(EnrolmentIdentifier("AgentReferenceNumber", "arn123")), "activated"))

      EnrolmentType.findEnrolmentIdentifier(EnrolmentAsAgent, enrolments) shouldBe Some(Arn("arn123"))
    }

    "return Vrn for EnrolmentMtdVat if the HMRC-MTD-VAT enrolment exists with an MTDVATID identifier" in {
      val enrolments = Set(Enrolment("HMRC-MTD-VAT", Seq(EnrolmentIdentifier("MTDVATID", "101747696")), "activated"))

      EnrolmentType.findEnrolmentIdentifier(EnrolmentMtdVat, enrolments) shouldBe Some(Vrn("101747696"))
    }

    "return MtdItId for EnrolmentMtdIt if the HMRC-MTD-IT enrolment exists with an MTDITID identifier" in {
      val enrolments = Set(Enrolment("HMRC-MTD-IT", Seq(EnrolmentIdentifier("MTDITID", "123456789")), "activated"))

      EnrolmentType.findEnrolmentIdentifier(EnrolmentMtdIt, enrolments) shouldBe Some(MtdItId("123456789"))
    }

    "return None if the required enrolment does not exist" in {
      val enrolments = Set(Enrolment("HMRC-FOO-BAR", Seq(EnrolmentIdentifier("Foo", "Bar")), "activated"))

      allEnrolmentTypes.foreach { enrolmentType =>
        EnrolmentType.findEnrolmentIdentifier(enrolmentType, enrolments) shouldBe None
      }
    }

    "return None if the required enrolment exists but the required identifier does not exist" in {
      val enrolments = Set(
        Enrolment("HMRC-AS-AGENT", Seq(EnrolmentIdentifier("NotAgentReferenceNumber", "arn123")), "activated"),
        Enrolment("HMRC-MTD-IT", Seq(EnrolmentIdentifier("NotMTDITID", "123")), "activated"),
        Enrolment("HMRC-MTD-VAT", Seq(EnrolmentIdentifier("NotMTDVATID", "123")), "activated")
      )

      allEnrolmentTypes.foreach { enrolmentType =>
        EnrolmentType.findEnrolmentIdentifier(enrolmentType, enrolments) shouldBe None
      }
    }
  }
}

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

package uk.gov.hmrc.agentclientrelationships.services

import org.mockito.ArgumentMatchers.{any, eq => eqTo}
import org.mockito.Mockito.verify
import play.api.i18n.{Lang, Langs, MessagesApi}
import play.api.test.Helpers.stubControllerComponents
import uk.gov.hmrc.agentclientrelationships.connectors.EmailConnector
import uk.gov.hmrc.agentclientrelationships.model.{EmailInformation, Invitation}
import uk.gov.hmrc.agentclientrelationships.support.{ResettingMockitoSugar, UnitSpec}
import uk.gov.hmrc.agentmtdidentifiers.model.Service.Vat
import uk.gov.hmrc.agentmtdidentifiers.model.Vrn
import play.api.mvc.{AnyContentAsEmpty, RequestHeader}
import play.api.test.FakeRequest

import java.time.{Instant, LocalDate}
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global

class EmailServiceSpec
extends UnitSpec
with ResettingMockitoSugar {

  val mockEmailConnector: EmailConnector = resettingMock[EmailConnector]
  lazy val messagesApi: MessagesApi = stubControllerComponents().messagesApi
  val langs: Langs = stubControllerComponents().langs
  implicit val lang: Lang = langs.availables.head
  implicit val request: RequestHeader = FakeRequest()

  val service = new EmailService(mockEmailConnector, messagesApi, langs)

  val invitation: Invitation = Invitation
    .createNew(
      "XARN1234567",
      Vat,
      Vrn("123456789"),
      Vrn("234567890"),
      "Macrosoft",
      "Will Gates",
      "agent@email.com",
      LocalDate.parse("2020-01-01"),
      None
    )
    .copy(created = Instant.parse("2020-06-06T00:00:00.000Z"))

  ".sendWarningEmail" should {

    "send the correct model to the connector when there are multiple invitations" in {
      val expectedEmailInfoModel = EmailInformation(
        to = Seq("agent@email.com"),
        templateId = "agent_invitations_about_to_expire",
        parameters = Map(
          "agencyName"          -> "Will Gates",
          "numberOfInvitations" -> "2",
          "createdDate"         -> "6 June 2020",
          "expiryDate"          -> "1 January 2020"
        )
      )
      val invitations = Seq(invitation, invitation.copy(invitationId = "2", suppliedClientId = "2"))

      service.sendWarningEmail(invitations)
      verify(mockEmailConnector).sendEmail(eqTo(expectedEmailInfoModel))(any[RequestHeader]())
    }

    "send the correct model to the connector when there is one invitation" in {
      val expectedEmailInfoModel = EmailInformation(
        to = Seq("agent@email.com"),
        templateId = "agent_invitation_about_to_expire_single",
        parameters = Map(
          "agencyName"          -> "Will Gates",
          "numberOfInvitations" -> "1",
          "createdDate"         -> "6 June 2020",
          "expiryDate"          -> "1 January 2020"
        )
      )

      service.sendWarningEmail(Seq(invitation))
      verify(mockEmailConnector).sendEmail(eqTo(expectedEmailInfoModel))(any[RequestHeader]())
    }
  }

  ".sendExpiredEmail" should {

    val serviceKeys = Seq(
      "HMRC-MTD-IT",
      "HMRC-MTD-IT-SUPP",
      "PERSONAL-INCOME-RECORD",
      "HMRC-MTD-VAT",
      "HMRC-TERS-ORG",
      "HMRC-TERSNT-ORG",
      "HMRC-CGT-PD",
      "HMRC-PPT-ORG",
      "HMRC-CBC-ORG",
      "HMRC-CBC-NONUK",
      "HMRC-PILLAR2-ORG"
    )

    "send the correct model to the connector" when
      serviceKeys.foreach { serviceKey =>
        s"the service is $serviceKey" in {
          val expectedEmailInfoModel = EmailInformation(
            to = Seq("agent@email.com"),
            templateId = "client_expired_authorisation_request",
            parameters = Map(
              "agencyName" -> "Will Gates",
              "clientName" -> "Macrosoft",
              "expiryDate" -> "1 January 2020",
              "service"    -> messagesApi(s"service.$serviceKey")
            )
          )

          service.sendExpiredEmail(invitation.copy(service = serviceKey))
          verify(mockEmailConnector).sendEmail(eqTo(expectedEmailInfoModel))(any[RequestHeader]())
        }
      }
  }
}

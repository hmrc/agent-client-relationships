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

package uk.gov.hmrc.agentclientrelationships.services

import org.mockito.ArgumentMatchers.{any, eq => eqTo}
import org.mockito.Mockito.when
import org.scalatestplus.mockito.MockitoSugar.mock
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors.ClientDetailsConnector
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.ClientStatus.{Deregistered, Insolvent}
import uk.gov.hmrc.agentclientrelationships.model.clientDetails._
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.cgt.CgtSubscriptionDetails
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.itsa.{ItsaBusinessDetails, ItsaCitizenDetails, ItsaDesignatoryDetails}
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.ppt.PptSubscriptionDetails
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.vat.{VatCustomerDetails, VatIndividual}
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec
import uk.gov.hmrc.http.HeaderCarrier

import java.time.LocalDate
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ClientDetailsServiceSpec extends UnitSpec {

  val mockConnector: ClientDetailsConnector = mock[ClientDetailsConnector]
  val mockAppConfig: AppConfig = mock[AppConfig]
  val service = new ClientDetailsService(mockConnector, mockAppConfig)
  implicit val hc: HeaderCarrier = HeaderCarrier()

  ".findClientDetails" when {

    "the service is HMRC-MTD-IT" when {

      "the IF Get Business Details API returns a successful response" should {

        "return a ClientDetailsResponse after confirming postcodes match" in {
          when(mockConnector.getItsaBusinessDetails(eqTo[String]("AA00001B"))(any[HeaderCarrier]))
            .thenReturn(Future.successful(Right(ItsaBusinessDetails("John Rocks", Some("AA1 1AA"), "GB"))))

          val requestModel = ClientDetailsRequest(Map("nino" -> "AA00001B", "postcode" -> "AA1 1AA"))
          val resultModel = ClientDetailsResponse("John Rocks", None, isOverseas = false)

          await(service.findClientDetails("HMRC-MTD-IT", requestModel)) shouldBe Right(resultModel)
        }

        "return a ClientDetailsDoNotMatch error if postcodes do not match" in {
          when(mockConnector.getItsaBusinessDetails(eqTo[String]("AA00001B"))(any[HeaderCarrier]))
            .thenReturn(Future.successful(Right(ItsaBusinessDetails("John Rocks", Some("BB2 2BB"), "GB"))))

          val requestModel = ClientDetailsRequest(Map("nino" -> "AA00001B", "postcode" -> "AA1 1AA"))

          await(service.findClientDetails("HMRC-MTD-IT", requestModel)) shouldBe Left(ClientDetailsDoNotMatch)
        }

        "return a ClientDetailsDoNotMatch error if no postcode was returned by the API" in {
          when(mockConnector.getItsaBusinessDetails(eqTo[String]("AA00001B"))(any[HeaderCarrier]))
            .thenReturn(Future.successful(Right(ItsaBusinessDetails("John Rocks", None, "GB"))))

          val requestModel = ClientDetailsRequest(Map("nino" -> "AA00001B", "postcode" -> "AA1 1AA"))

          await(service.findClientDetails("HMRC-MTD-IT", requestModel)) shouldBe Left(ClientDetailsDoNotMatch)
        }
      }

      "the IF Get Business Details API returns a 404 response" when {

        "the Citizen Details APIs (alt-ITSA) return successful responses" should {

          "return a ClientDetailsResponse after confirming postcodes match" in {
            when(mockAppConfig.altItsaEnabled).thenReturn(true)
            when(mockConnector.getItsaBusinessDetails(eqTo[String]("AA00001B"))(any[HeaderCarrier]))
              .thenReturn(Future.successful(Left(ClientDetailsNotFound)))
            when(mockConnector.getItsaCitizenDetails(eqTo[String]("AA00001B"))(any[HeaderCarrier]))
              .thenReturn(Future.successful(Right(ItsaCitizenDetails(Some("John"), Some("Rocks"), None))))
            when(mockConnector.getItsaDesignatoryDetails(eqTo[String]("AA00001B"))(any[HeaderCarrier]))
              .thenReturn(Future.successful(Right(ItsaDesignatoryDetails(Some("AA1 1AA")))))

            val requestModel = ClientDetailsRequest(Map("nino" -> "AA00001B", "postcode" -> "AA1 1AA"))
            val resultModel = ClientDetailsResponse("John Rocks", None, isOverseas = false)

            await(service.findClientDetails("HMRC-MTD-IT", requestModel)) shouldBe Right(resultModel)
          }

          "return a ClientDetailsDoNotMatch error if postcodes do not match" in {
            when(mockAppConfig.altItsaEnabled).thenReturn(true)
            when(mockConnector.getItsaBusinessDetails(eqTo[String]("AA00001B"))(any[HeaderCarrier]))
              .thenReturn(Future.successful(Left(ClientDetailsNotFound)))
            when(mockConnector.getItsaCitizenDetails(eqTo[String]("AA00001B"))(any[HeaderCarrier]))
              .thenReturn(Future.successful(Right(ItsaCitizenDetails(Some("John"), Some("Rocks"), None))))
            when(mockConnector.getItsaDesignatoryDetails(eqTo[String]("AA00001B"))(any[HeaderCarrier]))
              .thenReturn(Future.successful(Right(ItsaDesignatoryDetails(Some("BB2 2BB")))))

            val requestModel = ClientDetailsRequest(Map("nino" -> "AA00001B", "postcode" -> "AA1 1AA"))

            await(service.findClientDetails("HMRC-MTD-IT", requestModel)) shouldBe Left(ClientDetailsDoNotMatch)
          }

          "return a ClientDetailsDoNotMatch error if no postcode was returned by the API" in {
            when(mockAppConfig.altItsaEnabled).thenReturn(true)
            when(mockConnector.getItsaBusinessDetails(eqTo[String]("AA00001B"))(any[HeaderCarrier]))
              .thenReturn(Future.successful(Left(ClientDetailsNotFound)))
            when(mockConnector.getItsaCitizenDetails(eqTo[String]("AA00001B"))(any[HeaderCarrier]))
              .thenReturn(Future.successful(Right(ItsaCitizenDetails(Some("John"), Some("Rocks"), None))))
            when(mockConnector.getItsaDesignatoryDetails(eqTo[String]("AA00001B"))(any[HeaderCarrier]))
              .thenReturn(Future.successful(Right(ItsaDesignatoryDetails(None))))

            val requestModel = ClientDetailsRequest(Map("nino" -> "AA00001B", "postcode" -> "AA1 1AA"))

            await(service.findClientDetails("HMRC-MTD-IT", requestModel)) shouldBe Left(ClientDetailsDoNotMatch)
          }

          "return a ClientDetailsDoNotMatch error if no name was returned by the API" in {
            when(mockAppConfig.altItsaEnabled).thenReturn(true)
            when(mockConnector.getItsaBusinessDetails(eqTo[String]("AA00001B"))(any[HeaderCarrier]))
              .thenReturn(Future.successful(Left(ClientDetailsNotFound)))
            when(mockConnector.getItsaCitizenDetails(eqTo[String]("AA00001B"))(any[HeaderCarrier]))
              .thenReturn(Future.successful(Right(ItsaCitizenDetails(None, None, None))))
            when(mockConnector.getItsaDesignatoryDetails(eqTo[String]("AA00001B"))(any[HeaderCarrier]))
              .thenReturn(Future.successful(Right(ItsaDesignatoryDetails(Some("AA1 1AA")))))

            val requestModel = ClientDetailsRequest(Map("nino" -> "AA00001B", "postcode" -> "AA1 1AA"))

            await(service.findClientDetails("HMRC-MTD-IT", requestModel)) shouldBe Left(ClientDetailsDoNotMatch)
          }
        }

        "the Citizen Details APIs (alt-ITSA) return 404 responses" should {

          "return a ClientDetailsNotFound error" in {
            when(mockAppConfig.altItsaEnabled).thenReturn(true)
            when(mockConnector.getItsaBusinessDetails(eqTo[String]("AA00001B"))(any[HeaderCarrier]))
              .thenReturn(Future.successful(Left(ClientDetailsNotFound)))
            when(mockConnector.getItsaCitizenDetails(eqTo[String]("AA00001B"))(any[HeaderCarrier]))
              .thenReturn(Future.successful(Left(ClientDetailsNotFound)))
            when(mockConnector.getItsaDesignatoryDetails(eqTo[String]("AA00001B"))(any[HeaderCarrier]))
              .thenReturn(Future.successful(Left(ClientDetailsNotFound)))

            val requestModel = ClientDetailsRequest(Map("nino" -> "AA00001B", "postcode" -> "AA1 1AA"))

            await(service.findClientDetails("HMRC-MTD-IT", requestModel)) shouldBe Left(ClientDetailsNotFound)
          }
        }
      }
    }

    "the service is HMRC-MTD-VAT" when {

      "the VAT customer info API returns a successful response" should {

        val responseModel = VatCustomerDetails(
          Some("CFG"),
          Some(VatIndividual(Some("Mr"), Some("Ilkay"), Some("Silky"), Some("Gundo"))),
          Some("CFG Solutions"),
          Some(LocalDate.parse("2020-01-01")),
          isInsolvent = true,
          isOverseas = true
        )

        "return a ClientDetailsResponse after confirming registration dates match" in {
          when(mockConnector.getVatCustomerInfo(eqTo[String]("123456789"))(any[HeaderCarrier]))
            .thenReturn(Future.successful(Right(responseModel)))

          val requestModel = ClientDetailsRequest(Map("vrn" -> "123456789", "registrationDate" -> "2020-01-01"))
          val resultModel = ClientDetailsResponse("CFG Solutions", Some(Insolvent), isOverseas = true)

          await(service.findClientDetails("HMRC-MTD-VAT", requestModel)) shouldBe Right(resultModel)
        }

        "return a ClientDetailsDoNotMatch error if registration dates do not match" in {
          when(mockConnector.getVatCustomerInfo(eqTo[String]("123456789"))(any[HeaderCarrier]))
            .thenReturn(Future.successful(Right(responseModel)))

          val requestModel = ClientDetailsRequest(Map("vrn" -> "123456789", "registrationDate" -> "2022-02-02"))

          await(service.findClientDetails("HMRC-MTD-VAT", requestModel)) shouldBe Left(ClientDetailsDoNotMatch)
        }

        "return a ClientDetailsNotFound error if no names are returned by the API" in {
          val noNameModel = responseModel.copy(organisationName = None, tradingName = None, individual = None)

          when(mockConnector.getVatCustomerInfo(eqTo[String]("123456789"))(any[HeaderCarrier]))
            .thenReturn(Future.successful(Right(noNameModel)))

          val requestModel = ClientDetailsRequest(Map("vrn" -> "123456789", "registrationDate" -> "2020-01-01"))

          await(service.findClientDetails("HMRC-MTD-VAT", requestModel)) shouldBe Left(ClientDetailsNotFound)
        }
      }

      "the VAT customer info API returns an unsuccessful response" should {

        "return the same error given by the connector" in {
          when(mockConnector.getVatCustomerInfo(eqTo[String]("123456789"))(any[HeaderCarrier]))
            .thenReturn(Future.successful(Left(ClientDetailsNotFound)))

          val requestModel = ClientDetailsRequest(Map("vrn" -> "123456789", "registrationDate" -> "2020-01-01"))

          await(service.findClientDetails("HMRC-MTD-VAT", requestModel)) shouldBe Left(ClientDetailsNotFound)
        }
      }
    }

    "the service is HMRC-TERS-ORG" when {

      "the trust details API returns a successful response" should {

        "return a ClientDetailsResponse including the trust name" in {
          when(mockConnector.getTrustName(eqTo[String]("1234567890"))(any[HeaderCarrier]))
            .thenReturn(Future.successful(Right("The Safety Trust")))

          val requestModel = ClientDetailsRequest(Map("taxId" -> "1234567890"))
          val resultModel = ClientDetailsResponse("The Safety Trust", None, isOverseas = false)

          await(service.findClientDetails("HMRC-TERS-ORG", requestModel)) shouldBe Right(resultModel)
        }
      }

      "the trust details API returns an unsuccessful response" should {

        "return the same error given by the connector" in {
          when(mockConnector.getTrustName(eqTo[String]("1234567890"))(any[HeaderCarrier]))
            .thenReturn(Future.successful(Left(ClientDetailsNotFound)))

          val requestModel = ClientDetailsRequest(Map("taxId" -> "1234567890"))

          await(service.findClientDetails("HMRC-TERS-ORG", requestModel)) shouldBe Left(ClientDetailsNotFound)
        }
      }
    }

    "the service is IR-SA" when {

      "the Citizen Details API returns a successful response" should {

        "return a ClientDetailsResponse after confirming postcodes match" in {
          when(mockConnector.getItsaCitizenDetails(eqTo[String]("AA00001B"))(any[HeaderCarrier]))
            .thenReturn(Future.successful(Right(
              ItsaCitizenDetails(Some("John"), Some("Rocks"), Some(LocalDate.parse("2000-01-01"))))))

          val requestModel = ClientDetailsRequest(Map("nino" -> "AA00001B", "dob" -> "01012000"))
          val resultModel = ClientDetailsResponse("John Rocks", None, isOverseas = false)

          await(service.findClientDetails("IR-SA", requestModel)) shouldBe Right(resultModel)
        }

        "return a ClientDetailsDoNotMatch error if the date of births do not match" in {
          when(mockConnector.getItsaCitizenDetails(eqTo[String]("AA00001B"))(any[HeaderCarrier]))
            .thenReturn(Future.successful(Right(
              ItsaCitizenDetails(Some("John"), Some("Rocks"), Some(LocalDate.parse("2000-01-01"))))))

          val requestModel = ClientDetailsRequest(Map("nino" -> "AA00001B", "dob" -> "02022002"))

          await(service.findClientDetails("IR-SA", requestModel)) shouldBe Left(ClientDetailsDoNotMatch)
        }

        "return a ClientDetailsDoNotMatch error if no date of birth was returned by the API" in {
          when(mockConnector.getItsaCitizenDetails(eqTo[String]("AA00001B"))(any[HeaderCarrier]))
            .thenReturn(Future.successful(Right(ItsaCitizenDetails(Some("John"), Some("Rocks"), None))))

          val requestModel = ClientDetailsRequest(Map("nino" -> "AA00001B", "dob" -> "01012000"))

          await(service.findClientDetails("IR-SA", requestModel)) shouldBe Left(ClientDetailsDoNotMatch)
        }

        "return a ClientDetailsNotFound error if no name was returned by the API" in {
          when(mockConnector.getItsaCitizenDetails(eqTo[String]("AA00001B"))(any[HeaderCarrier]))
            .thenReturn(Future.successful(Right(ItsaCitizenDetails(None, None, Some(LocalDate.parse("2000-01-01"))))))

          val requestModel = ClientDetailsRequest(Map("nino" -> "AA00001B", "dob" -> "01012000"))

          await(service.findClientDetails("IR-SA", requestModel)) shouldBe Left(ClientDetailsNotFound)
        }
      }

      "the Citizen Details API returns an unsuccessful response" should {

        "return the same error given by the connector" in {
          when(mockConnector.getItsaCitizenDetails(eqTo[String]("AA00001B"))(any[HeaderCarrier]))
            .thenReturn(Future.successful(Left(ClientDetailsNotFound)))

          val requestModel = ClientDetailsRequest(Map("nino" -> "AA00001B", "dob" -> "01012000"))

          await(service.findClientDetails("IR-SA", requestModel)) shouldBe Left(ClientDetailsNotFound)
        }
      }
    }

    "the service is HMRC-CGT-PD" when {

      "the CGT subscription API returns a successful response" should {

        "return a ClientDetailsResponse if postcode is the provided known fact and they match" in {
          when(mockConnector.getCgtSubscriptionDetails(eqTo[String]("XACGTP123456789"))(any[HeaderCarrier]))
            .thenReturn(Future.successful(Right(CgtSubscriptionDetails("Erling Haal", Some("AA1 1AA"), "GB"))))

          val requestModel = ClientDetailsRequest(Map("cgtRef" -> "XACGTP123456789", "knownFact" -> "AA1 1AA"))
          val resultModel = ClientDetailsResponse("Erling Haal", None, isOverseas = false)

          await(service.findClientDetails("HMRC-CGT-PD", requestModel)) shouldBe Right(resultModel)
        }

        "return a ClientDetailsResponse if country code is the provided known fact and they match" in {
          when(mockConnector.getCgtSubscriptionDetails(eqTo[String]("XACGTP123456789"))(any[HeaderCarrier]))
            .thenReturn(Future.successful(Right(CgtSubscriptionDetails("Erling Haal", Some("AA1 1AA"), "GB"))))

          val requestModel = ClientDetailsRequest(Map("cgtRef" -> "XACGTP123456789", "knownFact" -> "GB"))
          val resultModel = ClientDetailsResponse("Erling Haal", None, isOverseas = false)

          await(service.findClientDetails("HMRC-CGT-PD", requestModel)) shouldBe Right(resultModel)
        }

        "return a ClientDetailsDoNotMatch error if postcode is the provided known fact and they do not match" in {
          when(mockConnector.getCgtSubscriptionDetails(eqTo[String]("XACGTP123456789"))(any[HeaderCarrier]))
            .thenReturn(Future.successful(Right(CgtSubscriptionDetails("Erling Haal", Some("AA1 1AA"), "GB"))))

          val requestModel = ClientDetailsRequest(Map("cgtRef" -> "XACGTP123456789", "knownFact" -> "BB2 2BB"))

          await(service.findClientDetails("HMRC-CGT-PD", requestModel)) shouldBe Left(ClientDetailsDoNotMatch)
        }

        "return a ClientDetailsDoNotMatch error if country code is the provided known fact and they do not match" in {
          when(mockConnector.getCgtSubscriptionDetails(eqTo[String]("XACGTP123456789"))(any[HeaderCarrier]))
            .thenReturn(Future.successful(Right(CgtSubscriptionDetails("Erling Haal", Some("AA1 1AA"), "GB"))))

          val requestModel = ClientDetailsRequest(Map("cgtRef" -> "XACGTP123456789", "knownFact" -> "NO"))

          await(service.findClientDetails("HMRC-CGT-PD", requestModel)) shouldBe Left(ClientDetailsDoNotMatch)
        }
      }

      "the Citizen Details API returns an unsuccessful response" should {

        "return the same error given by the connector" in {
          when(mockConnector.getCgtSubscriptionDetails(eqTo[String]("XACGTP123456789"))(any[HeaderCarrier]))
            .thenReturn(Future.successful(Left(ClientDetailsNotFound)))

          val requestModel = ClientDetailsRequest(Map("cgtRef" -> "XACGTP123456789", "knownFact" -> "AA1 1AA"))

          await(service.findClientDetails("HMRC-CGT-PD", requestModel)) shouldBe Left(ClientDetailsNotFound)
        }
      }
    }

    "the service is HMRC-PPT-ORG" when {

      "the PPT subscription API returns a successful response" should {

        "return a ClientDetailsResponse if the registration dates match" in {
          when(mockConnector.getPptSubscriptionDetails(eqTo[String]("XAPPT0004567890"))(any[HeaderCarrier]))
            .thenReturn(Future.successful(Right(
              PptSubscriptionDetails("Erling Haal", LocalDate.parse("2020-01-01"), None))))

          val requestModel = ClientDetailsRequest(Map("pptRef" -> "XAPPT0004567890", "registrationDate" -> "2020-01-01"))
          val resultModel = ClientDetailsResponse("Erling Haal", None, isOverseas = false)

          await(service.findClientDetails("HMRC-PPT-ORG", requestModel)) shouldBe Right(resultModel)
        }

        "return a ClientDetailsResponse if the registration dates match and client is deregistered" in {
          when(mockConnector.getPptSubscriptionDetails(eqTo[String]("XAPPT0004567890"))(any[HeaderCarrier]))
            .thenReturn(Future.successful(Right(
              PptSubscriptionDetails("Erling Haal", LocalDate.parse("2020-01-01"), Some(LocalDate.parse("2021-01-01"))))))

          val requestModel = ClientDetailsRequest(Map("pptRef" -> "XAPPT0004567890", "registrationDate" -> "2020-01-01"))
          val resultModel = ClientDetailsResponse("Erling Haal", Some(Deregistered), isOverseas = false)

          await(service.findClientDetails("HMRC-PPT-ORG", requestModel)) shouldBe Right(resultModel)
        }

        "return a ClientDetailsDoNotMatch error if registration dates do not match" in {
          when(mockConnector.getPptSubscriptionDetails(eqTo[String]("XAPPT0004567890"))(any[HeaderCarrier]))
            .thenReturn(Future.successful(Right(
              PptSubscriptionDetails("Erling Haal", LocalDate.parse("2020-01-01"), None))))

          val requestModel = ClientDetailsRequest(Map("pptRef" -> "XAPPT0004567890", "registrationDate" -> "2022-02-02"))

          await(service.findClientDetails("HMRC-PPT-ORG", requestModel)) shouldBe Left(ClientDetailsDoNotMatch)
        }
      }

      "the Citizen Details API returns an unsuccessful response" should {

        "return the same error given by the connector" in {
          when(mockConnector.getPptSubscriptionDetails(eqTo[String]("XAPPT0004567890"))(any[HeaderCarrier]))
            .thenReturn(Future.successful(Left(ClientDetailsNotFound)))

          val requestModel = ClientDetailsRequest(Map("pptRef" -> "XAPPT0004567890", "registrationDate" -> "2020-01-01"))

          await(service.findClientDetails("HMRC-PPT-ORG", requestModel)) shouldBe Left(ClientDetailsNotFound)
        }
      }
    }
  }
}

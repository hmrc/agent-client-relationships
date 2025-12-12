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

import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.{eq => eqTo}
import org.mockito.Mockito.when
import org.scalatestplus.mockito.MockitoSugar.mock
import play.api.mvc.RequestHeader
import play.api.test.FakeRequest
import play.api.test.Helpers.await
import play.api.test.Helpers.defaultAwaitTimeout
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors.ClientDetailsConnector
import uk.gov.hmrc.agentclientrelationships.connectors.HipConnector
import uk.gov.hmrc.agentclientrelationships.model.CitizenDetails
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.ClientStatus.Deregistered
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.ClientStatus.Inactive
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.ClientStatus.Insolvent
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.KnownFactType._
import uk.gov.hmrc.agentclientrelationships.model.clientDetails._
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.cbc.SimpleCbcSubscription
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.cgt.CgtSubscriptionDetails
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.itsa.ItsaBusinessDetails
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.itsa.ItsaDesignatoryDetails
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.pillar2.Pillar2Record
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.ppt.PptSubscriptionDetails
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.vat.VatCustomerDetails
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.vat.VatIndividual
import uk.gov.hmrc.agentclientrelationships.model.identifiers.NinoWithoutSuffix
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec

import java.time.LocalDate
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ClientDetailsServiceSpec
extends UnitSpec {

  val mockConnector: ClientDetailsConnector = mock[ClientDetailsConnector]
  val mockHipConnector: HipConnector = mock[HipConnector]
  val mockAppConfig: AppConfig = mock[AppConfig]
  val service =
    new ClientDetailsService(
      mockConnector,
      mockHipConnector,
      mockAppConfig
    )
  implicit val request: RequestHeader = FakeRequest()
  val nino: NinoWithoutSuffix = NinoWithoutSuffix("AA000001B")

  ".findClientDetails" when {

    "the service is HMRC-MTD-IT" when {

      "the IF Get Business Details API returns a successful response" should {

        "return a ClientDetailsResponse if expected data is returned" in {
          when(mockHipConnector.getItsaBusinessDetails(eqTo(nino))(any[RequestHeader])).thenReturn(
            Future.successful(
              Right(
                ItsaBusinessDetails(
                  "John Rocks",
                  Some("AA1 1AA"),
                  "GB"
                )
              )
            )
          )

          val resultModel = ClientDetailsResponse(
            "John Rocks",
            None,
            isOverseas = Some(false),
            Seq("AA11AA"),
            Some(PostalCode)
          )

          await(service.findClientDetails("HMRC-MTD-IT", "AA000001")) shouldBe Right(resultModel)
        }

        "return a ClientDetailsNotFound error if no postcode was returned" in {
          when(mockHipConnector.getItsaBusinessDetails(eqTo(nino))(any[RequestHeader])).thenReturn(
            Future.successful(
              Right(
                ItsaBusinessDetails(
                  "John Rocks",
                  None,
                  "GB"
                )
              )
            )
          )

          await(service.findClientDetails("HMRC-MTD-IT", "AA000001B")) shouldBe Left(ClientDetailsNotFound)
        }
      }

      "the IF Get Business Details API returns a 404 response" when {

        "the Citizen Details APIs (alt-ITSA) return successful responses" should {

          "return a ClientDetailsResponse if expected data is returned" in {
            when(mockHipConnector.getItsaBusinessDetails(eqTo(nino))(any[RequestHeader])).thenReturn(
              Future.successful(Left(ClientDetailsNotFound))
            )
            when(mockConnector.getItsaCitizenDetails(eqTo(nino))(any[RequestHeader])).thenReturn(
              Future.successful(
                Right(
                  CitizenDetails(
                    Some("John"),
                    Some("Rocks"),
                    None,
                    Some("11223344")
                  )
                )
              )
            )
            when(mockConnector.getItsaDesignatoryDetails(eqTo(nino))(any[RequestHeader])).thenReturn(
              Future.successful(Right(ItsaDesignatoryDetails(Some("AA1 1AA"), Some("GREAT BRITAIN"))))
            )

            val resultModel = ClientDetailsResponse(
              "John Rocks",
              None,
              isOverseas = Some(false),
              Seq("AA11AA"),
              Some(PostalCode)
            )

            await(service.findClientDetails("HMRC-MTD-IT", "AA000001B")) shouldBe Right(resultModel)
          }

          "return a ClientDetailsNotFound error if no postcode was returned" in {
            when(mockHipConnector.getItsaBusinessDetails(eqTo(nino))(any[RequestHeader])).thenReturn(
              Future.successful(Left(ClientDetailsNotFound))
            )
            when(mockConnector.getItsaCitizenDetails(eqTo(nino))(any[RequestHeader])).thenReturn(
              Future.successful(
                Right(
                  CitizenDetails(
                    Some("John"),
                    Some("Rocks"),
                    None,
                    Some("11223344")
                  )
                )
              )
            )
            when(mockConnector.getItsaDesignatoryDetails(eqTo(nino))(any[RequestHeader])).thenReturn(
              Future.successful(Right(ItsaDesignatoryDetails(None, None)))
            )

            await(service.findClientDetails("HMRC-MTD-IT", "AA000001B")) shouldBe Left(ClientDetailsNotFound)
          }

          "return a ClientDetailsNotFound error if no name was returned" in {
            when(mockHipConnector.getItsaBusinessDetails(eqTo(nino))(any[RequestHeader])).thenReturn(
              Future.successful(Left(ClientDetailsNotFound))
            )
            when(mockConnector.getItsaCitizenDetails(eqTo(nino))(any[RequestHeader])).thenReturn(
              Future.successful(
                Right(
                  CitizenDetails(
                    None,
                    None,
                    None,
                    Some("11223344")
                  )
                )
              )
            )
            when(mockConnector.getItsaDesignatoryDetails(eqTo(nino))(any[RequestHeader])).thenReturn(
              Future.successful(Right(ItsaDesignatoryDetails(Some("AA1 1AA"), Some("GREAT BRITAIN"))))
            )

            await(service.findClientDetails("HMRC-MTD-IT", "AA000001B")) shouldBe Left(ClientDetailsNotFound)
          }

          "return a ClientDetailsNotFound error if no SA UTR was returned" in {
            when(mockHipConnector.getItsaBusinessDetails(eqTo(nino))(any[RequestHeader])).thenReturn(
              Future.successful(Left(ClientDetailsNotFound))
            )
            when(mockConnector.getItsaCitizenDetails(eqTo(nino))(any[RequestHeader])).thenReturn(
              Future.successful(
                Right(
                  CitizenDetails(
                    Some("John"),
                    Some("Rocks"),
                    None,
                    None
                  )
                )
              )
            )
            when(mockConnector.getItsaDesignatoryDetails(eqTo(nino))(any[RequestHeader])).thenReturn(
              Future.successful(Right(ItsaDesignatoryDetails(Some("AA1 1AA"), Some("GREAT BRITAIN"))))
            )

            await(service.findClientDetails("HMRC-MTD-IT", "AA000001B")) shouldBe Left(ClientDetailsNotFound)
          }
        }

        "the Citizen Details APIs (alt-ITSA) return 404 responses" should {

          "return a ClientDetailsNotFound error" in {
            when(mockHipConnector.getItsaBusinessDetails(eqTo(nino))(any[RequestHeader])).thenReturn(
              Future.successful(Left(ClientDetailsNotFound))
            )
            when(mockConnector.getItsaCitizenDetails(eqTo(nino))(any[RequestHeader])).thenReturn(
              Future.successful(Left(ClientDetailsNotFound))
            )
            when(mockConnector.getItsaDesignatoryDetails(eqTo(nino))(any[RequestHeader])).thenReturn(
              Future.successful(Left(ClientDetailsNotFound))
            )

            await(service.findClientDetails("HMRC-MTD-IT", "AA000001B")) shouldBe Left(ClientDetailsNotFound)
          }
        }
      }
    }

    "the service is HMRC-MTD-VAT" when {

      "the VAT customer info API returns a successful response" should {

        val responseModel = VatCustomerDetails(
          Some("CFG"),
          Some(
            VatIndividual(
              Some("Mr"),
              Some("Ilkay"),
              Some("Silky"),
              Some("Gundo")
            )
          ),
          Some("CFG Solutions"),
          Some(LocalDate.parse("2020-01-01")),
          isInsolvent = true
        )

        "return a ClientDetailsResponse if expected data is returned" in {
          when(mockConnector.getVatCustomerInfo(eqTo[String]("123456789"))(any[RequestHeader])).thenReturn(
            Future.successful(Right(responseModel))
          )

          val resultModel = ClientDetailsResponse(
            "CFG Solutions",
            Some(Insolvent),
            isOverseas = None,
            Seq("2020-01-01"),
            Some(Date)
          )

          await(service.findClientDetails("HMRC-MTD-VAT", "123456789")) shouldBe Right(resultModel)
        }

        "return a ClientDetailsNotFound error if no names are returned" in {
          val noNameModel = responseModel.copy(
            organisationName = None,
            tradingName = None,
            individual = None
          )

          when(mockConnector.getVatCustomerInfo(eqTo[String]("123456789"))(any[RequestHeader])).thenReturn(
            Future.successful(Right(noNameModel))
          )

          await(service.findClientDetails("HMRC-MTD-VAT", "123456789")) shouldBe Left(ClientDetailsNotFound)
        }

        "return a ClientDetailsNotFound error if no registration date is returned" in {
          val noRegDateModel = responseModel.copy(effectiveRegistrationDate = None)

          when(mockConnector.getVatCustomerInfo(eqTo[String]("123456789"))(any[RequestHeader])).thenReturn(
            Future.successful(Right(noRegDateModel))
          )

          await(service.findClientDetails("HMRC-MTD-VAT", "123456789")) shouldBe Left(ClientDetailsNotFound)
        }
      }

      "the VAT customer info API returns an unsuccessful response" should {

        "return the same error given by the connector" in {
          when(mockConnector.getVatCustomerInfo(eqTo[String]("123456789"))(any[RequestHeader])).thenReturn(
            Future.successful(Left(ClientDetailsNotFound))
          )

          await(service.findClientDetails("HMRC-MTD-VAT", "123456789")) shouldBe Left(ClientDetailsNotFound)
        }
      }
    }

    "the service is HMRC-TERS-ORG" when {

      "the trust details API returns a successful response" should {

        "return a ClientDetailsResponse if expected data is returned" in {
          when(mockConnector.getTrustName(eqTo[String]("1234567890"))(any[RequestHeader])).thenReturn(
            Future.successful(Right("The Safety Trust"))
          )

          val resultModel = ClientDetailsResponse(
            "The Safety Trust",
            None,
            isOverseas = None,
            Seq(),
            None
          )

          await(service.findClientDetails("HMRC-TERS-ORG", "1234567890")) shouldBe Right(resultModel)
        }
      }

      "the trust details API returns an unsuccessful response" should {

        "return the same error given by the connector" in {
          when(mockConnector.getTrustName(eqTo[String]("1234567890"))(any[RequestHeader])).thenReturn(
            Future.successful(Left(ClientDetailsNotFound))
          )

          await(service.findClientDetails("HMRC-TERS-ORG", "1234567890")) shouldBe Left(ClientDetailsNotFound)
        }
      }
    }

    "the service is IR-SA" when {

      "the Citizen Details API returns a successful response" should {

        "return a ClientDetailsResponse if expected data is returned" in {
          when(mockConnector.getItsaCitizenDetails(eqTo(nino))(any[RequestHeader])).thenReturn(
            Future.successful(
              Right(
                CitizenDetails(
                  Some("John"),
                  Some("Rocks"),
                  Some(LocalDate.parse("2000-01-01")),
                  None
                )
              )
            )
          )

          val resultModel = ClientDetailsResponse(
            "John Rocks",
            None,
            isOverseas = None,
            Seq("2000-01-01"),
            Some(Date)
          )

          await(service.findClientDetails("PERSONAL-INCOME-RECORD", "AA000001B")) shouldBe Right(resultModel)
        }

        "return a ClientDetailsNotFound error if no date of birth was returned" in {
          when(mockConnector.getItsaCitizenDetails(eqTo(nino))(any[RequestHeader])).thenReturn(
            Future.successful(
              Right(
                CitizenDetails(
                  Some("John"),
                  Some("Rocks"),
                  None,
                  None
                )
              )
            )
          )

          await(service.findClientDetails("PERSONAL-INCOME-RECORD", "AA000001B")) shouldBe Left(ClientDetailsNotFound)
        }

        "return a ClientDetailsNotFound error if no name was returned" in {
          when(mockConnector.getItsaCitizenDetails(eqTo(nino))(any[RequestHeader])).thenReturn(
            Future.successful(
              Right(
                CitizenDetails(
                  None,
                  None,
                  Some(LocalDate.parse("2000-01-01")),
                  None
                )
              )
            )
          )

          await(service.findClientDetails("PERSONAL-INCOME-RECORD", "AA000001B")) shouldBe Left(ClientDetailsNotFound)
        }
      }

      "the Citizen Details API returns an unsuccessful response" should {

        "return the same error given by the connector" in {
          when(mockConnector.getItsaCitizenDetails(eqTo(nino))(any[RequestHeader])).thenReturn(
            Future.successful(Left(ClientDetailsNotFound))
          )

          await(service.findClientDetails("PERSONAL-INCOME-RECORD", "AA000001B")) shouldBe Left(ClientDetailsNotFound)
        }
      }
    }

    "the service is HMRC-CGT-PD" when {

      "the CGT subscription API returns a successful response" should {

        "return a ClientDetailsResponse with postcode as the known fact if country code is GB" in {
          when(mockConnector.getCgtSubscriptionDetails(eqTo[String]("XACGTP123456789"))(any[RequestHeader])).thenReturn(
            Future.successful(
              Right(
                CgtSubscriptionDetails(
                  "Erling Haal",
                  Some("AA11AA"),
                  "GB"
                )
              )
            )
          )

          val resultModel = ClientDetailsResponse(
            "Erling Haal",
            None,
            isOverseas = Some(false),
            Seq("AA11AA"),
            Some(PostalCode)
          )

          await(service.findClientDetails("HMRC-CGT-PD", "XACGTP123456789")) shouldBe Right(resultModel)
        }

        "return a ClientDetailsResponse with country code as the known fact if country code is not GB" in {
          when(mockConnector.getCgtSubscriptionDetails(eqTo[String]("XACGTP123456789"))(any[RequestHeader])).thenReturn(
            Future.successful(
              Right(
                CgtSubscriptionDetails(
                  "Erling Haal",
                  None,
                  "NO"
                )
              )
            )
          )

          val resultModel = ClientDetailsResponse(
            "Erling Haal",
            None,
            isOverseas = Some(true),
            Seq("NO"),
            Some(CountryCode)
          )

          await(service.findClientDetails("HMRC-CGT-PD", "XACGTP123456789")) shouldBe Right(resultModel)
        }
      }

      "the Citizen Details API returns an unsuccessful response" should {

        "return the same error given by the connector" in {
          when(mockConnector.getCgtSubscriptionDetails(eqTo[String]("XACGTP123456789"))(any[RequestHeader])).thenReturn(
            Future.successful(Left(ClientDetailsNotFound))
          )

          await(service.findClientDetails("HMRC-CGT-PD", "XACGTP123456789")) shouldBe Left(ClientDetailsNotFound)
        }
      }
    }

    "the service is HMRC-PPT-ORG" when {

      "the PPT subscription API returns a successful response" should {

        "return a ClientDetailsResponse if expected data is returned" in {
          when(mockConnector.getPptSubscriptionDetails(eqTo[String]("XAPPT0004567890"))(any[RequestHeader])).thenReturn(
            Future.successful(
              Right(
                PptSubscriptionDetails(
                  "Erling Haal",
                  LocalDate.parse("2020-01-01"),
                  None
                )
              )
            )
          )

          val resultModel = ClientDetailsResponse(
            "Erling Haal",
            None,
            isOverseas = None,
            Seq("2020-01-01"),
            Some(Date)
          )

          await(service.findClientDetails("HMRC-PPT-ORG", "XAPPT0004567890")) shouldBe Right(resultModel)
        }

        "return a ClientDetailsResponse if expected data is returned including a deregistered date" in {
          when(mockConnector.getPptSubscriptionDetails(eqTo[String]("XAPPT0004567890"))(any[RequestHeader])).thenReturn(
            Future.successful(
              Right(
                PptSubscriptionDetails(
                  "Erling Haal",
                  LocalDate.parse("2020-01-01"),
                  Some(LocalDate.parse("2021-01-01"))
                )
              )
            )
          )

          val resultModel = ClientDetailsResponse(
            "Erling Haal",
            Some(Deregistered),
            isOverseas = None,
            Seq("2020-01-01"),
            Some(Date)
          )

          await(service.findClientDetails("HMRC-PPT-ORG", "XAPPT0004567890")) shouldBe Right(resultModel)
        }
      }

      "the PPT subscription API returns an unsuccessful response" should {

        "return the same error given by the connector" in {
          when(mockConnector.getPptSubscriptionDetails(eqTo[String]("XAPPT0004567890"))(any[RequestHeader])).thenReturn(
            Future.successful(Left(ClientDetailsNotFound))
          )

          await(service.findClientDetails("HMRC-PPT-ORG", "XAPPT0004567890")) shouldBe Left(ClientDetailsNotFound)
        }
      }
    }

    "the service is HMRC-CBC-ORG" when {

      "the CBC subscription API returns a successful response" should {

        "return a ClientDetailsResponse if expected data is returned" in {
          when(mockConnector.getCbcSubscriptionDetails(eqTo[String]("XACBC1234567890"))(any[RequestHeader])).thenReturn(
            Future.successful(
              Right(
                SimpleCbcSubscription(
                  Some("CFG Solutions"),
                  Seq(),
                  isGBUser = true,
                  Seq("test@email.com", "test2@email.com")
                )
              )
            )
          )

          val resultModel = ClientDetailsResponse(
            "CFG Solutions",
            None,
            isOverseas = Some(false),
            Seq("test@email.com", "test2@email.com"),
            Some(Email)
          )

          await(service.findClientDetails("HMRC-CBC-ORG", "XACBC1234567890")) shouldBe Right(resultModel)
        }

        "return a ClientDetailsNotFound error if no name is returned" in {
          when(mockConnector.getCbcSubscriptionDetails(eqTo[String]("XACBC1234567890"))(any[RequestHeader])).thenReturn(
            Future.successful(
              Right(
                SimpleCbcSubscription(
                  None,
                  Seq(),
                  isGBUser = true,
                  Seq("test@email.com")
                )
              )
            )
          )

          await(service.findClientDetails("HMRC-CBC-ORG", "XACBC1234567890")) shouldBe Left(ClientDetailsNotFound)
        }

        "return a ClientDetailsNotFound error if no emails are returned" in {
          when(mockConnector.getCbcSubscriptionDetails(eqTo[String]("XACBC1234567890"))(any[RequestHeader])).thenReturn(
            Future.successful(
              Right(
                SimpleCbcSubscription(
                  Some("CFG Solutions"),
                  Seq(),
                  isGBUser = true,
                  Seq()
                )
              )
            )
          )

          await(service.findClientDetails("HMRC-CBC-ORG", "XACBC1234567890")) shouldBe Left(ClientDetailsNotFound)
        }
      }

      "the CBC subscription API returns an unsuccessful response" should {

        "return the same error given by the connector" in {
          when(mockConnector.getCbcSubscriptionDetails(eqTo[String]("XACBC1234567890"))(any[RequestHeader])).thenReturn(
            Future.successful(Left(ClientDetailsNotFound))
          )

          await(service.findClientDetails("HMRC-CBC-ORG", "XACBC1234567890")) shouldBe Left(ClientDetailsNotFound)
        }
      }
    }

    "the service is HMRC-PILLAR2-ORG" when {

      "the Pillar2 API returns a successful response" should {

        "return a ClientDetailsResponse if client is inactive" in {
          when(mockConnector.getPillar2SubscriptionDetails(eqTo[String]("XAPLR2222222222"))(any[RequestHeader]))
            .thenReturn(
              Future.successful(
                Right(
                  Pillar2Record(
                    "CFG Solutions",
                    "2020-01-01",
                    "GB",
                    inactive = true
                  )
                )
              )
            )

          val resultModel = ClientDetailsResponse(
            "CFG Solutions",
            Some(Inactive),
            isOverseas = Some(false),
            Seq("2020-01-01"),
            Some(Date)
          )

          await(service.findClientDetails("HMRC-PILLAR2-ORG", "XAPLR2222222222")) shouldBe Right(resultModel)
        }

        "return a ClientDetailsResponse if client is not inactive" in {
          when(mockConnector.getPillar2SubscriptionDetails(eqTo[String]("XAPLR2222222222"))(any[RequestHeader]))
            .thenReturn(
              Future.successful(
                Right(
                  Pillar2Record(
                    "CFG Solutions",
                    "2020-01-01",
                    "DE",
                    inactive = false
                  )
                )
              )
            )

          val resultModel = ClientDetailsResponse(
            "CFG Solutions",
            None,
            isOverseas = Some(true),
            Seq("2020-01-01"),
            Some(Date)
          )

          await(service.findClientDetails("HMRC-PILLAR2-ORG", "XAPLR2222222222")) shouldBe Right(resultModel)
        }
      }

      "the Pillar2 API returns an unsuccessful response" should {

        "return the same error given by the connector" in {
          when(mockConnector.getPillar2SubscriptionDetails(eqTo[String]("XAPLR2222222222"))(any[RequestHeader]))
            .thenReturn(Future.successful(Left(ClientDetailsNotFound)))

          await(service.findClientDetails("HMRC-PILLAR2-ORG", "XAPLR2222222222")) shouldBe Left(ClientDetailsNotFound)
        }
      }
    }
  }

}

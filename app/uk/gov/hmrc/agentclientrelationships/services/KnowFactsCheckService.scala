/*
 * Copyright 2023 HM Revenue & Customs
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

import uk.gov.hmrc.agentclientrelationships.model.clientDetails.ClientDetailsResponse
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.KnownFactType
import uk.gov.hmrc.agentclientrelationships.model.invitation.ApiCreateInvitationRequest
import uk.gov.hmrc.agentclientrelationships.model.invitation.InvitationFailureResponse

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import scala.util.Try

@Singleton
class KnowFactsCheckService @Inject() {

  private val postcodeWithoutSpacesRegex = "^[A-Za-z]{1,2}[0-9]{1,2}[A-Za-z]?[0-9][A-Za-z]{2}$".r
  private def normalise(postcode: String): String = postcode.replaceAll("\\s", "").toUpperCase

  def checkKnowFacts(
    apiCreateInvitationInputData: ApiCreateInvitationRequest,
    clientDetailsResponse: ClientDetailsResponse
  ): Either[InvitationFailureResponse, Boolean] =
    clientDetailsResponse.knownFactType match {
      case Some(value) =>
        value match {
          case KnownFactType.PostalCode =>
            checkPostCode(
              apiCreateInvitationInputData.knownFact,
              clientDetailsResponse.knownFacts.head,
              clientDetailsResponse.isOverseas.getOrElse(false)
            )
          case KnownFactType.Date => checkDate(apiCreateInvitationInputData.knownFact, clientDetailsResponse.knownFacts.head)
          case KnownFactType.CountryCode | KnownFactType.Email => Left(InvitationFailureResponse.UnsupportedKnowFacts)
        }
      case None => Left(InvitationFailureResponse.UnsupportedKnowFacts)
    }

  private def checkDate(
    suppliedDateStr: String,
    knowFactDateStr: String
  ): Either[InvitationFailureResponse, Boolean] = {
    def getLocalDate(dateStr: String): Either[InvitationFailureResponse, LocalDate] = Try(LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE))
      .fold(_ => Left(InvitationFailureResponse.VatKnownFormatInvalid), x => Right(x))

    (for {
      suppliedDate <- getLocalDate(suppliedDateStr)
      knowFactDate <- getLocalDate(knowFactDateStr)
    } yield suppliedDate == knowFactDate) match {
      case Right(true) => Right(true)
      case Right(false) => Left(InvitationFailureResponse.VatKnownFactNotMatched)
      case Left(err) => Left(err)
    }
  }

  private def checkPostCode(
    suppliedPostCode: String,
    postcode: String,
    isOverseas: Boolean
  ): Either[InvitationFailureResponse, Boolean] =
    if (suppliedPostCode.isEmpty)
      Left(InvitationFailureResponse.PostcodeRequired)
    else {
      postcodeWithoutSpacesRegex
        .findFirstIn(normalise(suppliedPostCode))
        .map { _ =>
          if (isOverseas) {
            Left(InvitationFailureResponse.NotUkAddress)
          }
          else {
            if (normalise(postcode).contains(normalise(suppliedPostCode))) {
              Right(true)
            }
            else {
              Left(InvitationFailureResponse.PostcodeDoesNotMatch)
            }
          }
        }
        .getOrElse {
          Left(InvitationFailureResponse.PostcodeFormatInvalid)
        }
    }

}

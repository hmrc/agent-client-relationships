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

package uk.gov.hmrc.agentclientrelationships.util

import play.api.Logging

import scala.util.matching.Regex

object PostcodeMatchUtil extends Logging {

  val postcodeWithoutSpacesRegex: Regex = "^[A-Za-z]{1,2}[0-9]{1,2}[A-Za-z]?[0-9][A-Za-z]{2}$".r

  def postcodeMatches(submittedPostcode: String, retrievedPostcode: String): Boolean = {

    val sanitisedSubmittedPostcode = submittedPostcode.replaceAll("\\s", "").toUpperCase
    val sanitisedRetrievedPostcode = retrievedPostcode.replaceAll("\\s", "").toUpperCase

    val validPostcodes = postcodeWithoutSpacesRegex.matches(sanitisedRetrievedPostcode) &&
      postcodeWithoutSpacesRegex.matches(sanitisedSubmittedPostcode)

    if (validPostcodes) {
      sanitisedSubmittedPostcode == sanitisedRetrievedPostcode
    } else {
      logger.warn("At least one of the provided postcodes did not adhere to the expected regex")
      false
    }
  }
}

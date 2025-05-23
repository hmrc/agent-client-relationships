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

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

object DateTimeHelper {

  def formatISOInstantSeconds(now: Instant): String = DateTimeFormatter.ISO_INSTANT
    .format(now.truncatedTo(ChronoUnit.SECONDS))

  private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.UK)

  def displayDate(localDate: LocalDate): String = localDate.format(dateFormatter)

  def displayDate(instant: Instant): String = instant
    .atZone(ZoneId.of("Europe/London"))
    .toLocalDate
    .format(dateFormatter)

}

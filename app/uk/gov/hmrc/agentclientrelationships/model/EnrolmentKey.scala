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

package uk.gov.hmrc.agentclientrelationships.model

import play.api.libs.json._
import uk.gov.hmrc.agentclientrelationships.model.identifiers.ClientIdType
import uk.gov.hmrc.agentclientrelationships.model.identifiers.ClientIdentifier
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Identifier
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service
import uk.gov.hmrc.domain.TaxIdentifier

/** An implementation of EnrolmentKey with some extra features to make life easier.
  */
case class EnrolmentKey(
  service: String,
  identifiers: Seq[Identifier]
) {

  lazy val tag = // note: we intentionally do not use the Identifier's toString below because it upper cases everything!
    s"$service~${identifiers.map(identifier => s"${identifier.key}~${identifier.value}").mkString("~")}"

  // TODO: enrolmentKey is a case class with overridden toString which is used to construct urls.
  // This is misleading, difficult to spot, an in general not correct implementation
  // result in errors in connectors
  override def toString: String = tag

  /** Note: unsafe (i.e. can throw exceptions)
    *
    * Supplying no key assumes the service has a single 'supported' identifier! For other enrolments with multiple identifiers you should try and specify which
    * one, or it will grab the first.
    */
  def oneIdentifier(key: Option[String] = None): Identifier = identifiers
    .find(i =>
      i.key ==
        key.getOrElse(
          if (Service.Cbc.id == service) { // would prefer match on supported services but too many 'special' cases
            Service.forId(service).supportedClientIdType.enrolmentId
          }
          else
            identifiers.head.key // fallback to old behaviour
        )
    )
    .getOrElse(throw new IllegalArgumentException(s"No identifier for $key with $service"))

  /* Note: unsafe, see oneIdentifier */
  def oneTaxIdentifier(key: Option[String] = None): TaxIdentifier = {
    val identifier = oneIdentifier(key)
    ClientIdType.supportedTypes.find(_.enrolmentId == identifier.key).get.createUnderlying(identifier.value)
  }

  def serviceType: Service = Service.forId(service)

}

object EnrolmentKey {

  def apply(s: String): EnrolmentKey = parse(s).getOrElse(
    throw new IllegalArgumentException("Invalid enrolment key: " + s)
  )

  def apply(
    service: Service,
    taxIdentifier: TaxIdentifier
  ): EnrolmentKey = EnrolmentKey(service.id, taxIdentifier)

  def apply(
    serviceKey: String,
    taxIdentifier: TaxIdentifier
  ): EnrolmentKey = EnrolmentKey(
    serviceKey,
    Seq(Identifier(ClientIdentifier(taxIdentifier).enrolmentId, taxIdentifier.value))
  )

  def parse(s: String): Option[EnrolmentKey] = {
    val parts = s.split("~")
    if (parts.nonEmpty && parts.size >= 3 && parts.size % 2 == 1) {
      val service = parts.head
      val identifiers = parts.tail.sliding(2, 2).map(a => Identifier(a(0), a(1))).toSeq
      Some(EnrolmentKey(service, identifiers))
    }
    else
      None
  }

  implicit val writes: Writes[EnrolmentKey] =
    new Writes[EnrolmentKey] {
      override def writes(ek: EnrolmentKey): JsValue = JsString(ek.toString)
    }

  implicit val reads: Reads[EnrolmentKey] =
    new Reads[EnrolmentKey] {
      override def reads(json: JsValue): JsResult[EnrolmentKey] =
        json match {
          case JsString(value) => parse(value).fold[JsResult[EnrolmentKey]](JsError("Invalid enrolment key"))(JsSuccess(_))
          case _ => JsError("STRING_VALUE_EXPECTED")
        }
    }

}

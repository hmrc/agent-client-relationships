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

package uk.gov.hmrc.agentclientrelationships.binders

import play.api.mvc.QueryStringBindable
import uk.gov.hmrc.agentclientrelationships.model.{InvitationStatus, UserId}
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.domain.Nino

object PathBinders {
  implicit object ArnBinder extends SimpleObjectBinder[Arn](Arn.apply, _.value)
  implicit object MtdItIdBinder extends SimpleObjectBinder[MtdItId](MtdItId.apply, _.value)
  implicit object NinoBinder extends SimpleObjectBinder[Nino](Nino.apply, _.value)
  implicit object VrnBinder extends SimpleObjectBinder[Vrn](Vrn.apply, _.value)
  implicit object UtrBinder extends SimpleObjectBinder[Utr](Utr.apply, _.value)
  implicit object PptRefBinder extends SimpleObjectBinder[PptRef](PptRef.apply, _.value)
  implicit object CbcIdBinder extends SimpleObjectBinder[CbcId](CbcId.apply, _.value)
  implicit object PlrIdBinder extends SimpleObjectBinder[PlrId](PlrId.apply, _.value)
  implicit object UserIdBinder extends SimpleObjectBinder[UserId](UserId.apply, _.value)
  implicit object ArnQueryBinder extends QueryStringBindable[Arn] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, Arn]] = params
      .get(key)
      .flatMap(_.headOption)
      .map { value =>
        try Right(Arn.apply(value))
        catch {
          case err: Throwable => Left(s"Cannot parse parameter as Arn: [$value] is not a valid Arn")
        }
      }

    override def unbind(key: String, value: Arn): String = s"$key=${Arn.unapply(value)}"
  }
  implicit object InvitationStatusBinder extends QueryStringBindable[InvitationStatus] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, InvitationStatus]] = params
      .get(key)
      .flatMap(_.headOption)
      .map { value =>
        try Right(InvitationStatus.apply(value))
        catch {
          case err: Throwable =>
            Left(
              s"Cannot parse parameter status as InvitationStatus: status of [$value] is not a valid InvitationStatus"
            )
        }
      }

    override def unbind(key: String, value: InvitationStatus): String = s"$key=${InvitationStatus.unapply(value)}"
  }
}

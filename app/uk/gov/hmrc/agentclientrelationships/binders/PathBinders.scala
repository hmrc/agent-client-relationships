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

import uk.gov.hmrc.agentclientrelationships.model.UserId
import uk.gov.hmrc.agentclientrelationships.model.identifiers._
import uk.gov.hmrc.domain.Nino

// scalafmt: { binPack.parentConstructors = Always }
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

}

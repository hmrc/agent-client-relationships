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
import uk.gov.hmrc.agentclientrelationships.model.identifiers.*

// scalafmt: { binPack.parentConstructors = Always }
object PathBinders {

  given ArnBinder: SimpleObjectBinder[Arn] = new SimpleObjectBinder[Arn](Arn.apply, _.value)
  given MtdItIdBinder: SimpleObjectBinder[MtdItId] = new SimpleObjectBinder[MtdItId](MtdItId.apply, _.value)
  given NinoBinder: SimpleObjectBinder[NinoWithoutSuffix] = new SimpleObjectBinder[NinoWithoutSuffix](NinoWithoutSuffix.apply, _.value)
  given VrnBinder: SimpleObjectBinder[Vrn] = new SimpleObjectBinder[Vrn](Vrn.apply, _.value)
  given UtrBinder: SimpleObjectBinder[Utr] = new SimpleObjectBinder[Utr](Utr.apply, _.value)
  given PptRefBinder: SimpleObjectBinder[PptRef] = new SimpleObjectBinder[PptRef](PptRef.apply, _.value)
  given CbcIdBinder: SimpleObjectBinder[CbcId] = new SimpleObjectBinder[CbcId](CbcId.apply, _.value)
  given PlrIdBinder: SimpleObjectBinder[PlrId] = new SimpleObjectBinder[PlrId](PlrId.apply, _.value)
  given UserIdBinder: SimpleObjectBinder[UserId] = new SimpleObjectBinder[UserId](UserId.apply, _.value)

}

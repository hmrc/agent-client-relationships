/*
 * Copyright 2020 HM Revenue & Customs
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
import play.api.libs.json.{JsPath, Json, Reads}
import uk.gov.hmrc.domain.SaAgentReference

case class Agents(agents: Seq[Agent])

case class Agent(hasAgent: Boolean, agentId: Option[SaAgentReference], agentCeasedDate: Option[String])

object Agents {
  implicit val agentReads: Reads[Agent] = Json.reads[Agent]

  implicit val readClientRelationship: Reads[Agents] =
    (JsPath \ "agents")
      .readNullable[Seq[Agent]]
      .map(optionalAgents => Agents(optionalAgents.getOrElse(Seq.empty)))
}

/*
 * Copyright 2026 HM Revenue & Customs
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

import scala.annotation.StaticAnnotation

/** Simple annotation to mark connector methods that call other Agent services APIs.
  *
  * This annotation is purely for documentation and tooling purposes:
  *   - Documents which external APIs are being called
  *   - Allows AI agents and tooling to parse service dependencies
  *   - Enables automated sequence diagram generation
  *   - No compile-time validation or macro expansion
  *
  * @param apiId
  *   The API identifier (e.g., "AA02", "ACR01") from the service-meta.yaml
  * @param service
  *   The target service name (e.g., "agent-assurance", "agent-client-relationships")
  *
  * Example usage:
  * {{{
  * @ConsumesAPI(apiId = "AA02", service = "agent-assurance")
  * def getAgentRecord(arn: Arn)(implicit hc: HeaderCarrier): Future[Option[AgentRecord]] = {
  *   httpClient.GET[Option[AgentRecord]](s"$baseUrl/agent-assurance/agent/$arn")
  * }
  *
  * @ConsumesAPI(apiId = "ACR01", service = "agent-client-relationships")
  * def checkRelationship(arn: Arn, service: String, clientId: String): Future[Boolean] = {
  *   httpClient.GET[Boolean](
  *     s"$baseUrl/agent-client-relationships/agent/$arn/service/$service/client/ni/$clientId"
  *   )
  * }
  *
  * @ConsumesAPI(apiId = "AP01", service = "agent-permissions")
  * def getClientPermissions(arn: Arn, clientId: String): Future[Seq[Permission]] = {
  *   httpClient.GET[Seq[Permission]](s"$baseUrl/agent-permissions/agent/$arn/client/$clientId")
  * }
  * }}}
  *
  * ===AI Agent / Tooling Usage===
  *
  * This annotation can be easily parsed by AI agents or scripts to:
  *
  *   1. **Extract API dependencies:**
  *      {{{
  * // Scala reflection example
  * import scala.reflect.runtime.universe._
  *
  * def findApiCalls(connector: AnyRef): Seq[(String, String)] = {
  *   val mirror = runtimeMirror(connector.getClass.getClassLoader)
  *   val instanceMirror = mirror.reflect(connector)
  *   val symbolType = instanceMirror.symbol.typeSignature
  *
  *   symbolType.members.collect {
  *     case method if method.annotations.exists(_.tree.tpe =:= typeOf[ConsumesAPI]) =>
  *       val annotation = method.annotations
  *         .find(_.tree.tpe =:= typeOf[ConsumesAPI])
  *         .get
  *
  *       // Extract apiId and service from annotation
  *       val args = annotation.tree.children.tail
  *       val apiId = extractString(args(0))
  *       val service = extractString(args(1))
  *
  *       (apiId, service)
  *   }.toSeq
  * }
  *      }}}
  *   2. **Generate sequence diagrams:**
  *      {{{
  * // Python script example
  * import re
  * from pathlib import Path
  *
  * def extract_api_calls(scala_file):
  *     pattern = r'@ConsumesAPI\(apiId\s*=\s*"([^"]+)",\s*service\s*=\s*"([^"]+)"\)'
  *     content = Path(scala_file).read_text()
  *
  *     calls = []
  *     for match in re.finditer(pattern, content):
  *         calls.append({
  *             'api_id': match.group(1),
  *             'service': match.group(2)
  *         })
  *
  *     return calls
  *
  * # Generate Mermaid sequence diagram
  * def generate_sequence_diagram(service_name, api_calls):
  *     diagram = ["sequenceDiagram"]
  *
  *     for call in api_calls:
  *         diagram.append(
  *             f"    {service_name}->>+{call['service']}: {call['api_id']}"
  *         )
  *         diagram.append(
  *             f"    {call['service']}-->>-{service_name}: response"
  *         )
  *
  *     return "\n".join(diagram)
  *      }}}
  *   3. **Build dependency graphs:**
  *      {{{
  * // Find all services that a component depends on
  * val dependencies = findApiCalls(myConnector)
  *   .map(_._2)  // Extract service names
  *   .distinct
  *   .sorted
  *
  * println(s"This service depends on: ${dependencies.mkString(", ")}")
  *      }}}
  *   4. **Generate API documentation:**
  *      {{{
  * # AI prompt example:
  * "Scan all @ConsumesAPI annotations in agent-permissions service
  *  and generate a markdown table showing:
  *  - Connector class
  *  - Method name
  *  - Target service
  *  - API ID called
  *  - HTTP method (inferred from method body)"
  *      }}}
  */
class ConsumesAPI(
  val apiId: String,
  val service: String
)
extends StaticAnnotation

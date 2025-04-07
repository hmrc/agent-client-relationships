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

package uk.gov.hmrc.agentclientrelationships.util

import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.time.{Seconds, Span}
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import scala.concurrent.{ExecutionContext, Future}

class HttpApiMonitorSpec extends UnitSpec with GuiceOneServerPerSuite with HttpApiMonitor {

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(scaled(Span(30, Seconds)), scaled(Span(2, Seconds)))

  override lazy val app: Application = appBuilder.build()

  protected def appBuilder: GuiceApplicationBuilder =
    new GuiceApplicationBuilder().configure("metrics.enabled" -> true)

  val metrics: Metrics = app.injector.instanceOf[Metrics]
  val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]

  ".monitor" should {

    "create a metrics Timer event with the provided name" in {
      metrics.defaultRegistry.remove("Timer-Fake-API-Call")

      monitor("Fake-API-Call") {
        Future.successful(1)
      }

      eventually {
        metrics.defaultRegistry.getTimers.get("Timer-Fake-API-Call").getCount should be >= 1L
      }
    }
  }
}

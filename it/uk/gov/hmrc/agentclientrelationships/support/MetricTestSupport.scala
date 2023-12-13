package uk.gov.hmrc.agentclientrelationships.support

import com.codahale.metrics.MetricRegistry
import org.scalatest.matchers.should.Matchers
import org.scalatest.{AppendedClues, Assertion, Suite}
import play.api.Application

import scala.jdk.CollectionConverters._

trait MetricTestSupport extends AppendedClues {
  self: Suite with Matchers =>

  def app: Application

  def givenCleanMetricRegistry(): Unit = {
    val registry = app.injector.instanceOf[MetricRegistry]
    for (metric <- registry.getMetrics.keySet().iterator().asScala) {
      registry.remove(metric)
    }
  }

  def timerShouldExistsAndBeenUpdated(metric: String): Assertion = {
    val timerName = s"Timer-$metric"
    val timer = app.injector.instanceOf[MetricRegistry].getTimers.get(timerName)
    timer should not be (null) withClue (s" - Not found: timer $timerName") // more useful error message
    timer.getCount should be >= 1L
  }
}

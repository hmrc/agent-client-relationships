package uk.gov.hmrc.agentclientrelationships.support

import com.codahale.metrics.MetricRegistry
import com.kenshoo.play.metrics.Metrics
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Assertion, Suite}
import play.api.Application

import scala.collection.JavaConverters

trait MetricTestSupport {
  self: Suite with Matchers =>

  def app: Application

  private var metricsRegistry: MetricRegistry = _

  def givenCleanMetricRegistry(): Unit = {
    val registry = app.injector.instanceOf[Metrics].defaultRegistry
    for (metric <- JavaConverters.asScalaIterator[String](registry.getMetrics.keySet().iterator())) {
      registry.remove(metric)
    }
    metricsRegistry = registry
  }

  def timerShouldExistsAndBeenUpdated(metric: String): Assertion =
    metricsRegistry.getTimers.get(s"Timer-$metric").getCount should be >= 1L

}

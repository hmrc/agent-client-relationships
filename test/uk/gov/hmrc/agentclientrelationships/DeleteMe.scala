package uk.gov.hmrc.agentclientrelationships

import java.net.URL
import java.time.Clock
import scala.concurrent.ExecutionContext

trait T1
trait T2
trait T3

class DeleteMe(p: String, p2: String, p3: String)(implicit clock: Clock, executionContext: ExecutionContext)
extends T1
with T2
with T3 {

  def f(s: String, b: String)(implicit i: Int, i2: Int): String =
    if (i < 2)
      new URL(s + b + i + i2).toString.trim.toLowerCase()
    else
      "sialala"

  val x: String = f("s", "b")(1, 2)
}

package uk.gov.hmrc.agentclientrelationships

import java.net.URL
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext

trait T1
trait T2
trait T3

class Dm1(
  a: Any,
  b: Any
)

class Dm2(
  a: Any,
  b: Any
)
extends Dm1(a, b)

object X
extends T1

class Dm3(
  a: Any,
  b: Any,
  c: Any
)
extends Dm1(a, b)

class Dm4(
  a: Any,
  b: Any,
  c: Any
)
extends Dm2(a, c)
with T1
with T2

class DeleteMeOne
extends T1
with T2
with T3

class DeleteMe2(
  p: String,
  p2: String,
  p3: String
)(implicit
  clock: Clock,
  executionContext: ExecutionContext,
  url: java.net.URL
)

class DeleteMe3(
  p: String,
  p2: String,
  p3: String
)(implicit
  clock: Clock,
  executionContext: ExecutionContext,
  url: java.net.URL
)
extends DeleteMe2(
  p,
  p2,
  p3
)
with T1
with T2

@Singleton()
@deprecated("asdf", "asdf")
abstract class DeleteMe @Inject() (p: String)(implicit
  clock: Clock,
  executionContext: ExecutionContext,
  url: URL
)
extends T1
with T2
with T3 {

  def method(
    a: Int,
    b: String
  ): Boolean

  def f(
    s: String,
    b: String,
    c: String = "|"
  )(implicit
    i: Int,
    i2: Int
  ): String =
    if (i < 2)
      new URL(s + b + i + i2).toString.trim.toLowerCase()
    else
      "sialala"

  def f2(
    s: String,
    b: String,
    c: String = "|"
  )(implicit
    i: Int,
    i2: Int
  ): String = {
    if (i < 2) {

      (
        new URL(s + b + i + i2)
          .toString
          .toString
          .toString
          .toString
          .toString
          .toString
          .toString
          .toString
          .toString
          .toString
          .toString
          .toString
          .toString
          .toString
          .toString
          .toString
          .toString.toString
          .toString
          .toString
          .toString
          .toString
          .toString
          .toString
          .toString
          .toString
          .toString
          .toString
          .toString
          .toString
          .toString
          .toString
          .toString
          .trim
          .trim
      ).trim

      new URL(s + b + i + i2).toString.toLowerCase().toLowerCase().toLowerCase().toLowerCase()

      List(1)
        .toIterator
        .buffered
        .map(_ + 2).filter(_ > 2)

      new URL(s + b + i + i2).toString.trim
    }
    if (i < 3) {
      // test for newlines.avoidForSimpleOverflow = [slc]
      new URL(s + b + i + i2).toString.trim // very long comment which might case the line break ........................asdf asdfasd
    }
    else {
      "sialala"
    }
  }

  def f(
    s: String,
    s2: String
  ): String = s.toString.trim.toLowerCase()

  def f3(
    s: String,
    s2: String,
    s3: String
  ): String = s.toString.trim.toLowerCase()

  class ClassT1[T1](
    s: String,
    s2: String
  )

  f("1", "2")

  f3(
    "1",
    "2",
    "3"
  )

  val x123 = 123

  private implicit final lazy val x22 = 42
  private implicit final lazy val y22 = 42

  class ClassT2[
    T1,
    T2
  ]()

  class ClassT3[
    T1,
    T2,
    T3
  ]()

  class BaseDataSource[
    TD,
    EI,
    Q,
    A
  ]()

  abstract class Engine[
    TD_____________________________________1,
    EI,
    PD,
    Q,
    P,
    A
  ](
    val dataSourceClassMap: Map[String, Class[_ <: BaseDataSource[
      TD_____________________________________1,
      EI,
      Q,
      A
    ]]]
  ) {
    val dataSourceClassMapasdfasdfsdfsd2: Map[String, Class[_ <: BaseDataSource[
      TD_____________________________________1,
      EI,
      Q,
      A
    ]]]

  }

  def x[Engine[
    TD_____________________________________1,
    EI,
    PD,
    Q,
    P,
    A
  ]] = 1

  val x: String = f("s", "b")(1, 2)

  val tuple = (1, 2, 3, 43, 54, 1, 5, 6, 67)

  val list123 = List(
    1,
    2,
    3
  )
  val list1 = List(1)
  val list12 = List(1, 2)

  val list = List(
    1,
    2,
    3,
    43,
    54,
    1,
    5,
    6,
    67
  )

  List(1)
    .map { x =>
      x + 2
    }
    .filter(_ > 2)

  val multiline: String =
    s"""asdf
       |asdfa
       |asdf
       |asdf ${"asdf".trim.trim.trim.trim.trim.trim.trim.trim.trim.trim.trim.trim.trim.trim.trim.trim.trim.trim.trim.trim.trim}
       |asd
       |""".stripMargin

}

class IntString(
  int: Int,
  string: String
)

class IntStringLong(
  int: Int,
  string: String,
  long: Long
) {

  println("asdf") match {
    case 1 =>
      println("aialala")
      1
    case 2 => 2
  }

  def func(
    a: Any,
    b: Any
  ) = a

  def foo = func(123, "asdf")

  val foo1 = func(a = foo, b = "bar")

  def foo2 = {
    def a = func(foo, "bar")

    val b = func(foo, "bar")
  }

  val secret: List[Int] = List(
    0,
    0,
    1,
    1,
    1,
    1,
    1,
    0,
    0,
    1,
    1,
    0,
    1
  )

}

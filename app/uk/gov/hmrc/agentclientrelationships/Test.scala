package uk.gov.hmrc.agentclientrelationships

object Test {

//  case class Arn(value: String)
//  case class Vrn(value: String)
//  case class Utr(value: String)

  val utr = "utr"
  val vrn = "vrn"
  val arn = "arn"

  val b: Boolean = true

  sealed trait Status
  case object Option1 extends Status
  case object Option2 extends Status
  case object Option3 extends Status
  case object Option4 extends Status

  def f(s: Status) = s match {
    case Option1 => println("")
    case Option2 => println("")
    _ => println("avoid")
  }

  def f2(s: Status) = s match {
    case Option1 => println("")
    case Option2 => println("")
  }




  def doSomething(
                   utr: String,
                   arn: String,
                   vrn: String): String = {
    s"utr=$utr, vrn=$arn, arn=$vrn"
  }

  doSomething(
    utr,
    vrn,
    arn
  ) shouldBe "utr=utr, vrn=vrn, arn=arn"

}


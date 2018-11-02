package uk.gov.hmrc.agentclientrelationships.support

import java.util.UUID

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Scheduler}
import org.joda.time.{DateTime, LocalDate}
import play.api.{Configuration, Environment}
import reactivemongo.bson.Subtype.UuidSubtype
import uk.gov.hmrc.agentclientrelationships.repository.MongoRecoveryScheduleRepository

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

case class Message(message: String)

class DeauthorisationScheduler(mongoRecoveryScheduleRepository: MongoRecoveryScheduleRepository) extends Actor {

  val receiveActor = context.system.actorOf(Receiver.props)

  def whatTheHell(implicit ex: ExecutionContext) =
    context.system.scheduler.scheduleOnce(50 milliseconds, receiveActor, "UUID")

  def receive = {
    case Message(uid) =>
      mongoRecoveryScheduleRepository.findBy(uid).flatMap {
        case Some(record) =>
          mongoRecoveryScheduleRepository.update(record.uid, UUID.randomUUID().toString, DateTime.now().toString())
        case None => ???
      }
    // call de authorise
  }

}

object Receiver {
  def props: Props = Props[Receiver]
}

class Receiver extends Actor with ActorLogging {
  import Receiver._

  def receive = {
    case uuid => log.info("UUID received")
  }

}

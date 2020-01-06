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

package uk.gov.hmrc.agentclientrelationships.modules

import java.time.{Duration, LocalDateTime}

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import com.google.inject.AbstractModule
import com.mongodb.spark.MongoSpark
import javax.inject.{Inject, Named}
import org.apache.spark.sql.SparkSession
import org.joda.time
import play.api.Logger
import uk.gov.hmrc.lock.{LockKeeper, LockRepository}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class MongoMigrationScheduler @Inject()(
  @Named("mongo.input.collection.uri") inputCollectionuri: String,
  @Named("mongo.output.collection.uri") outputCollectionuri: String,
  @Named("mongo.migration.enabled") enabled: Boolean,
  actorSystem: ActorSystem,
  lockRepository: LockRepository)(implicit executor: ExecutionContext) {

  if (enabled) {
    Logger(getClass).info("mongo migration scheduler is enabled.")
    val actor: ActorRef = actorSystem.actorOf(Props {
      new MigrationActor(inputCollectionuri, outputCollectionuri, lockRepository)
    })
    actorSystem.scheduler.scheduleOnce(
      20.seconds,
      actor,
      "<start mongo migration scheduler>"
    )
  } else
    Logger(getClass).warn("mongo migration scheduler is not enabled.")
}

class MigrationActor(inputCollectionUri: String, outputCollectionUri: String, lockRepository: LockRepository)(
  implicit executor: ExecutionContext)
    extends Actor {

  val lockKeeper = new LockKeeper {
    override def repo: LockRepository = lockRepository

    override def lockId: String = MongoMigrationScheduler.lock

    override val forceLockReleaseAfter: time.Duration = time.Duration.standardHours(1)
  }

  override def receive: Receive = {
    case _: String =>
      val _ = lockKeeper.tryLock {
        Logger(getClass).warn("starting mongo migration scheduler")
        Try {
          Logger(getClass).warn(s"inputCollectionUri = $inputCollectionUri")
          Logger(getClass).warn(s"outputCollectionUri = $outputCollectionUri")
          val sparkSession = SparkSession
            .builder()
            .master("local")
            .appName("MongoCollectionMigration")
            .config("spark.mongodb.input.uri", inputCollectionUri)
            .config("spark.mongodb.output.uri", outputCollectionUri)
            .getOrCreate()

          val df = MongoSpark.load(sparkSession)
          df.printSchema()
          MongoSpark.save(df.write.mode("overwrite"))
        } match {
          case Success(_) =>
            Logger(getClass).warn("mongo migration scheduler finished")

            val now = LocalDateTime.now()
            val nextRunAt = now.plusDays(1).toLocalDate.atStartOfDay()
            val delay = Duration.between(now, nextRunAt).getSeconds

            context.system.scheduler
              .scheduleOnce(delay.seconds, self, "<start mongo migration scheduler>")
            Logger(getClass)
              .info(s"Starting mongo migration scheduler job, next job is scheduled at $nextRunAt")
          case Failure(exception) =>
            Logger(getClass).warn(s"mongo migration scheduler failed with", exception)
        }

        Future.successful(())
      }
  }
}

class MongoMigrationSchedulerModule extends AbstractModule {

  override def configure(): Unit = bind(classOf[MongoMigrationScheduler]).asEagerSingleton()

}

object MongoMigrationScheduler {
  val lock = "MONGO-MIGRATION-LOCK"
}

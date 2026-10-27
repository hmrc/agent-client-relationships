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

package uk.gov.hmrc.agentclientrelationships.services

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.scalatest.BeforeAndAfterEach
import org.scalatestplus.mockito.MockitoSugar
import play.api.mvc.RequestHeader
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.config.KeyRotationConfig
import uk.gov.hmrc.agentclientrelationships.repository.PartialAuthRepository
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec
import uk.gov.hmrc.mongo.lock.Lock
import uk.gov.hmrc.mongo.lock.MongoLockRepository

import java.time.Instant
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.Deadline
import scala.concurrent.duration.DurationInt

class KeyRotationServiceSpec
extends UnitSpec
with MockitoSugar
with BeforeAndAfterEach:

  given ExecutionContext = ExecutionContext.Implicits.global

  private val mockLockRepository = mock[MongoLockRepository]
  private val mockAppConfig = mock[AppConfig]
  private val mockPartialAuthRepository = mock[PartialAuthRepository]

  private val keyRotationConfig = KeyRotationConfig(
    enabled = true,
    maxTotalDuration = 10.seconds,
    batchSize = 1
  )

  private def createService() =
    new KeyRotationService(
      mockLockRepository,
      mockAppConfig,
      mockPartialAuthRepository
    )

  override protected def beforeEach(): Unit =
    super.beforeEach()
    reset(
      mockLockRepository,
      mockAppConfig,
      mockPartialAuthRepository
    )

  "KeyRotationService" when:

    "key rotation is disabled" should:
      "skip migration and complete without running repository migrations" in:
        when(mockAppConfig.keyRotation).thenReturn(keyRotationConfig.copy(enabled = false))

        createService().migration.futureValue shouldBe None

        verify(mockLockRepository, never()).takeLock(
          any[String],
          any[String],
          any()
        )
        verify(mockLockRepository, never()).releaseLock(any[String], any[String])
        verify(mockPartialAuthRepository, never()).migrate(any[Int], any[Deadline])(using any[RequestHeader])

    "key rotation is enabled" should:
      "run the partial auth migration when the lock is acquired" in:
        when(mockAppConfig.keyRotation).thenReturn(keyRotationConfig)
        when(mockLockRepository.takeLock(
          any[String],
          any[String],
          any()
        ))
          .thenReturn(Future.successful(Some(Lock(
            "",
            "",
            Instant.now(),
            Instant.MAX
          ))))
        when(mockLockRepository.releaseLock(any[String], any[String]))
          .thenReturn(Future.unit)
        when(mockPartialAuthRepository.migrate(any[Int], any[Deadline])(using any[RequestHeader]))
          .thenReturn(Future.successful(20))

        createService().migration.futureValue shouldBe Some(())

        verify(mockPartialAuthRepository).migrate(any[Int], any[Deadline])(using any[RequestHeader])

      "skip migration when the lock is not acquired" in:
        when(mockAppConfig.keyRotation).thenReturn(keyRotationConfig)
        when(mockLockRepository.takeLock(
          any[String],
          any[String],
          any()
        ))
          .thenReturn(Future.successful(None))

        createService().migration.futureValue shouldBe None

        verify(mockLockRepository, never()).releaseLock(any[String], any[String])
        verify(mockPartialAuthRepository, never()).migrate(any[Int], any[Deadline])(using any[RequestHeader])

      "fail when the partial auth migration fails" in:
        when(mockAppConfig.keyRotation).thenReturn(keyRotationConfig)
        when(mockLockRepository.takeLock(
          any[String],
          any[String],
          any()
        ))
          .thenReturn(Future.successful(Some(Lock(
            "",
            "",
            Instant.now(),
            Instant.MAX
          ))))
        when(mockLockRepository.releaseLock(any[String], any[String]))
          .thenReturn(Future.unit)
        when(mockPartialAuthRepository.migrate(any[Int], any[Deadline])(using any[RequestHeader]))
          .thenReturn(Future.failed(RuntimeException("partial auth migration failed")))

        createService().migration.failed.futureValue

        verify(mockPartialAuthRepository).migrate(any[Int], any[Deadline])(using any[RequestHeader])

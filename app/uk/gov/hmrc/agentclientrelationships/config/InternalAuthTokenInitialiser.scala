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

package uk.gov.hmrc.agentclientrelationships.config

import org.apache.pekko.Done
import play.api.Logging
import uk.gov.hmrc.agentclientrelationships.util.RequestAwareLogging
import play.api.http.Status.CREATED
import play.api.libs.json.Json
import play.api.mvc.RequestHeader
import uk.gov.hmrc.http.HttpReads.Implicits.readRaw
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.StringContextOps

import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.duration.DurationInt
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** Auth token initialiser is required on local test/dev environment, where it is not possible to preconfigure application with the auth token. It has to be
  * obtained during runtime. On production environment, the auth-token is pre-registered and hardcoded in config. This cannot be done on test environments, such
  * design ...
  */
@Singleton
class InternalAuthTokenInitialiser @Inject() (
  appConfig: AppConfig,
  httpClient: HttpClientV2
)(implicit
  ec: ExecutionContext
)
extends Logging {

  Await.result(
    {
      logger.info("Auth token initialising ...")
      if (appConfig.internalAuthTokenEnabled) {
        ensureAuthToken()
          .map(_ => logger.info("Auth token initialised"))
      }
      else {
        logger.info("Auth token initialising - skipped")
        Future.successful(())
      }
    },
    30.seconds
  )

  private def ensureAuthToken(): Future[Done] = isAuthTokenValid.flatMap { isValid =>
    if (isValid) {
      logger.info("Auth token is already valid")
      Future.successful(Done)
    }
    else {
      createClientAuthToken()
    }
  }

  private def createClientAuthToken(): Future[Done] = {
    logger.info("Creating auth token...")
    httpClient
      .post(url"${appConfig.internalAuthBaseUrl}/test-only/token")(HeaderCarrier())
      .withBody(
        Json.obj(
          "token" -> appConfig.internalAuthToken,
          "principal" -> appConfig.appName,
          "permissions" ->
            Seq(
              Json.obj(
                "resourceType" -> "agent-assurance",
                "resourceLocation" -> "agent-record-with-checks/arn",
                "actions" -> Seq("WRITE")
              )
            )
        )
      )
      .execute
      .flatMap { response =>
        if (response.status == CREATED) {
          logger.info("Auth token initialised")
          Future.successful(Done)
        }
        else {
          Future.failed(new RuntimeException("Unable to initialise internal-auth token"))
        }
      }

  }

  private def isAuthTokenValid: Future[Boolean] = {
    logger.info("Checking auth token")
    httpClient
      .get(url"${appConfig.internalAuthBaseUrl}/test-only/token")(HeaderCarrier())
      .setHeader("Authorization" -> appConfig.internalAuthToken)
      .execute
      .map(_.status == 200)
  }

}

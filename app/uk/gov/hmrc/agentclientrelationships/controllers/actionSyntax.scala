/*
 * Copyright 2017 HM Revenue & Customs
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

package uk.gov.hmrc.agentclientrelationships.controllers

import cats.syntax.either._
import play.api.libs.json.{JsObject, Json}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

object actionSyntax {

  type Result[T] = Either[(Throwable, String), T]

  implicit class FutureOps[T](val f: Future[T]) extends AnyVal {

    def orRaiseError(code: String): FutureOfEither[T] =
      FutureOfEither(f map Right.apply recover { case NonFatal(e) => Left((e, code)) })

    def orFail: FutureOfEither[T] = FutureOfEither(f map Right.apply)
  }

  final case class FutureOfEither[T](value: Future[Result[T]]) {

    def map[S](f: T => S)(implicit executor: ExecutionContext): FutureOfEither[S] = {
      val p = Promise[Result[S]]()
      value.onComplete {
        case f: Failure[_] =>
          p complete f.asInstanceOf[Failure[Result[S]]]
        case Success(v) => v match {
          case Left(failure) => p complete Success(Either.left(failure))
          case Right(a) => p complete Success(Right(f(a)))
        }
      }
      FutureOfEither(p.future)
    }

    def flatMap[S](f: T => FutureOfEither[S])(implicit executor: ExecutionContext): FutureOfEither[S] = {
      val p = Promise[Result[S]]()
      value.onComplete {
        case f: Failure[_] =>
          p complete f.asInstanceOf[Failure[Result[S]]]
        case Success(v) => v match {
          case Left(failure) => p complete Success(Either.left(failure))
          case Right(a) => f(a).value.onComplete(v => p complete v)
        }
      }
      FutureOfEither(p.future)
    }

    def fold[S](f: Result[T] => S): Future[S] = value map f

  }

  def success[T](a: T): FutureOfEither[T] = FutureOfEither(Future.successful(Right(a)))

  def raiseError(code: String) = FutureOfEither(Future.successful(Left((new Exception, code))))

  def toJson(ex: Throwable, code: String): JsObject = {
    val message = Option(ex.getMessage)
      .flatMap(m => if (m.trim().isEmpty) None else Some(m))
      .map(m => Json.obj("message" -> m)).getOrElse(Json.obj())
    Json.obj("code" -> code) ++ message
  }

}

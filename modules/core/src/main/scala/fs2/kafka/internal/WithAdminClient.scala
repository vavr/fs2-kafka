/*
 * Copyright 2018-2019 OVO Energy Limited
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

package fs2.kafka.internal

import cats.effect.{Blocker, Concurrent, ContextShift, Resource}
import cats.implicits._
import fs2.kafka.AdminClientSettings
import fs2.kafka.internal.syntax._
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.common.KafkaFuture

private[kafka] sealed abstract class WithAdminClient[F[_]] {
  def apply[A](f: AdminClient => KafkaFuture[A]): F[A]
}

private[kafka] object WithAdminClient {
  def apply[F[_]](
    settings: AdminClientSettings[F]
  )(
    implicit F: Concurrent[F],
    context: ContextShift[F]
  ): Resource[F, WithAdminClient[F]] = {
    val blockerResource =
      settings.blocker
        .map(Resource.pure[F, Blocker])
        .getOrElse(Blockers.adminClient)

    blockerResource.flatMap { blocker =>
      Resource[F, WithAdminClient[F]] {
        settings.createAdminClient.map { adminClient =>
          val withAdminClient =
            new WithAdminClient[F] {
              override def apply[A](f: AdminClient => KafkaFuture[A]): F[A] =
                context.blockOn(blocker) {
                  F.suspend(f(adminClient).cancelable)
                }
            }

          val close =
            context.blockOn(blocker) {
              F.delay(adminClient.close(settings.closeTimeout.asJava))
            }

          (withAdminClient, close)
        }
      }
    }
  }
}

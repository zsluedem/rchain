package coop.rchain.rspace.history.syntax

import cats.effect.Sync
import cats.syntax.all._
import coop.rchain.rspace.channelStore.{ContinuationHash, DataJoinHash}
import coop.rchain.rspace.history.HistoryRepository
import coop.rchain.rspace.syntax.syntaxHistoryRepository
import coop.rchain.rspace.{internal, Blake2b256Hash}

import scala.language.{higherKinds, implicitConversions}

trait HistoryRepositorySyntax {
  implicit final def syntaxHistoryRepository[F[_]: Sync, C, P, K, A](
      historyRepo: HistoryRepository[F, C, P, K, A]
  ): HistoryRepositoryOps[F, C, P, K, A] =
    new HistoryRepositoryOps[F, C, P, K, A](historyRepo)
}
final class HistoryRepositoryOps[F[_]: Sync, C, P, K, A](
    private val historyRepo: HistoryRepository[F, C, P, K, A]
) {
  def getData(state: Blake2b256Hash, channel: C): F[Seq[internal.Datum[K]]] =
    historyRepo.reset(state) >>= { h =>
      h.getData(channel)
    }

  def getJoins(state: Blake2b256Hash, channel: C): F[Seq[Seq[C]]] =
    historyRepo.reset(state) >>= { h =>
      h.getJoins(channel)
    }

  def getContinuations(
      state: Blake2b256Hash,
      channels: Seq[C]
  ): F[Seq[internal.WaitingContinuation[P, A]]] =
    historyRepo.reset(state) >>= { h =>
      h.getContinuations(channels)
    }

  def getData(
      state: Blake2b256Hash,
      dataHash: Blake2b256Hash
  ): F[Seq[internal.Datum[K]]] =
    historyRepo.reset(state) >>= { h =>
      h.getData(dataHash)
    }

  def getJoins(state: Blake2b256Hash, joinHash: Blake2b256Hash): F[Seq[Seq[C]]] =
    historyRepo.reset(state) >>= { h =>
      h.getJoins(joinHash)
    }

  def getContinuations(
      state: Blake2b256Hash,
      continuationHash: Blake2b256Hash
  ): F[Seq[internal.WaitingContinuation[P, A]]] =
    historyRepo.reset(state) >>= { h =>
      h.getContinuations(continuationHash)
    }

  def getDataFromChannelHash(
      channelHash: Blake2b256Hash
  ): F[Seq[internal.Datum[K]]] =
    for {
      maybeDataHash <- historyRepo.getChannelHash(channelHash)
      dataHash <- maybeDataHash match {
                   case Some(DataJoinHash(dataHash, _)) => dataHash.pure[F]
                   case _ =>
                     Sync[F].raiseError[Blake2b256Hash](
                       new Exception(s"not found data hash for $channelHash in channel store")
                     )
                 }
      data <- historyRepo.getData(dataHash)
    } yield data

  def getDataFromChannelHash(
      state: Blake2b256Hash,
      channelHash: Blake2b256Hash
  ): F[Seq[internal.Datum[K]]] =
    historyRepo.reset(state) >>= { h =>
      h.getDataFromChannelHash(channelHash)
    }

  def getJoinsFromChannelHash(
      channelHash: Blake2b256Hash
  ): F[Seq[Seq[C]]] =
    for {
      maybeJoinHash <- historyRepo.getChannelHash(channelHash)
      joinHash <- maybeJoinHash match {
                   case Some(DataJoinHash(_, joinHash)) => joinHash.pure[F]
                   case _ =>
                     Sync[F].raiseError[Blake2b256Hash](
                       new Exception(s"not found join hash for $channelHash in channel store")
                     )
                 }
      data <- historyRepo.getJoins(joinHash)
    } yield data

  def getJoinsFromChannelHash(
      state: Blake2b256Hash,
      channelHash: Blake2b256Hash
  ): F[Seq[Seq[C]]] =
    historyRepo.reset(state) >>= { h =>
      h.getJoinsFromChannelHash(channelHash)
    }
  def getContinuationFromChannelHash(
      channelHash: Blake2b256Hash
  ): F[Seq[internal.WaitingContinuation[P, A]]] =
    for {
      maybeContinuationHash <- historyRepo.getChannelHash(
                                channelHash
                              )
      continuationHash <- maybeContinuationHash match {
                           case Some(ContinuationHash(continuationHash)) => continuationHash.pure[F]
                           case _ =>
                             Sync[F].raiseError[Blake2b256Hash](
                               new Exception(
                                 s"not found continuation hash for $channelHash in channel store"
                               )
                             )
                         }
      continuations <- historyRepo.getContinuations(continuationHash)
    } yield continuations

  def getContinuationFromChannelHash(
      state: Blake2b256Hash,
      channelHash: Blake2b256Hash
  ): F[Seq[internal.WaitingContinuation[P, A]]] =
    historyRepo.reset(state) >>= { h =>
      h.getContinuationFromChannelHash(channelHash)
    }
}

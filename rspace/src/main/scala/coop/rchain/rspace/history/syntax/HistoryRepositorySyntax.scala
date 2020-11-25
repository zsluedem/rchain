package coop.rchain.rspace.history.syntax

import cats.effect.Sync
import cats.syntax.all._
import coop.rchain.rspace.channelStore.{ContinuationHash, DataJoinHash}
import coop.rchain.rspace.history.HistoryRepository
import coop.rchain.rspace.syntax.syntaxHistoryRepository
import coop.rchain.rspace.trace.Event
import coop.rchain.rspace.trace.Event.{
  containConflictingEvents,
  extractJoinedChannels,
  extractRSpaceEventGroup,
  findNoOpChannelOnJoin,
  produceChannels,
  produceInCommChannels,
  Conflict,
  IsConflict,
  NonConflict
}
import coop.rchain.rspace.{internal, Blake2b256Hash, StableHashProvider}
import coop.rchain.shared.Serialize

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

  /**
    * detect conflict events between rightEvents and rightEvents.
    * In conflict case, conflict set contains the conflict channel hash.
    *
    * Some discussions are in https://github.com/rchain/rchain/issues/3139
    * @param baseState baseState needed here for detect conflict in joins
    * @return
    */
  def isConflict(
      baseState: Blake2b256Hash,
      mainEvents: List[Event],
      mergingEvents: List[Event]
  )(implicit sc: Serialize[C]): F[IsConflict] = {
    // eventGroup doesn't contain volatile events
    val mainEventGroup    = extractRSpaceEventGroup(mainEvents)
    val mergingEventGroup = extractRSpaceEventGroup(mergingEvents)

    val conflictJoinInMain =
      extractJoinedChannels(mainEventGroup).intersect(produceChannels(mergingEventGroup))
    val conflictJoinInMerging =
      extractJoinedChannels(mergingEventGroup).intersect(produceChannels(mainEventGroup))

    val otherConflict          = containConflictingEvents(mainEventGroup, mergingEventGroup)
    val normalConflictChannels = conflictJoinInMain ++ conflictJoinInMerging ++ otherConflict
    // only look at merging side for better performance
    // This should be check for case like below
    //   @1!(0)      @0!(0)
    //      \         /
    //     for (_ <- @0;_ <- @1) { 0 }
    val nonConflictMergingProduceChannels =
      mergingEventGroup.produces.filter(p => !normalConflictChannels.contains(p.channelsHash))
    for {
      rightJoins <- nonConflictMergingProduceChannels.toList.traverse { produce =>
                     for {
                       joins <- historyRepo.getJoinsFromChannelHash(
                                 baseState,
                                 produce.channelsHash
                               )
                       joinM = joins.filter(_.length > 1)
                     } yield (produce, joinM)
                   }
      leftProduceChannel = mainEventGroup.produces.map(_.channelsHash).toSet
      conflictJoinChannels = rightJoins
        .filter {
          case (produce, joins) => {
            val joinsChannelHashes  = joins.map(_.map(StableHashProvider.hash(_)(sc))).flatten
            val joinsWithoutProduce = joinsChannelHashes diff Seq(produce.channelsHash)
            joinsWithoutProduce.exists(p => leftProduceChannel.contains(p))
          }
        }
        .map(_._1.channelsHash)
        .toSet

      // The case below between "=" won't be concerned because we are not clear about
      //  whether join should be lazy or greedy and we consider any produce on channels in joins are conflict.
      // =================================================================
//      // below are trying to solve conflict like
//      //   @1!(0)      for (_ <- @0;_ <- @1) { 0 }
//      //      \         /
//      //        @0!(0)
//      noOpChannelOnJoinsMain    = findNoOpChannelOnJoin(mainEventGroup, mergingEventGroup)
//      noOpChannelOnJoinsMerging = findNoOpChannelOnJoin(mergingEventGroup, mainEventGroup)
//      conflictJoins <- noOpChannelOnJoinsMain.toList.filterA { jc =>
//                        jc.nonProducedChannel.toList.forallM { channelHash =>
//                          for {
//                            dataAtBase <- historyRepo.getDataFromChannelHash(baseState, channelHash)
//                          } yield dataAtBase.nonEmpty
//                        }
//                      }
//      conflictProduces <- noOpChannelOnJoinsMerging.toList.filterA { jc =>
//                           jc.nonProducedChannel.toList.forallM { channelHash =>
//                             for {
//                               dataAtBase <- historyRepo.getDataFromChannelHash(
//                                              baseState,
//                                              channelHash
//                                            )
//                             } yield dataAtBase.nonEmpty
//                           }
//                         }
//      conflictJoinsAtMerge = conflictProduces
//        .flatMap(_.nonProducedChannel)
//        .toSet ++ conflictJoins.flatMap(_.producedChannel).toSet
      // =================================================================
    } yield
      if (normalConflictChannels.isEmpty && conflictJoinChannels.isEmpty) {
        NonConflict(mainEventGroup, mergingEventGroup)
      } else {
        Conflict(
          mainEventGroup,
          mergingEventGroup,
          normalConflictChannels ++ conflictJoinChannels
        )
      }
  }
}

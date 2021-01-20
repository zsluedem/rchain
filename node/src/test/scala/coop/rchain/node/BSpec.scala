package coop.rchain.node

import java.nio.file.Path

import cats.effect.Sync
import cats.implicits.{catsSyntaxApplicativeId, toFoldableOps}
import com.google.protobuf.ByteString
import coop.rchain.blockstorage.dag.BlockDagKeyValueStorage
import coop.rchain.blockstorage.dag.codecs.{codecBlockHash, codecBlockMetadata}
import coop.rchain.blockstorage.util.io.IOError.RaiseIOError
import coop.rchain.blockstorage.util.io._
import coop.rchain.blockstorage.{BlockStore, FileLMDBIndexBlockStore, KeyValueBlockStore}
import coop.rchain.casper.engine.EngineCell
import coop.rchain.casper.protocol.{toCasperMessageProto, BlockMessage, CasperMessage}
import coop.rchain.casper.storage.RNodeKeyValueStoreManager
import coop.rchain.casper.util.comm.CasperPacketHandler
import coop.rchain.catscontrib.TaskContrib.TaskOps
import coop.rchain.comm.PeerNode
import coop.rchain.comm.protocol.routing.Packet
import coop.rchain.comm.rp.{ClearConnectionsConf, HandleMessages, RPConf}
import coop.rchain.comm.transport.GrpcTransportServer
import coop.rchain.crypto.codec.Base16
import coop.rchain.metrics.Metrics
import coop.rchain.models.BlockHash.BlockHash
import coop.rchain.models.BlockMetadata
import coop.rchain.models.Validator.Validator
import coop.rchain.p2p.effects.PacketHandler
import coop.rchain.rspace.Context
import coop.rchain.shared
import coop.rchain.shared.Log
import coop.rchain.shared.Log.NOPLog
import coop.rchain.shared.syntax._
import coop.rchain.store.{KeyValueStoreManager, KeyValueTypedStore}
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import fs2._
import org.scalatest._
import org.scalatest.prop.GeneratorDrivenPropertyChecks

import scala.concurrent.duration.DurationInt

class BSpec
    extends FlatSpecLike
    with Matchers
    with OptionValues
    with EitherValues
    with GeneratorDrivenPropertyChecks
    with BeforeAndAfterAll {

  it should "do" ignore {
    implicit val log = new NOPLog[Task]
//    implicit val metrics = new Metrics.MetricsNOP[Task]
    val p = for {
      casperStoreManager <- RNodeKeyValueStoreManager[Task](Path.of("/rchain/rnode"))
      blockMetadataDb <- casperStoreManager.database[BlockHash, BlockMetadata](
                          "block-metadata",
                          codecBlockHash,
                          codecBlockMetadata
                        )
      b <- blockMetadataDb.get(
            ByteString.copyFrom(
              Base16
                .unsafeDecode("ece10a44117cb804b5746e18d7f0204797096c58049adaa9e3bcd19aff029ecf")
            )
          )
      _ = println(b)
    } yield ()
    p.runSyncUnsafe()
  }

  it should "migrate" in {
    implicit val log     = new NOPLog[Task]
    implicit val metrics = new Metrics.MetricsNOP[Task]
    val blockstorePath   = Path.of("/root/rnode/blockstore")
    val p = for {
      casperStoreManager <- RNodeKeyValueStoreManager[Task](Path.of("/rchain/rnode"))
      blockMetadataDb <- casperStoreManager.database[BlockHash, BlockMetadata](
                          "block-metadata",
                          codecBlockHash,
                          codecBlockMetadata
                        )
      invalidBlocksDb <- casperStoreManager.database[BlockHash, BlockMetadata](
                          "invalid-blocks",
                          codecBlockHash,
                          codecBlockMetadata
                        )
      blockKVStore <- {
        implicit val kvm = casperStoreManager
        KeyValueBlockStore[Task]()
      }

      blockFileStorage <- {
        val blockstoreEnv = Context.env(blockstorePath, 1024L * 1024L * 1024L)
        for {
          blockStore <- FileLMDBIndexBlockStore
                         .create[Task](blockstoreEnv, blockstorePath)
                         .map(_.right.get) // TODO handle errors
        } yield blockStore
      }
      originalBlockStream <- blockFileStorage.iterateStream()
      result = originalBlockStream.broadcastTo(8)(
        s =>
          s.evalMap(
            b =>
              for {
                blockInKV <- blockKVStore.get(b.blockHash)
                _ <- blockInKV match {
                      case Some(_) => ().pure[Task]
                      case None =>
                        for {
                          _ <- blockKVStore.put(b)
                        } yield ()
                    }
              } yield ()
          )
      )
//      s = originalBlockStream.evalMap(
//        b =>
//          for {
//            blockInKV <- blockKVStore.get(b.blockHash)
//            _ <- blockInKV match {
//              case Some(_) => ().pure[Task]
//              case None =>
//                for {
//                  _ <- blockKVStore.put(b)
//                } yield ()
//            }
//            blockInMeta <- blockMetadataDb.get(b.blockHash)
//            _ <- blockInMeta match {
//                  case Some(_) => ().pure[Task]
//                  case None =>
//                    for {
//                      isInValid <- invalidBlocksDb.contains(b.blockHash)
//                      _         <- blockMetadataDb.put(b.blockHash, BlockMetadata.fromBlock(b, isInValid))
//                    } yield ()
//                }
//          } yield ()
//      )
    } yield result
    val s = p.runSyncUnsafe()
    s.compile.drain.runSyncUnsafe()
  }

}

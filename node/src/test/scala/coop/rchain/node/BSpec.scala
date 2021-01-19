package coop.rchain.node

import java.nio.file.Path

import cats.effect.Sync
import com.google.protobuf.ByteString
import coop.rchain.blockstorage.dag.BlockDagKeyValueStorage
import coop.rchain.blockstorage.dag.codecs.{codecBlockHash, codecBlockMetadata}
import coop.rchain.blockstorage.util.io.IOError.RaiseIOError
import coop.rchain.blockstorage.util.io._
import coop.rchain.blockstorage.{FileLMDBIndexBlockStore, KeyValueBlockStore}
import coop.rchain.casper.storage.RNodeKeyValueStoreManager
import coop.rchain.catscontrib.TaskContrib.TaskOps
import coop.rchain.crypto.codec.Base16
import coop.rchain.metrics.Metrics
import coop.rchain.models.BlockHash.BlockHash
import coop.rchain.models.BlockMetadata
import coop.rchain.models.Validator.Validator
import coop.rchain.rspace.Context
import coop.rchain.shared
import coop.rchain.shared.Log
import coop.rchain.shared.Log.NOPLog
import coop.rchain.shared.syntax._
import coop.rchain.store.KeyValueStoreManager
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalatest._
import org.scalatest.prop.GeneratorDrivenPropertyChecks

class BSpec
    extends FlatSpecLike
    with Matchers
    with OptionValues
    with EitherValues
    with GeneratorDrivenPropertyChecks
    with BeforeAndAfterAll {
//
//  import java.nio.file.Path
//  implicit val scheduler = Scheduler.fixedPool("block-dag-storage-test-scheduler", 4)
//
//  implicit val raiseIOError: RaiseIOError[Task] = IOError.raiseIOErrorThroughSync[Task]
//
//  def withDagStorageLocation[R](f: Path => Task[R]): R = {
//    val testProgram = Sync[Task].bracket {
//      Sync[Task].delay {
//        Path.of("/rchain2/rnode/dagstorage")
//      }
//    } { dagDataDir =>
//      f(dagDataDir)
//    } { _ =>
//      Task.delay(())
//    }
//    testProgram.unsafeRunSync(scheduler)
//  }
//
//  def withDagStorage[R](f: BlockDagStorage[Task] => Task[R]): R =
//    withDagStorageLocation { dagDataDir =>
//      for {
//        dagStorage <- createAtDefaultLocation(dagDataDir)
//        result     <- f(dagStorage)
//        _          <- dagStorage.close()
//      } yield result
//    }
//
//  private def createAtDefaultLocation(
//                                       dagDataDir: Path,
//                                       maxSizeFactor: Int = 10
//                                     ): Task[BlockDagFileStorage[Task]] = {
//    implicit val log     = new shared.Log.NOPLog[Task]()
//    implicit val metrics = new Metrics.MetricsNOP[Task]
//    BlockDagFileStorage.create[Task](
//      BlockDagFileStorage.Config(
//        latestMessagesLogPath = dagDataDir.resolve("latestMessagesLogPath"),
//        latestMessagesCrcPath = dagDataDir.resolve("latestMessagesCrcPath"),
//        blockMetadataLogPath = dagDataDir.resolve("blockMetadataLogPath"),
//        blockMetadataCrcPath = dagDataDir.resolve("blockMetadataCrcPath"),
//        equivocationsTrackerLogPath = dagDataDir.resolve("equivocationsTrackerLogPath"),
//        equivocationsTrackerCrcPath = dagDataDir.resolve("equivocationsTrackerCrcPath"),
//        invalidBlocksLogPath = dagDataDir.resolve("invalidBlocksLogPath"),
//        invalidBlocksCrcPath = dagDataDir.resolve("invalidBlocksCrcPath"),
//        blockHashesByDeployLogPath = dagDataDir.resolve("blockHashesByDeployLogPath"),
//        blockHashesByDeployCrcPath = dagDataDir.resolve("blockHashesByDeployCrcPath"),
//        checkpointsDirPath = dagDataDir.resolve("checkpointsDirPath"),
//        blockNumberIndexPath = dagDataDir.resolve("blockNumberIndexPath"),
//        mapSize = 1024L * 1024L * 1024L,
//        latestMessagesLogMaxSizeFactor = 10
//      )
//    )
//  }
//
//  type LookupResult =
//    (
//      List[
//        (
//          Option[BlockMetadata],
//            Option[BlockHash],
//            Option[BlockMetadata],
//            Option[Set[BlockHash]],
//            Boolean
//          )
//      ],
//        Map[Validator, BlockHash],
//        Map[Validator, BlockMetadata],
//        Vector[Vector[BlockHash]],
//        Long
//      )
//
//  it ignore "handle " in {
//    withDagStorage { dagStorage =>
//      implicit val log: Log[Task]        = new Log.NOPLog()
//      implicit val metric: Metrics[Task] = new Metrics.MetricsNOP()
//      val blockStorageDir                = Path.of("/rchain2/rnode/blockstore")
//      val env                            = Context.env(blockStorageDir, 1024L * 1024L * 1024L)
//      for {
//        dag <- dagStorage.getRepresentation
//
//        blockStore <- FileLMDBIndexBlockStore.create[Task](env, blockStorageDir).map(_.right.get)
//
//        b <- blockStore.get(
//          ByteString.copyFrom(
//            Base16
//              .unsafeDecode("1ce752046b4ef30049bc5d0417efbadd936025cf384667bab636eb63d14fdcb3")
//          )
//        )
//      } yield ()
//    }
//  }

  it should "do" in {
    implicit val log     = new NOPLog[Task]
    implicit val metrics = new Metrics.MetricsNOP[Task]
    val p = for {
      casperStoreManager <- RNodeKeyValueStoreManager[Task](Path.of("/rchain/rnode"))
      blockMetadataDb <- casperStoreManager.database[BlockHash, BlockMetadata](
        "block-metadata",
        codecBlockHash,
        codecBlockMetadata
      )
      b <- blockMetadataDb.get(ByteString.copyFrom(Base16.unsafeDecode("ece10a44117cb804b5746e18d7f0204797096c58049adaa9e3bcd19aff029ecf")))
      _ = println(b)
    } yield ()
    p.runSyncUnsafe()
  }

}

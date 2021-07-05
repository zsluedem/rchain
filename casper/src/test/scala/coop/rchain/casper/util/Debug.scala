package coop.rchain.casper.util

import cats.syntax.all._
import com.google.protobuf.ByteString
import coop.rchain.casper.ReportingRuntime
import coop.rchain.casper.storage.RNodeKeyValueStoreManager
import coop.rchain.casper.util.rholang.SystemDeployUtil
import coop.rchain.casper.util.rholang.costacc.CloseBlockDeploy
import coop.rchain.crypto.codec.Base16
import coop.rchain.metrics.{Metrics, Span}
import coop.rchain.rholang.interpreter.RhoRuntime
import coop.rchain.rspace.hashing.Blake2b256Hash
import coop.rchain.rspace.syntax._
import coop.rchain.casper.syntax._
import coop.rchain.casper.util.rholang.SystemDeployPlatformFailure.UnexpectedSystemErrors
import coop.rchain.crypto.PublicKey
import coop.rchain.rholang.interpreter.SystemProcesses.BlockData
import coop.rchain.shared.Log
import monix.eval.Task
import org.scalatest.{FlatSpec, Matchers}

import java.nio.file.Path
import monix.execution.Scheduler.Implicits.global

class Debug extends FlatSpec with Matchers {

  "s" should "s" in {
    implicit val log    = Log.log[Task]
    implicit val metric = new Metrics.MetricsNOP[Task]()
    implicit val span   = Span.noop[Task]
    val dataDir         = Path.of("/rchain/OLD-TESTNET/node4-210604-platform-fail/rnode")
    val selfId = ByteString.copyFrom(
      Base16.unsafeDecode(
        "0466fe01872364f0f26be110ba5201383ec450c458edeb862b1b2520b6ef042e91c60094a0c4a98b2d80bbd64d252e58f3b749a4e8d75976b51e441ada4ce667f6"
      )
    )
    val nextSeqNum = 267260
    val task = for {
      rnodeStoreManager <- RNodeKeyValueStoreManager[Task](dataDir, legacyRSpacePaths = false)
      store             <- rnodeStoreManager.rSpaceStores
      runtimes          <- RhoRuntime.createRuntimes[Task](store)
      reportingRspace   <- ReportingRuntime.setupReportingRSpace(store)
      reportingRuntime  <- ReportingRuntime.createReportingRuntime(reportingRspace)
      closeBlock = CloseBlockDeploy(
        SystemDeployUtil.generateCloseDeployRandomSeed(selfId, nextSeqNum)
      )
      _               = println(closeBlock)
      (runtime, _, _) = runtimes
      state = ByteString.copyFrom(
        Base16.unsafeDecode("917c3b64f49f557a7828de0b486e6fc05c76020272c324eb8e67eb074d5f9eac")
      )
      _ <- runtime.setBlockData(
            BlockData(1625455092656L, 1110000L, PublicKey(selfId), 267260)
          )
      _    <- runtime.reset(Blake2b256Hash.fromByteString(state))
      _    <- runtime.evaluateSystemSource(closeBlock)
//      soft <- runtime.createSoftCheckpoint
//
//      _ <- reportingRuntime.rig(soft.log)
//      _ <- reportingRuntime.setBlockData(
//            BlockData(1625455092656L, 1110000L, PublicKey(selfId), 267260)
//          )
//      _              <- reportingRuntime.reset(Blake2b256Hash.fromByteString(state))
//      evaluateResult <- reportingRuntime.evaluateSystemSource(closeBlock)
//      report         <- reportingRuntime.getReport
//      _              = println(report.drop(10))
//      maybeConsumedTuple <- if (evaluateResult.failed)
//                             Task.raiseError(UnexpectedSystemErrors(evaluateResult.errors))
//                           else
//                             reportingRuntime.consumeSystemResult(closeBlock)
//      _ = println(s"${maybeConsumedTuple}")

    } yield ()

    task.runSyncUnsafe()

  }

}

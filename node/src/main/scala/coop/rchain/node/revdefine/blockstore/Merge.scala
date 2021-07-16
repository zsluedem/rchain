package coop.rchain.node.revdefine.blockstore

import cats.effect.Blocker.liftExecutionContext
import cats.effect._
import com.google.protobuf.ByteString
import coop.rchain.blockstorage.KeyValueBlockStore
import coop.rchain.crypto.codec.Base16
import coop.rchain.metrics.{Metrics, NoopSpan}
import coop.rchain.models.{BindPattern, GPrivate, ListParWithRandom, Par}
import coop.rchain.node.revvaultexport.StateBalances
import coop.rchain.rspace.Match
import coop.rchain.shared.Log
import coop.rchain.store.LmdbStoreManager
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.rogach.scallop.ScallopConf

import java.io.PrintWriter
import java.nio.file.{Files, Path}

final case class MergeOptions(arguments: Seq[String]) extends ScallopConf(arguments) {
  val width = 120
  helpWidth(width)
  printedName = "state-balance-main"

  val blockDir = opt[Path](
    descr = s"RNode data dir.",
    required = true
  )
  val block2Dir = opt[Path](
    descr = s"Target block for generate the balances.",
    required = true
  )
  verify()
}
object MergeMain {

  @SuppressWarnings(Array("org.wartremover.warts.NonUnitStatements"))
  def main(args: Array[String]): Unit = {
    val options   = MergeOptions(args)
    val blockDir  = options.blockDir()
    val block2Dir = options.block2Dir()

    implicit val log: Log[Task] = Log.log
//    implicit val span: NoopSpan[Task] = NoopSpan[Task]()
//    implicit val metrics: Metrics.MetricsNOP[Task] = new Metrics.MetricsNOP[Task]()
//    import coop.rchain.rholang.interpreter.storage._
//    implicit val m: Match[Task, BindPattern, ListParWithRandom] = matchListPar[Task]
    val b = liftExecutionContext(global)

    val task: Task[Unit] = for {
      old         <- LmdbStoreManager[Task](blockDir, 1024L * 1024L * 1024L * 1024L)
      blockStore  <- DefineBlockStore[Task](old, b)
      target      <- LmdbStoreManager[Task](block2Dir, 1024L * 1024L * 1024L * 1024L)
      targetStore <- DefineBlockStore[Task](target, blocker = b)
      a <- blockStore.iterate(
            s =>
              s.evalMap(
                block =>
                  for {
                    targetB <- targetStore.get(block.blockHash)
                    _       = println(targetB)
                  } yield ()
              )
          )
      _ <- a.compile.toList
//      s <- blockStore.iterateStream
//      r = s.evalMap {
//        case block =>
//          for {
//            targetB <- targetStore.get(block.blockHash)
//            _       = println(targetB)
//          } yield ()
//      }
//      s <- r.compile.toList
    } yield ()
    task.runSyncUnsafe()
  }
}

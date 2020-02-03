package coop.rchain.casper

import cats.effect.{Concurrent, Sync}
import cats.syntax.all._
import cats.{Applicative, Show}
import com.google.protobuf.ByteString
import coop.rchain.blockstorage.BlockStore
import coop.rchain.blockstorage.dag.BlockDagStorage.DeployId
import coop.rchain.blockstorage.dag.{BlockDagRepresentation, BlockDagStorage}
import coop.rchain.blockstorage.deploy.DeployStorage
import coop.rchain.blockstorage.finality.LastFinalizedStorage
import coop.rchain.casper.engine.Running
import coop.rchain.casper.protocol._
import coop.rchain.casper.util.ProtoUtil
import coop.rchain.casper.util.comm.CommUtil
import coop.rchain.casper.util.rholang.RuntimeManager.StateHash
import coop.rchain.casper.util.rholang._
import coop.rchain.catscontrib.ski.kp2
import coop.rchain.crypto.signatures.Signed
import coop.rchain.metrics.{Metrics, MetricsSemaphore, Span}
import coop.rchain.models.BlockHash.BlockHash
import coop.rchain.models.Validator.Validator
import coop.rchain.shared._

sealed trait DeployError
final case class ParsingError(details: String)          extends DeployError
final case object MissingUser                           extends DeployError
final case class UnknownSignatureAlgorithm(alg: String) extends DeployError
final case object SignatureVerificationFailed           extends DeployError

object DeployError {
  def parsingError(details: String): DeployError          = ParsingError(details)
  def missingUser: DeployError                            = MissingUser
  def unknownSignatureAlgorithm(alg: String): DeployError = UnknownSignatureAlgorithm(alg)
  def signatureVerificationFailed: DeployError            = SignatureVerificationFailed

  implicit val showDeployError: Show[DeployError] = new Show[DeployError] {
    def show(error: DeployError): String = error match {
      case ParsingError(details)          => s"Parsing error: $details"
      case MissingUser                    => s"Missing user"
      case UnknownSignatureAlgorithm(alg) => s"Unknown signature algorithm '$alg'"
      case SignatureVerificationFailed    => "Signature verification failed"
    }
  }
}

trait Casper[F[_]] {
  def addBlock(b: BlockMessage): F[ValidBlockProcessing]
  def contains(hash: BlockHash): F[Boolean]
  def deploy(d: Signed[DeployData]): F[Either[DeployError, DeployId]]
  def estimator(dag: BlockDagRepresentation[F]): F[IndexedSeq[BlockHash]]
  def createBlock: F[CreateBlockStatus]
  def getGenesis: F[BlockMessage]
  def getValidator: F[ByteString]
}

trait MultiParentCasper[F[_]] extends Casper[F] {
  def blockDag: F[BlockDagRepresentation[F]]
  def fetchDependencies: F[Unit]
  // This is the weight of faults that have been accumulated so far.
  // We want the clique oracle to give us a fault tolerance that is greater than
  // this initial fault weight combined with our fault tolerance threshold t.
  def normalizedInitialFault(weights: Map[Validator, Long]): F[Float]
  def lastFinalizedBlock: F[BlockMessage]
  def getRuntimeManager: F[RuntimeManager[F]]
}

object MultiParentCasper extends MultiParentCasperInstances {
  def apply[F[_]](implicit instance: MultiParentCasper[F]): MultiParentCasper[F] = instance
  def ignoreDoppelgangerCheck[F[_]: Applicative]: (BlockMessage, Validator) => F[Unit] =
    kp2(().pure)

  def forkChoiceTip[F[_]: Sync: BlockStore](casper: MultiParentCasper[F]): F[BlockMessage] =
    for {
      dag       <- casper.blockDag
      tipHashes <- casper.estimator(dag)
      tipHash   = tipHashes.head
      tip       <- ProtoUtil.getBlock(tipHash)
    } yield tip
}

sealed abstract class MultiParentCasperInstances {
  implicit val MetricsSource: Metrics.Source =
    Metrics.Source(CasperMetricsSource, "casper")
  private[this] val genesisLabel = Metrics.Source(MetricsSource, "genesis")

  def hashSetCasper[F[_]: Sync: Metrics: Concurrent: CommUtil: Log: Time: SafetyOracle: LastFinalizedBlockCalculator: BlockStore: BlockDagStorage: LastFinalizedStorage: Span: Running.RequestedBlocks: EventPublisher: SynchronyConstraintChecker: LastFinalizedHeightConstraintChecker: Estimator: DeployStorage](
      validatorId: Option[ValidatorIdentity],
      genesis: BlockMessage,
      shardId: String,
      finalizationRate: Int
  )(implicit runtimeManager: RuntimeManager[F]): F[MultiParentCasper[F]] =
    Span[F].trace(genesisLabel) {
      for {
        dag <- BlockDagStorage[F].getRepresentation
        maybePostGenesisStateHash <- InterpreterUtil
                                      .validateBlockCheckpoint(genesis, dag, runtimeManager)
        postGenesisStateHash <- maybePostGenesisStateHash match {
                                 case Left(BlockError.BlockException(ex)) =>
                                   ex.raiseError[F, StateHash]
                                 case Left(error) =>
                                   new Exception(s"Block error: $error").raiseError[F, StateHash]
                                 case Right(None) =>
                                   new Exception("Genesis tuplespace validation failed!")
                                     .raiseError[F, StateHash]
                                 case Right(Some(hash)) => hash.pure
                               }
        blockProcessingLock <- MetricsSemaphore.single[F]
        casperState         <- Cell.mvarCell[F, CasperState](CasperState())
      } yield {
        implicit val state = casperState
        new MultiParentCasperImpl(
          validatorId,
          genesis,
          postGenesisStateHash,
          shardId,
          finalizationRate,
          blockProcessingLock
        )
      }
    }
}

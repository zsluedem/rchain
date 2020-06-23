package coop.rchain.casper.api

import cats.Monad
import cats.effect.concurrent.Semaphore
import cats.effect.{Concurrent, Sync}
import cats.implicits._
import com.google.protobuf.ByteString
import coop.rchain.blockstorage.BlockStore
import coop.rchain.blockstorage.dag.BlockDagStorage.DeployId
import coop.rchain.casper.DeployError._
import coop.rchain.casper.{ReportingCasper, ReportingProtoTransformer, _}
import coop.rchain.casper.engine.EngineCell._
import coop.rchain.casper.engine._
import coop.rchain.casper.protocol._
import coop.rchain.casper.syntax._
import coop.rchain.casper.util._
import coop.rchain.casper.util.rholang.{RuntimeManager, Tools}
import coop.rchain.crypto.PublicKey
import coop.rchain.crypto.codec.Base16
import coop.rchain.crypto.signatures.Signed
import coop.rchain.graphz._
import coop.rchain.metrics.implicits._
import coop.rchain.metrics.{Metrics, Span}
import coop.rchain.models.BlockHash.{BlockHash, _}
import coop.rchain.models.rholang.sorter.Sortable._
import coop.rchain.models.serialization.implicits.mkProtobufInstance
import coop.rchain.models.{BlockMetadata, Par}
import coop.rchain.rholang.interpreter.storage.StoragePrinter
import coop.rchain.rspace.ReportingRspace.ReportingEvent
import coop.rchain.rspace.StableHashProvider
import coop.rchain.rspace.trace._
import coop.rchain.shared.Log
import scala.collection.immutable

sealed trait APIError extends Throwable

trait BlockAPI[F[_]] {
  type Error     = String
  type ApiErr[A] = Either[Error, A]

  def deploy(d: Signed[DeployData]): F[ApiErr[String]]
  def createBlock(printUnmatchedSends: Boolean): F[ApiErr[String]]
  def getListeningNameDataResponse(
      depth: Int,
      listeningName: Par
  ): F[ApiErr[(Seq[DataWithBlockInfo], Int)]]
  def getListeningNameContinuationResponse(
      depth: Int,
      listeningNames: Seq[Par]
  ): F[ApiErr[(Seq[ContinuationsWithBlockInfo], Int)]]
  def getBlocksByHeights(
      startBlockNumber: Long,
      endBlockNumber: Long
  ): F[ApiErr[List[LightBlockInfo]]]
  def visualizeDag[G[_], R](
      depth: Int,
      startBlockNumber: Int,
      visualizer: (Vector[Vector[BlockHash]], String) => F[G[Graphz[G]]],
      serialize: G[Graphz[G]] => R
  ): F[ApiErr[R]]
  def machineVerifiableDag(depth: Int): F[ApiErr[String]]
  def getBlocks(depth: Int): F[ApiErr[List[LightBlockInfo]]]
  def showMainChain(depth: Int): F[List[LightBlockInfo]]
  def findDeploy(id: DeployId): F[ApiErr[LightBlockInfo]]
  def blockReport(hash: String): F[ApiErr[BlockEventInfo]]
  def getBlock(
      hash: String
  ): F[ApiErr[BlockInfo]]
  def previewPrivateNames(
      deployer: ByteString,
      timestamp: Long,
      nameQty: Int
  ): F[ApiErr[Seq[ByteString]]]
  def lastFinalizedBlock: F[ApiErr[BlockInfo]]
  def isFinalized(hash: String): F[ApiErr[Boolean]]
  def bondStatus(publicKey: ByteString): F[ApiErr[Boolean]]
  def exploratoryDeploy(
      term: String,
      blockHash: Option[String] = none,
      usePreStateHash: Boolean = false
  ): F[ApiErr[(Seq[Par], LightBlockInfo)]]
  def getLatestMessage: F[ApiErr[BlockMetadata]]
}

object BlockAPI {

  sealed trait LatestBlockMessageError     extends Throwable
  final case object ValidatorReadOnlyError extends LatestBlockMessageError
  final case object NoBlockMessageError    extends LatestBlockMessageError

  // TODO: we should refactor BlockApi with applicative errors for better classification
  //  of errors and to overcome nesting when validating data.
  final case class BlockRetrievalError(message: String) extends Exception

  class BlockAPIImpl[F[_]: Sync: Concurrent: EngineCell: Log: Metrics: Span: SafetyOracle: BlockStore: SynchronyConstraintChecker: LastFinalizedHeightConstraintChecker](
      blockApiLock: Semaphore[F],
      apiMaxBlocksLimit: Int,
      reportingCasper: ReportingCasper[F]
  ) extends BlockAPI[F] {
    val reportTransformer                     = new ReportingProtoTransformer()
    val BlockAPIMetricsSource: Metrics.Source = Metrics.Source(Metrics.BaseSource, "block-api")
    val DeploySource: Metrics.Source          = Metrics.Source(BlockAPIMetricsSource, "deploy")
    val GetBlockSource: Metrics.Source        = Metrics.Source(BlockAPIMetricsSource, "get-block")

    def deploy(
        d: Signed[DeployData]
    ): F[ApiErr[String]] = Span[F].trace(DeploySource) {

      def casperDeploy(casper: MultiParentCasper[F]): F[ApiErr[String]] =
        casper
          .deploy(d)
          .map(
            _.bimap(
              err => err.show,
              res => s"Success!\nDeployId is: ${PrettyPrinter.buildStringNoLimit(res)}"
            )
          )

      // Check if deploy has minimum phlo price
      val minPhloPrice = 1
      val minPriceError = new RuntimeException(
        s"Phlo price is less than minimum price $minPhloPrice."
      ).raiseError[F, ApiErr[String]]
      val minPhloPriceCheck = minPriceError.whenA(d.data.phloPrice < minPhloPrice)

      val errorMessage = "Could not deploy, casper instance was not available yet."
      val logErrorMessage = Log[F]
        .warn(errorMessage)
        .as(s"Error: $errorMessage".asLeft[String])

      minPhloPriceCheck >> EngineCell[F].read >>= (_.withCasper[ApiErr[String]](
        casperDeploy,
        logErrorMessage
      ))
    }

    def createBlock(
        printUnmatchedSends: Boolean = false
    ): F[ApiErr[String]] = {
      def logWarning(err: String) = Log[F].warn(s"Error: $err") >> err.asLeft[String].pure[F]
      EngineCell[F].read >>= (
        _.withCasper[ApiErr[String]](
          casper => {
            Sync[F].bracket(blockApiLock.tryAcquire) {
              case true => {
                implicit val ms     = BlockAPIMetricsSource
                val syncCheckFailed = logWarning("Must wait for more blocks from other validators")
                val lfhcCheckFailed = logWarning("Too far ahead of the last finalized block")
                val validatorCheckFailed = new IllegalStateException(
                  "Read only node cannot create a block"
                ).raiseError[F, PublicKey]
                for {
                  maybeValidator  <- casper.getValidator
                  validatorPubKey <- maybeValidator.fold(validatorCheckFailed)(_.pure[F])
                  validator       = ByteString.copyFrom(validatorPubKey.bytes)
                  genesis         <- casper.getGenesis
                  dag             <- casper.blockDag
                  runtimeManager  <- casper.getRuntimeManager
                  checkSynchronyConstraint = SynchronyConstraintChecker[F]
                    .check(
                      dag,
                      runtimeManager,
                      genesis,
                      validator
                    )
                  checkLastFinalizedHeightConstraint = LastFinalizedHeightConstraintChecker[F]
                    .check(dag, genesis, validator)
                  createBlock = (for {
                    _          <- Metrics[F].incrementCounter("propose")
                    maybeBlock <- casper.createBlock
                    result <- maybeBlock match {
                               case err: NoBlock =>
                                 s"Error while creating block: $err".asLeft[String].pure[F]
                               case Created(block) =>
                                 Log[F]
                                   .info(s"Proposing ${PrettyPrinter.buildString(block)}") *>
                                   casper.addBlock(block) >>= (addResponse(
                                   _,
                                   block,
                                   casper,
                                   printUnmatchedSends
                                 ))
                             }
                  } yield result)
                    .timer("propose-total-time")
                    .attempt
                    .map(
                      _.leftMap(e => s"Error while creating block: ${e.getMessage}").joinRight
                    )
                    .flatMap {
                      case Left(error) =>
                        logWarning(error) <* Metrics[F].incrementCounter("propose-failed")
                      case result => result.pure[F]
                    }
                  result <- checkSynchronyConstraint.ifM(
                             checkLastFinalizedHeightConstraint.ifM(createBlock, lfhcCheckFailed),
                             syncCheckFailed
                           )
                } yield result
              }
              case false =>
                "Error: There is another propose in progress.".asLeft[String].pure[F]
            } {
              case true =>
                blockApiLock.release
              case false =>
                ().pure[F]
            }
          },
          default = logWarning("Could not create block, casper instance was not available yet.")
        )
      )
    }

    def getListeningNameDataResponse(
        depth: Int,
        listeningName: Par
    ): F[ApiErr[(Seq[DataWithBlockInfo], Int)]] = {

      val errorMessage = "Could not get listening name data, casper instance was not available yet."

      def casperResponse(
          implicit casper: MultiParentCasper[F]
      ): F[ApiErr[(Seq[DataWithBlockInfo], Int)]] =
        for {
          mainChain           <- getMainChainFromTip(depth)
          runtimeManager      <- casper.getRuntimeManager
          sortedListeningName <- parSortable.sortMatch[F](listeningName).map(_.term)
          maybeBlocksWithActiveName <- mainChain.toList.traverse { block =>
                                        getDataWithBlockInfo(
                                          runtimeManager,
                                          sortedListeningName,
                                          block
                                        )
                                      }
          blocksWithActiveName = maybeBlocksWithActiveName.flatten
        } yield (blocksWithActiveName, blocksWithActiveName.length).asRight

      if (depth > apiMaxBlocksLimit)
        s"Your request on getListeningName depth ${depth} exceed the max limit ${apiMaxBlocksLimit}"
          .asLeft[(Seq[DataWithBlockInfo], Int)]
          .pure[F]
      else
        EngineCell[F].read >>= (_.withCasper[ApiErr[(Seq[DataWithBlockInfo], Int)]](
          casperResponse(_),
          Log[F]
            .warn(errorMessage)
            .as(s"Error: $errorMessage".asLeft)
        ))
    }

    def getListeningNameContinuationResponse(
        depth: Int,
        listeningNames: Seq[Par]
    ): F[ApiErr[(Seq[ContinuationsWithBlockInfo], Int)]] = {
      val errorMessage =
        "Could not get listening names continuation, casper instance was not available yet."
      def casperResponse(
          implicit casper: MultiParentCasper[F]
      ): F[ApiErr[(Seq[ContinuationsWithBlockInfo], Int)]] =
        for {
          mainChain      <- getMainChainFromTip(depth)
          runtimeManager <- casper.getRuntimeManager
          sortedListeningNames <- listeningNames.toList
                                   .traverse(parSortable.sortMatch(_).map(_.term))
          maybeBlocksWithActiveName <- mainChain.toList.traverse { block =>
                                        getContinuationsWithBlockInfo(
                                          runtimeManager,
                                          sortedListeningNames,
                                          block
                                        )
                                      }
          blocksWithActiveName = maybeBlocksWithActiveName.flatten
        } yield (blocksWithActiveName, blocksWithActiveName.length).asRight

      if (depth > apiMaxBlocksLimit)
        s"Your request on getListeningNameContinuation depth ${depth} exceed the max limit ${apiMaxBlocksLimit}"
          .asLeft[(Seq[ContinuationsWithBlockInfo], Int)]
          .pure[F]
      else
        EngineCell[F].read >>= (_.withCasper[ApiErr[(Seq[ContinuationsWithBlockInfo], Int)]](
          casperResponse(_),
          Log[F]
            .warn(errorMessage)
            .as(s"Error: $errorMessage".asLeft)
        ))
    }

    private def getMainChainFromTip(depth: Int)(
        implicit casper: MultiParentCasper[F]
    ): F[IndexedSeq[BlockMessage]] =
      for {
        dag       <- casper.blockDag
        tipHashes <- casper.estimator(dag)
        tipHash   = tipHashes.head
        tip       <- BlockStore[F].getUnsafe(tipHash)
        mainChain <- ProtoUtil.getMainChainUntilDepth[F](tip, IndexedSeq.empty[BlockMessage], depth)
      } yield mainChain

    private def getDataWithBlockInfo(
        runtimeManager: RuntimeManager[F],
        sortedListeningName: Par,
        block: BlockMessage
    )(implicit casper: MultiParentCasper[F]): F[Option[DataWithBlockInfo]] =
      if (isListeningNameReduced(block, immutable.Seq(sortedListeningName))) {
        val stateHash = ProtoUtil.postStateHash(block)
        for {
          data      <- runtimeManager.getData(stateHash)(sortedListeningName)
          blockInfo <- getLightBlockInfo(block)
        } yield Option[DataWithBlockInfo](DataWithBlockInfo(data, Some(blockInfo)))
      } else {
        none[DataWithBlockInfo].pure[F]
      }

    private def getContinuationsWithBlockInfo(
        runtimeManager: RuntimeManager[F],
        sortedListeningNames: immutable.Seq[Par],
        block: BlockMessage
    )(implicit casper: MultiParentCasper[F]): F[Option[ContinuationsWithBlockInfo]] =
      if (isListeningNameReduced(block, sortedListeningNames)) {
        val stateHash = ProtoUtil.postStateHash(block)
        for {
          continuations <- runtimeManager.getContinuation(stateHash)(sortedListeningNames)
          continuationInfos = continuations.map(
            continuation => WaitingContinuationInfo(continuation._1, Some(continuation._2))
          )
          blockInfo <- getLightBlockInfo(block)
        } yield Option[ContinuationsWithBlockInfo](
          ContinuationsWithBlockInfo(continuationInfos, Some(blockInfo))
        )
      } else {
        none[ContinuationsWithBlockInfo].pure[F]
      }

    private def isListeningNameReduced(
        block: BlockMessage,
        sortedListeningName: immutable.Seq[Par]
    ): Boolean = {
      val serializedLog = for {
        pd    <- block.body.deploys
        event <- pd.deployLog
      } yield event
      val log =
        serializedLog.map(EventConverter.toRspaceEvent)
      log.exists {
        case Produce(channelHash, _, _) =>
          channelHash == StableHashProvider.hash(sortedListeningName)
        case Consume(channelsHashes, _, _) =>
          channelsHashes.toList.sorted == sortedListeningName
            .map(StableHashProvider.hash(_))
            .toList
            .sorted
        case COMM(consume, produces, _, _) =>
          (consume.channelsHashes.toList.sorted ==
            sortedListeningName.map(StableHashProvider.hash(_)).toList.sorted) ||
            produces.exists(
              produce => produce.channelsHash == StableHashProvider.hash(sortedListeningName)
            )
      }
    }

    private def toposortDag[A](depth: Int, maxDepthLimit: Int)(
        doIt: (MultiParentCasper[F], Vector[Vector[BlockHash]]) => F[ApiErr[A]]
    ): F[ApiErr[A]] = {

      val errorMessage =
        "Could not visualize graph, casper instance was not available yet."

      def casperResponse(implicit casper: MultiParentCasper[F]): F[ApiErr[A]] =
        for {
          dag               <- MultiParentCasper[F].blockDag
          latestBlockNumber <- dag.latestBlockNumber
          topoSort          <- dag.topoSort((latestBlockNumber - depth).toLong, none)
          result            <- doIt(casper, topoSort)
        } yield result

      if (depth > maxDepthLimit)
        s"Your request depth ${depth} exceed the max limit ${maxDepthLimit}".asLeft[A].pure[F]
      else
        EngineCell[F].read >>= (_.withCasper[ApiErr[A]](
          casperResponse(_),
          Log[F].warn(errorMessage).as(errorMessage.asLeft)
        ))
    }

    def getBlocksByHeights(
        startBlockNumber: Long,
        endBlockNumber: Long
    ): F[ApiErr[List[LightBlockInfo]]] = {
      val errorMessage = s"Could not retrieve blocks from ${startBlockNumber} to ${endBlockNumber}"

      def casperResponse(implicit casper: MultiParentCasper[F]): F[ApiErr[List[LightBlockInfo]]] =
        for {
          dag         <- MultiParentCasper[F].blockDag
          topoSortDag <- dag.topoSort(startBlockNumber, Some(endBlockNumber))
          result <- topoSortDag
                     .foldM(List.empty[LightBlockInfo]) {
                       case (blockInfosAtHeightAcc, blockHashesAtHeight) =>
                         for {
                           blocksAtHeight <- blockHashesAtHeight.traverse(BlockStore[F].getUnsafe)
                           blockInfosAtHeight <- blocksAtHeight.traverse(
                                                  getLightBlockInfo
                                                )
                         } yield blockInfosAtHeightAcc ++ blockInfosAtHeight
                     }
                     .map(_.asRight[Error])
        } yield result

      if (endBlockNumber - startBlockNumber > apiMaxBlocksLimit)
        s"Your request startBlockNumber ${startBlockNumber} and endBlockNumber ${endBlockNumber} exceed the max limit ${apiMaxBlocksLimit}"
          .asLeft[List[LightBlockInfo]]
          .pure[F]
      else
        EngineCell[F].read >>= (_.withCasper[ApiErr[List[LightBlockInfo]]](
          casperResponse(_),
          Log[F]
            .warn(errorMessage)
            .as(s"Error: $errorMessage".asLeft)
        ))
    }

    override def visualizeDag[G[_], R](
        depth: Int,
        startBlockNumber: Int,
        visualizer: (Vector[Vector[BlockHash]], String) => F[G[Graphz[G]]],
        serialize: G[Graphz[G]] => R
    ): F[ApiErr[R]] = {
      val errorMessage = "visual dag failed"
      def casperResponse(implicit casper: MultiParentCasper[F]): F[ApiErr[R]] =
        for {
          dag <- MultiParentCasper[F].blockDag
          // the default startBlockNumber is 0
          // if the startBlockNumber is 0 , it would use the latestBlockNumber for backward compatible
          startBlockNum <- if (startBlockNumber == 0) dag.latestBlockNumber
                          else Sync[F].delay(startBlockNumber.toLong)
          topoSortDag <- dag.topoSort(
                          startBlockNum - depth,
                          Some(startBlockNum)
                        )
          lfb   <- casper.lastFinalizedBlock
          graph <- visualizer(topoSortDag, PrettyPrinter.buildString(lfb.blockHash))
        } yield serialize(graph).asRight[Error]
      EngineCell[F].read >>= (_.withCasper[ApiErr[R]](
        casperResponse(_),
        Log[F]
          .warn(errorMessage)
          .as(s"Error: $errorMessage".asLeft)
      ))
    }

    def machineVerifiableDag(depth: Int): F[ApiErr[String]] =
      toposortDag[String](depth, apiMaxBlocksLimit) {
        case (_, topoSort) =>
          val fetchParents: BlockHash => F[List[BlockHash]] = { blockHash =>
            BlockStore[F].getUnsafe(blockHash) map (_.header.parentsHashList)
          }

          MachineVerifiableDag[F](topoSort, fetchParents)
            .map(_.map(edges => edges.show).mkString("\n"))
            .map(_.asRight[Error])
      }

    def getBlocks(
        depth: Int
    ): F[ApiErr[List[LightBlockInfo]]] =
      toposortDag[List[LightBlockInfo]](depth, apiMaxBlocksLimit) {
        case (casper, topoSort) =>
          implicit val ev: MultiParentCasper[F] = casper
          topoSort
            .foldM(List.empty[LightBlockInfo]) {
              case (blockInfosAtHeightAcc, blockHashesAtHeight) =>
                for {
                  blocksAtHeight <- blockHashesAtHeight.traverse(BlockStore[F].getUnsafe)
                  blockInfosAtHeight <- blocksAtHeight.traverse(
                                         getLightBlockInfo
                                       )
                } yield blockInfosAtHeightAcc ++ blockInfosAtHeight
            }
            .map(_.reverse.asRight[Error])
      }

    def showMainChain(
        depth: Int
    ): F[List[LightBlockInfo]] = {

      val errorMessage =
        "Could not show main chain, casper instance was not available yet."

      def casperResponse(implicit casper: MultiParentCasper[F]) =
        for {
          dag       <- MultiParentCasper[F].blockDag
          tipHashes <- MultiParentCasper[F].estimator(dag)
          tipHash   = tipHashes.head
          tip       <- BlockStore[F].getUnsafe(tipHash)
          mainChain <- ProtoUtil
                        .getMainChainUntilDepth[F](tip, IndexedSeq.empty[BlockMessage], depth)
          blockInfos <- mainChain.toList.traverse(getLightBlockInfo)
        } yield blockInfos

      if (depth > apiMaxBlocksLimit)
        List.empty[LightBlockInfo].pure[F]
      else
        EngineCell[F].read >>= (_.withCasper[List[LightBlockInfo]](
          casperResponse(_),
          Log[F].warn(errorMessage).as(List.empty[LightBlockInfo])
        ))
    }

    def findDeploy(
        id: DeployId
    ): F[ApiErr[LightBlockInfo]] =
      EngineCell[F].read >>= (
        _.withCasper[ApiErr[LightBlockInfo]](
          implicit casper =>
            for {
              dag            <- casper.blockDag
              maybeBlockHash <- dag.lookupByDeployId(id)
              maybeBlock     <- maybeBlockHash.traverse(BlockStore[F].getUnsafe)
              response       <- maybeBlock.traverse(getLightBlockInfo)
            } yield response.fold(
              s"Couldn't find block containing deploy with id: ${PrettyPrinter
                .buildStringNoLimit(id)}".asLeft[LightBlockInfo]
            )(
              _.asRight
            ),
          Log[F]
            .warn("Could not find block with deploy, casper instance was not available yet.")
            .as(s"Error: errorMessage".asLeft)
        )
      )
    def blockReport(
        hash: String
    ): F[ApiErr[BlockEventInfo]] = {
      val errorMessage =
        "Could not get event data."
      def casperResponse(
          implicit casper: MultiParentCasper[F]
      ): F[ApiErr[BlockEventInfo]] =
        for {
          isReadOnly <- casper.getValidator
          result <- isReadOnly match {
                     case None =>
                       for {
                         reportResult <- reportingCasper.trace(
                                          ByteString.copyFrom(Base16.unsafeDecode(hash))
                                        )
                         mayberesult = createBlockReportResponse(reportResult)
                         block       <- BlockStore[F].get(ByteString.copyFrom(Base16.unsafeDecode(hash)))
                         lightBlock  <- block.traverse(getLightBlockInfo(_))

                         res = mayberesult.map(BlockEventInfo(lightBlock, _))
                       } yield res
                     case Some(_) =>
                       "Block report can only be executed on read-only RNode."
                         .asLeft[BlockEventInfo]
                         .pure[F]
                   }
        } yield result
      EngineCell[F].read >>= (_.withCasper[ApiErr[BlockEventInfo]](
        casperResponse(_),
        Log[F].warn(errorMessage).as(s"Error: $errorMessage".asLeft)
      ))
    }
    private def createBlockReportResponse(
        maybeResult: Either[ReportError, List[(ProcessedDeploy, Seq[Seq[ReportingEvent]])]]
    ): ApiErr[List[DeployInfoWithEventData]] =
      maybeResult match {
        case Left(ReportBlockNotFound(hash)) => Left(s"Block ${hash} not found")
        case Left(ReportReplayError(r))      => Left(s"Block replayed error ${r}")
        case Right(result) =>
          result
            .map(
              p =>
                DeployInfoWithEventData(
                  deployInfo = p._1.toDeployInfo.some,
                  report = p._2
                    .map(
                      a =>
                        SingleReport(events = a.map(reportTransformer.transformEvent(_) match {
                          case rc: ReportConsumeProto => ReportProto(ReportProto.Report.Consume(rc))
                          case rp: ReportProduceProto => ReportProto(ReportProto.Report.Produce(rp))
                          case rcm: ReportCommProto   => ReportProto(ReportProto.Report.Comm(rcm))
                        }))
                    )
                )
            )
            .asRight[Error]
      }

    def getBlock(
        hash: String
    ): F[ApiErr[BlockInfo]] = Span[F].trace(GetBlockSource) {

      val errorMessage =
        "Could not get block, casper instance was not available yet."

      // TODO: we should refactor BlockApi with applicative errors for better classification
      //  of errors and to overcome nesting when validating data.
      final case class BlockRetrievalError(message: String) extends Exception

      def casperResponse(
          implicit casper: MultiParentCasper[F]
      ): F[ApiErr[BlockInfo]] =
        for {
          // Add constraint on the length of searched hash to prevent to many block results
          // which can cause severe CPU load.
          _ <- BlockRetrievalError(s"Input hash value must be at least 6 characters: $hash")
                .raiseError[F, ApiErr[BlockInfo]]
                .whenA(hash.length < 6)
          // Check if hash string is in Base16 encoding and convert to ByteString
          hashByteString <- Base16
                             .decode(hash)
                             .map(ByteString.copyFrom)
                             .liftTo[F](
                               BlockRetrievalError(
                                 s"Input hash value is not valid hex string: $hash"
                               )
                             )
          // Check if hash is complete and not just the prefix in which case
          // we can use `get` directly and not iterate over the whole block hash index.
          getBlock  = BlockStore[F].get(hashByteString)
          findBlock = findBlockFromStore(hash)
          blockF    = if (hash.length == 64) getBlock else findBlock
          // Get block form the block store
          block <- blockF >>= (_.liftTo[F](
                    BlockRetrievalError(s"Error: Failure to find block with hash: $hash")
                  ))
          // Check if the block is added to the dag and convert it to block info
          dag <- MultiParentCasper[F].blockDag
          blockInfo <- dag
                        .contains(block.blockHash)
                        .ifM(
                          getFullBlockInfo(block),
                          BlockRetrievalError(
                            s"Error: Block with hash $hash received but not added yet"
                          ).raiseError[F, BlockInfo]
                        )
        } yield blockInfo.asRight

      EngineCell[F].read >>= (
        _.withCasper[ApiErr[BlockInfo]](
          casperResponse(_)
            .handleError {
              // Convert error message from BlockRetrievalError
              case BlockRetrievalError(errorMessage) => errorMessage.asLeft[BlockInfo]
            },
          Log[F].warn(errorMessage).as(s"Error: $errorMessage".asLeft)
        )
      )
    }

    private def getBlockInfo[A](
        block: BlockMessage,
        constructor: (
            BlockMessage,
            Float
        ) => F[A]
    )(implicit casper: MultiParentCasper[F]): F[A] =
      for {
        dag <- casper.blockDag
        // TODO this is temporary solution to not calculate fault tolerance for old blocks which is costly
        oldBlock  = dag.latestBlockNumber.map(_ - block.body.state.blockNumber).map(_ > 100)
        isInvalid = dag.lookup(block.blockHash).map(_.get.invalid)
        // Old block fault tolerance / invalid block has -1.0 fault tolerance
        oldBlockFaultTolerance = isInvalid.map(if (_) -1f else 1f)
        normalizedFaultTolerance <- oldBlock.ifM(
                                     oldBlockFaultTolerance,
                                     SafetyOracle[F]
                                       .normalizedFaultTolerance(dag, block.blockHash)
                                   )
        initialFault   <- casper.normalizedInitialFault(ProtoUtil.weightMap(block))
        faultTolerance = normalizedFaultTolerance - initialFault
        blockInfo      <- constructor(block, faultTolerance)
      } yield blockInfo

    private def getFullBlockInfo(
        block: BlockMessage
    )(implicit casper: MultiParentCasper[F]): F[BlockInfo] =
      getBlockInfo[BlockInfo](block, constructBlockInfo)
    private def getLightBlockInfo(
        block: BlockMessage
    )(implicit casper: MultiParentCasper[F]): F[LightBlockInfo] =
      getBlockInfo[LightBlockInfo](block, constructLightBlockInfo)

    private def constructBlockInfo(
        block: BlockMessage,
        faultTolerance: Float
    ): F[BlockInfo] =
      for {
        lightBlockInfo <- constructLightBlockInfo(block, faultTolerance)
        deploys        = block.body.deploys.toSeq.map(_.toDeployInfo)
      } yield BlockInfo(
        blockInfo = Some(lightBlockInfo),
        deploys = deploys
      )

    private def constructLightBlockInfo(
        block: BlockMessage,
        faultTolerance: Float
    ): F[LightBlockInfo] =
      LightBlockInfo(
        blockHash = PrettyPrinter.buildStringNoLimit(block.blockHash),
        sender = PrettyPrinter.buildStringNoLimit(block.sender),
        seqNum = block.seqNum.toLong,
        sig = PrettyPrinter.buildStringNoLimit(block.sig),
        sigAlgorithm = block.sigAlgorithm,
        shardId = block.shardId,
        extraBytes = block.extraBytes,
        version = block.header.version,
        timestamp = block.header.timestamp,
        headerExtraBytes = block.header.extraBytes,
        parentsHashList = block.header.parentsHashList.map(PrettyPrinter.buildStringNoLimit),
        blockNumber = block.body.state.blockNumber,
        preStateHash = PrettyPrinter.buildStringNoLimit(block.body.state.preStateHash),
        postStateHash = PrettyPrinter.buildStringNoLimit(block.body.state.postStateHash),
        bodyExtraBytes = block.body.extraBytes,
        bonds = block.body.state.bonds.map(ProtoUtil.bondToBondInfo),
        blockSize = block.toProto.serializedSize.toString,
        deployCount = block.body.deploys.length,
        faultTolerance = faultTolerance,
        justifications = block.justifications.map(ProtoUtil.justificationsToJustificationInfos)
      ).pure[F]

    // Be careful to use this method , because it would iterate the whole indexes to find the matched one which would cause performance problem
    // Trying to use BlockStore.get as much as possible would more be preferred
    private def findBlockFromStore(
        hash: String
    ): F[Option[BlockMessage]] =
      for {
        findResult <- BlockStore[F].find(h => Base16.encode(h.toByteArray).startsWith(hash), 1)
      } yield findResult.headOption match {
        case Some((_, block)) => Some(block)
        case None             => none[BlockMessage]
      }

    private def addResponse(
        status: ValidBlockProcessing,
        block: BlockMessage,
        casper: MultiParentCasper[F],
        printUnmatchedSends: Boolean
    ): F[ApiErr[String]] =
      status
        .map { _ =>
          val hash    = block.blockHash.base16String
          val deploys = block.body.deploys.map(_.deploy)
          val maybeUnmatchedSendsOutputF =
            if (printUnmatchedSends) prettyPrintUnmatchedSends(casper, deploys).map(_.some)
            else none[String].pure[F]
          maybeUnmatchedSendsOutputF >>= (
              maybeOutput =>
                s"Success! Block $hash created and added.${maybeOutput.map("\n" + _).getOrElse("")}"
                  .asRight[Error]
                  .pure[F]
            )
        }
        .leftMap {
          case _: InvalidBlock =>
            s"Failure! Invalid block: $status".asLeft[String].pure[F]
          case BlockError.BlockException(ex) =>
            s"Error during block processing: $ex".asLeft[String].pure[F]
          case BlockError.Processed =>
            "No action taken since other thread has already processed the block."
              .asLeft[String]
              .pure[F]
          case BlockError.CasperIsBusy =>
            s"Casper put block in the queue: $status".asLeft[String].pure[F]
        }
        .merge

    private def prettyPrintUnmatchedSends(
        casper: MultiParentCasper[F],
        deploys: Seq[Signed[DeployData]]
    ): F[String] =
      casper.getRuntimeManager >>= (
        _.withRuntimeLock(runtime => StoragePrinter.prettyPrintUnmatchedSends(deploys, runtime))
      )

    def previewPrivateNames(
        deployer: ByteString,
        timestamp: Long,
        nameQty: Int
    ): F[ApiErr[Seq[ByteString]]] = {
      val rand                = Tools.unforgeableNameRng(PublicKey(deployer.toByteArray), timestamp)
      val safeQty             = nameQty min 1024
      val ids: Seq[BlockHash] = (0 until safeQty).map(_ => ByteString.copyFrom(rand.next()))
      ids.asRight[String].pure[F]
    }

    def lastFinalizedBlock: F[ApiErr[BlockInfo]] = {
      val errorMessage =
        "Could not get last finalized block, casper instance was not available yet."
      EngineCell[F].read >>= (
        _.withCasper[ApiErr[BlockInfo]](
          implicit casper =>
            for {
              lastFinalizedBlock <- casper.lastFinalizedBlock
              blockInfo          <- getFullBlockInfo(lastFinalizedBlock)
            } yield blockInfo.asRight,
          Log[F].warn(errorMessage).as(s"Error: $errorMessage".asLeft)
        )
      )
    }

    def isFinalized(
        hash: String
    ): F[ApiErr[Boolean]] = {
      val errorMessage =
        "Could not check if block is finalized, casper instance was not available yet."
      EngineCell[F].read >>= (
        _.withCasper[ApiErr[Boolean]](
          implicit casper =>
            for {
              lastFinalizedBlock <- casper.lastFinalizedBlock
              lastFinalizedBlockMetadata = BlockMetadata
                .fromBlock(lastFinalizedBlock, invalid = false)
              dag                   <- casper.blockDag
              givenBlockHash        = ProtoUtil.stringToByteString(hash)
              givenBlockMetadataOpt <- dag.lookup(givenBlockHash)
              result <- givenBlockMetadataOpt match {
                         case None =>
                           s"Could not find block with hash $hash"
                             .asLeft[Boolean]
                             .pure[F]
                         case Some(givenBlockMetadata) =>
                           DagOperations
                             .bfTraverseF(List(lastFinalizedBlockMetadata)) { b =>
                               b.parents.traverse(dag.lookup).map { parentOpts =>
                                 parentOpts.flatten.distinct
                                   .filter(_.blockNum >= givenBlockMetadata.blockNum)
                               }
                             }
                             .contains(givenBlockMetadata)
                             .map(_.asRight[Error])
                       }
            } yield result,
          Log[F].warn(errorMessage).as(s"Error: $errorMessage".asLeft)
        )
      )
    }

    def bondStatus(
        publicKey: ByteString
    ): F[ApiErr[Boolean]] = {
      val errorMessage =
        "Could not check if validator is bonded, casper instance was not available yet."
      EngineCell[F].read >>= (
        _.withCasper[ApiErr[Boolean]](
          implicit casper =>
            for {
              lastFinalizedBlock <- casper.lastFinalizedBlock
              runtimeManager     <- casper.getRuntimeManager
              postStateHash      = ProtoUtil.postStateHash(lastFinalizedBlock)
              bonds              <- runtimeManager.computeBonds(postStateHash)
              validatorBondOpt   = bonds.find(_.validator == publicKey)
            } yield validatorBondOpt.isDefined.asRight[Error],
          Log[F].warn(errorMessage).as(s"Error: $errorMessage".asLeft)
        )
      )
    }

    /**
      * Explore the data or continuation in the tuple space for specific blockHash
      *
      * @param term: the term you want to explore in the request. Be sure the first new should be `return`
      * @param blockHash: the block hash you want to explore
      * @param usePreStateHash: Each block has preStateHash and postStateHash. If usePreStateHash is true, the explore
      *                       would try to execute on preState.
      * */
    def exploratoryDeploy(
        term: String,
        blockHash: Option[String] = none,
        usePreStateHash: Boolean = false
    ): F[ApiErr[(Seq[Par], LightBlockInfo)]] = {
      val errorMessage =
        "Could not execute exploratory deploy, casper instance was not available yet."
      EngineCell[F].read >>= (
        _.withCasper(
          implicit casper =>
            for {
              isReadOnly <- casper.getValidator
              result <- isReadOnly match {
                         case Some(_) =>
                           "Exploratory deploy can only be executed on read-only RNode."
                             .asLeft[(Seq[Par], LightBlockInfo)]
                             .pure[F]
                         case None =>
                           for {
                             targetBlock <- if (blockHash.isEmpty)
                                             casper.lastFinalizedBlock.map(_.some)
                                           else
                                             for {
                                               hashByteString <- Base16
                                                                  .decode(blockHash.getOrElse(""))
                                                                  .map(ByteString.copyFrom)
                                                                  .liftTo[F](
                                                                    BlockRetrievalError(
                                                                      s"Input hash value is not valid hex string: $blockHash"
                                                                    )
                                                                  )
                                               block <- BlockStore[F].get(hashByteString)
                                             } yield block
                             res <- targetBlock.traverse(b => {
                                     val postStateHash =
                                       if (usePreStateHash) ProtoUtil.preStateHash(b)
                                       else ProtoUtil.postStateHash(b)
                                     for {
                                       runtimeManager <- casper.getRuntimeManager
                                       res <- runtimeManager
                                               .playExploratoryDeploy(term, postStateHash)
                                       lightBlockInfo <- getLightBlockInfo(b)
                                     } yield (res, lightBlockInfo)
                                   })

                           } yield res.fold(
                             s"Can not find block ${blockHash}".asLeft[(Seq[Par], LightBlockInfo)]
                           )(_.asRight[Error])
                       }
            } yield result,
          Log[F].warn(errorMessage).as(s"Error: $errorMessage".asLeft)
        )
      )
    }

    def getLatestMessage = {
      val errorMessage =
        "Could not execute exploratory deploy, casper instance was not available yet."
      EngineCell[F].read >>= (
        _.withCasper(
          implicit casper =>
            for {
              validatorOpt     <- casper.getValidator
              validator        <- validatorOpt.liftTo[F](ValidatorReadOnlyError)
              dag              <- casper.blockDag
              latestMessageOpt <- dag.latestMessage(ByteString.copyFrom(validator.bytes))
              latestMessage    <- latestMessageOpt.liftTo[F](NoBlockMessageError)
            } yield latestMessage.asRight[Error],
          Log[F].warn(errorMessage).as(s"Error: $errorMessage".asLeft)
        )
      )
    }
  }

}

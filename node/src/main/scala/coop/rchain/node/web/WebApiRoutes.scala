package coop.rchain.node.web

import cats.effect.Sync
import cats.syntax.all._
import cats.~>
import com.google.protobuf.ByteString
import coop.rchain.casper.PrettyPrinter
import coop.rchain.casper.protocol.DeployWithBlockInfo
import coop.rchain.node.api.WebApi
import coop.rchain.node.api.WebApi._
import coop.rchain.shared.Log
import io.circe.generic.semiauto._
import org.http4s.{HttpRoutes, Response}

object WebApiRoutes {

  def service[F[_]: Sync: Log, M[_]: Sync](
      webApi: WebApi[M]
  )(implicit mf: M ~> F): HttpRoutes[F] = {
    import coop.rchain.casper.protocol.{BlockInfo, LightBlockInfo}
    import io.circe._
    import io.circe.generic.auto._
    import io.circe.syntax._
    import org.http4s.circe._
    import org.http4s.{EntityDecoder, EntityEncoder, InvalidMessageBodyFailure, Request}

    val dsl = org.http4s.dsl.Http4sDsl[F]
    import dsl._

    implicit class ResponseErrorHandler(val res: F[Response[F]]) {
      def getErrorMsg(ex: Throwable) = if (ex.getMessage == null) ex.toString else ex.getMessage
      def handleResponseError =
        res
          .handleErrorWith {
            // The place where all API errors are handled
            // TODO: introduce error codes
            case err: InvalidMessageBodyFailure =>
              // Request JSON parse errors
              BadRequest(s"${getErrorMsg(err).take(250)}...".asJson)
            // Errors from BlockAPI
            case err: BlockApiException => BadRequest(getErrorMsg(err).asJson)
            case err: Throwable         =>
              // Logging only unanticipated errors, not related to Block API or input parsing (user errors)
              Log[F].error("HTTP API response error", err) *> BadRequest(getErrorMsg(err).asJson)
          }
    }

    def handleRequest[A, B](req: Request[F], f: A => M[B])(
        implicit decoder: EntityDecoder[F, A],
        encoder: EntityEncoder[F, B]
    ): F[Response[F]] =
      req
        .attemptAs[A]
        .value
        .flatMap(_.liftTo[F])
        .flatMap(a => mf(f(a)))
        .flatMap(Ok(_))
        .handleResponseError

    implicit class MEx[A](val ma: M[A]) {
      // Handle GET requests
      //   case GET -> Root / "last-finalized-block" =>
      //     webApi.lastFinalizedBlock.handle
      def handle(implicit encoder: EntityEncoder[F, A]): F[Response[F]] =
        mf(ma)
          .flatMap(Ok(_))
          .handleResponseError
    }

    implicit class RequestEx(val req: Request[F]) {
      // Handle POST requests
      //   case req @ POST -> Root / "deploy" =>
      //     req.handle[DeployRequest, String](webApi.deploy)
      def handle[A, B](
          f: A => M[B]
      )(implicit decoder: EntityDecoder[F, A], encoder: EntityEncoder[F, B]): F[Response[F]] =
        handleRequest[A, B](req, f)

      // Handle POST requests without input parameters
      //   case req @ POST -> Root / "last-finalized-block" =>
      //     req.handle_(webApi.lastFinalizedBlock)
      def handle_[B](
          f: M[B]
      )(implicit encoder: EntityEncoder[F, B]): F[Response[F]] =
        handleRequest[Unit, B](req, _ => f)
    }

    // TODO: Create generic encoders/decoders for
    // ADT's with discriminator field
    implicit val encodeByteString: Encoder[ByteString] =
      Encoder.encodeString.contramap[ByteString](PrettyPrinter.buildStringNoLimit)
    implicit val encodeLightBlockInfo: Encoder[LightBlockInfo] = deriveEncoder[LightBlockInfo]

    implicit val encodeBlockInfo: Encoder[BlockInfo] = deriveEncoder[BlockInfo]
    // Encoders
    implicit val stringEncoder              = jsonEncoderOf[F, String]
    implicit val booleanEncode              = jsonEncoderOf[F, Boolean]
    implicit val apiStatusEncoder           = jsonEncoderOf[F, ApiStatus]
    implicit val blockInfoEncoder           = jsonEncoderOf[F, BlockInfo]
    implicit val lightBlockEncoder          = jsonEncoderOf[F, LightBlockInfo]
    implicit val lightBlockListEnc          = jsonEncoderOf[F, List[LightBlockInfo]]
    implicit val dataRespEncoder            = jsonEncoderOf[F, DataResponse]
    implicit val prepareEncoder             = jsonEncoderOf[F, PrepareResponse]
    implicit val explRespEncoder            = jsonEncoderOf[F, ExploratoryDeployResponse]
    implicit val DeployWithBlockInfoEncoder = jsonEncoderOf[F, DeployWithBlockInfo]
    // Decoders
    implicit val deployRequestDecoder = jsonOf[F, DeployRequest]
    implicit val dataRequestDecoder   = jsonOf[F, DataRequest]
    implicit val prepareDecoder       = jsonOf[F, PrepareRequest]
    implicit val ExploreDeployRequest = jsonOf[F, ExploreDeployRequest]

    HttpRoutes.of[F] {
      case GET -> Root / "status" =>
        webApi.status.handle

      // Prepare deploy

      case GET -> Root / "prepare-deploy" =>
        webApi.prepareDeploy(none).handle

      case req @ POST -> Root / "prepare-deploy" =>
        req.handle[PrepareRequest, PrepareResponse](x => webApi.prepareDeploy(x.some))

      // Deploy

      case req @ POST -> Root / "deploy" =>
        req.handle[DeployRequest, String](webApi.deploy)

      case req @ POST -> Root / "explore-deploy" =>
        req.handle[String, ExploratoryDeployResponse](
          term => webApi.exploratoryDeploy(term, none[String], true)
        )

      case req @ POST -> Root / "explore-deploy-by-block-hash" =>
        req.handle[ExploreDeployRequest, ExploratoryDeployResponse](
          req => webApi.exploratoryDeploy(req.term, Some(req.blockHash), req.usePreStateHash)
        )

      // Get data

      case req @ POST -> Root / "data-at-name" =>
        req.handle[DataRequest, DataResponse](webApi.listenForDataAtName)

      // Blocks

      case GET -> Root / "last-finalized-block" =>
        webApi.lastFinalizedBlock.handle

      case GET -> Root / "block" / hash =>
        webApi.getBlock(hash).handle

      case GET -> Root / "blocks" =>
        webApi.getBlocks(1).handle

      case GET -> Root / "blocks" / IntVar(startBlockNumber) / IntVar(endBlockNumber) =>
        webApi.getBlocksByHeights(startBlockNumber.toLong, endBlockNumber.toLong).handle

      case GET -> Root / "blocks" / IntVar(depth) =>
        webApi.getBlocks(depth).handle

      case GET -> Root / "deploy" / deployId =>
        webApi.findDeploy(deployId).handle

      case GET -> Root / "v2" / "deploy" / deployId =>
        webApi.getDeploy(deployId).handle

      case GET -> Root / "is-finalized" / hash =>
        webApi.isFinalized(hash).handle
    }
  }

}

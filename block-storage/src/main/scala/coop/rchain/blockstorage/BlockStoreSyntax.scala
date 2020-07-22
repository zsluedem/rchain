package coop.rchain.blockstorage

import cats.effect.Sync
import cats.syntax.all._
import com.google.protobuf.ByteString
import coop.rchain.casper.PrettyPrinter
import coop.rchain.casper.protocol.BlockMessage
import coop.rchain.crypto.codec.Base16
import coop.rchain.models.BlockHash.BlockHash

trait BlockStoreSyntax {
  implicit final def syntaxBlockStore[F[_]: Sync](
      blockStore: BlockStore[F]
  ): BlockStoreOps[F] =
    new BlockStoreOps[F](blockStore)
}

/**
  * This kind of error should be in a group of fatal errors.
  * E.g. for unsafe `get` we are expecting the message to be in the store and if it's not, node should stop.
  *
  * The point is to have the syntax to recognize these places, categorize errors and have meaningful error messages
  * instead of generic text e.g. for Option - NoSuchElementException: None.get.
  */
final case class BlockStoreInconsistencyError(message: String) extends Exception(message)
final case class InvalidHexStringError(message: String)        extends Exception(message)
final case class HexStringTooShortError(message: String)       extends Exception(message)
final case class BlockStoreNotFind(message: String)            extends Exception(message)

final class BlockStoreOps[F[_]: Sync](
    // BlockStore extensions / syntax
    private val blockStore: BlockStore[F]
) {

  /**
    * Get block, "unsafe" because method expects block already in the block store.
    *
    * Unfortunately there is no way to get stack trace when error is thrown in async execution.
    * Monix does not have support and cats.effect support is in creation.
    * https://github.com/typelevel/cats-effect/pull/854
    * So extra source parameters are a desperate measure to indicate who is the caller.
    */
  def getUnsafe(hash: BlockHash)(
      implicit line: sourcecode.Line,
      file: sourcecode.File,
      enclosing: sourcecode.Enclosing
  ): F[BlockMessage] = {
    def source = s"${file.value}:${line.value} ${enclosing.value}"
    def errMsg = s"BlockStore is missing hash ${PrettyPrinter.buildString(hash)}\n  $source"
    blockStore.get(hash) >>= (_.liftTo(BlockStoreInconsistencyError(errMsg)))
  }

  def getByString(hash: String)(
      implicit line: sourcecode.Line,
      file: sourcecode.File,
      enclosing: sourcecode.Enclosing
  ): F[BlockMessage] = {
    def source = s"${file.value}:${line.value} ${enclosing.value}"
    for {
      hashByteString <- Base16
                         .decode(hash)
                         .map(ByteString.copyFrom)
                         .liftTo[F](
                           InvalidHexStringError(
                             s"Input hash value is not valid hex string: $hash\n $source"
                           )
                         )
      block <- getUnsafe(hashByteString)
    } yield block
  }

  // Be careful to use this method , because it would iterate the whole indexes to find the matched one which would cause performance problem
  // Trying to use BlockStore.get as much as possible would more be preferred
  def findBlockUnsafe(
      hash: String
  )(
      implicit line: sourcecode.Line,
      file: sourcecode.File,
      enclosing: sourcecode.Enclosing
  ): F[BlockMessage] = {
    def source = s"${file.value}:${line.value} ${enclosing.value}"
    for {
      _ <- HexStringTooShortError(
            s"Input hash value must be at least 6 characters: $hash\n $source"
          ).raiseError[F, BlockMessage]
            .whenA(hash.length < 6)
      findResult <- blockStore.find(h => Base16.encode(h.toByteArray).startsWith(hash), 1)
      block <- findResult.headOption.liftTo(
                BlockStoreNotFind(s"BlockStore can not fins $hash\n $source")
              )
    } yield block._2
  }
}

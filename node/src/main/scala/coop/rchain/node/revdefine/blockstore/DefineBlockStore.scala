package coop.rchain.node.revdefine.blockstore

import cats.effect.{Blocker, ContextShift, Sync}
import cats.syntax.all._
import com.google.protobuf.ByteString
import coop.rchain.blockstorage.{BlockStore, KeyValueBlockStore}
import coop.rchain.blockstorage.KeyValueBlockStore.{
  blockProtoToBytes,
  bytesToBlockProto,
  BlockStoreFatalError
}
import coop.rchain.casper.PrettyPrinter
import coop.rchain.casper.protocol.{BlockMessage, BlockMessageProto}
import coop.rchain.models.BlockHash.BlockHash
import coop.rchain.store.{KeyValueStore, KeyValueStoreManager}
import coop.rchain.shared.syntax._
import coop.rchain.shared.ByteStringOps._
import fs2._
import cats.effect.Blocker.liftExecutionContext
import scodec.bits.ByteVector

import java.nio.ByteBuffer
import scala.concurrent.ExecutionContext

trait DefineBlockStore[F[_]] {
  def get(blockHash: BlockHash): F[Option[BlockMessage]]
  def put(block: BlockMessage): F[Unit]
  def iterate[T](f: Stream[F, BlockMessage] => Stream[F, T]): F[Stream[F, T]]
  def iterateStream: F[Stream[F, BlockMessage]]
}

final case class KeyValueDefineBlockStore[F[_]: Sync: ContextShift](
    private val store: KeyValueStore[F],
    private val blocker: Blocker
) extends DefineBlockStore[F] {
  private def errorBlock(hash: BlockHash)(cause: String) = BlockStoreFatalError(
    s"Block decoding error, hash ${PrettyPrinter.buildString(hash)}. Cause: $cause"
  )

  override def get(blockHash: BlockHash): F[Option[BlockMessage]] =
    for {
      // Optional serialized block from the store
      bytes <- store.get1(blockHash.toDirectByteBuffer, ByteVector(_))
      // Decode protobuf message / throws if fail
      proto <- bytes.map(_.toArray).traverse(bytesToBlockProto[F])
      block <- proto.traverse(BlockMessage.from(_).leftMap(errorBlock(blockHash)).liftTo[F])
    } yield block

  private def blockProtoToBuffer(blockProto: BlockMessageProto): ByteBuffer = {
    val bytes              = blockProtoToBytes(blockProto)
    val buffer: ByteBuffer = ByteBuffer.allocateDirect(bytes.length)
    buffer.put(bytes).flip
  }

  override def put(block: BlockMessage): F[Unit] = {
    def toBuffer(b: BlockMessage) = blockProtoToBuffer(b.toProto)
    val hash                      = block.blockHash
    val keyBuffer                 = hash.toDirectByteBuffer
    store.put1(keyBuffer, block, toBuffer)
  }

  override def iterate[T](f: Stream[F, BlockMessage] => Stream[F, T]): F[Stream[F, T]] =
    store
      .iterate { iter =>
        Stream
          .fromBlockingIterator(blocker, iter)
          .evalMap {
            case (keyBytes, valueBytes) =>
              bytesToBlockProto(ByteVector(valueBytes).toArray)
                .map(b => (ByteString.copyFrom(keyBytes), b))
          }
          .evalMap {
            case (key, block) => BlockMessage.from(block).leftMap(errorBlock(key)).liftTo[F]
          }
          .through(f)
      }
  def iterateStream: F[Stream[F, BlockMessage]] =
    for {
      indexes <- store.iterate { iterator =>
                  Stream.chunk(
                    Chunk.iterable(
                      iterator.map { case (k, v) => (ByteVector(k), v) }.toIterable
                    )
                  )
                }
      result = indexes.evalMap {
        case (k, v) =>
          for {
            key        <- Sync[F].delay(ByteString.copyFrom(k.toArray))
            blockProto <- bytesToBlockProto(ByteVector(v).toArray)
            block <- BlockMessage
                      .from(blockProto)
                      .leftMap(errorBlock(key))
                      .liftTo[F]
          } yield block
      }
    } yield result
}

object DefineBlockStore {
  def apply[F[_]: Sync: ContextShift](
      kvm: KeyValueStoreManager[F],
      blocker: Blocker
  ): F[DefineBlockStore[F]] =
    kvm.store("blocks").map(KeyValueDefineBlockStore(_, blocker))

}

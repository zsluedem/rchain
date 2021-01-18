package coop.rchain.blockstorage

import cats.Applicative
import cats.syntax.all._
import fs2._
import coop.rchain.casper.protocol.{ApprovedBlock, BlockMessage}
import coop.rchain.models.BlockHash.BlockHash

trait BlockStore[F[_]] {
  def get(blockHash: BlockHash): F[Option[BlockMessage]]

  /**
    * Iterates over BlockStore and loads first n blocks according to predicate
    * @param p predicate
    * @param n limit for number of blocks to load
    * @return Sequence of [(BlockHash, BlockMessage)]
    */
  def find(p: BlockHash => Boolean, n: Int = 10000): F[Seq[(BlockHash, BlockMessage)]]

  def put(f: => (BlockHash, BlockMessage)): F[Unit]

  def contains(blockHash: BlockHash)(implicit applicativeF: Applicative[F]): F[Boolean] =
    get(blockHash).map(_.isDefined)

  def getApprovedBlock: F[Option[ApprovedBlock]]

  def putApprovedBlock(block: ApprovedBlock): F[Unit]

  def checkpoint(): F[Unit]

  def clear(): F[Unit]

  def close(): F[Unit]

  def iterateStream(): F[Stream[F, BlockMessage]]

  // Defaults

  def put(blockMessage: BlockMessage): F[Unit] =
    put((blockMessage.blockHash, blockMessage))

  def put(blockHash: BlockHash, blockMessage: BlockMessage): F[Unit] =
    put((blockHash, blockMessage))
}

object BlockStore {
  def apply[F[_]](implicit instance: BlockStore[F]): BlockStore[F] = instance
}

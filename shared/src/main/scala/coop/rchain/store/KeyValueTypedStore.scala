package coop.rchain.store

import fs2.Stream

trait KeyValueTypedStore[F[_], K, V] {
  def get(keys: Seq[K]): F[Seq[Option[V]]]

  def put(kvPairs: Seq[(K, V)]): F[Unit]

  def delete(keys: Seq[K]): F[Int]

  def contains(keys: Seq[K]): F[Seq[Boolean]]

  def toMap: F[Map[K, V]]

  def iterateStream(): F[Stream[F, (K, V)]]

}

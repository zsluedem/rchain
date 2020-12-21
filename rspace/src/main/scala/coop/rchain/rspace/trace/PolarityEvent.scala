package coop.rchain.rspace.trace
import coop.rchain.rspace.Blake2b256Hash

object PolarityEvent {
  trait Cardinality
  case object Linear    extends Cardinality
  case object NonLinear extends Cardinality
  case object Peek      extends Cardinality

  trait PolarityEvent {
    def isConflict(b: PolarityEvent): Boolean
  }
  trait SinglePolarityEvent extends PolarityEvent
  final case class ProducePolarityEvent(
      channels: Blake2b256Hash,
      cardinality: Cardinality,
      eventHash: Blake2b256Hash
  ) extends SinglePolarityEvent {
    override def isConflict(b: PolarityEvent): Boolean = b match {
      case p @ ProducePolarityEvent(_, _, _)    => PolarityEvent.isConflict(this, p)
      case c @ ConsumePolarityEvent(_, _, _)    => PolarityEvent.isConflict(this, c)
      case comm @ CommPolarityEvent(_, _, _, _) => PolarityEvent.isConflict(this, comm)
    }
  }
  final case class ConsumePolarityEvent(
      channels: Seq[Blake2b256Hash],
      cardinality: Cardinality,
      eventHash: Blake2b256Hash
  ) extends SinglePolarityEvent {
    override def isConflict(b: PolarityEvent): Boolean = b match {
      case p @ ProducePolarityEvent(_, _, _)    => PolarityEvent.isConflict(this, p)
      case c @ ConsumePolarityEvent(_, _, _)    => PolarityEvent.isConflict(this, c)
      case comm @ CommPolarityEvent(_, _, _, _) => PolarityEvent.isConflict(this, comm)
    }
  }
  final case class CommPolarityEvent(
      channels: Seq[Blake2b256Hash],
      producePolarityEvent: Seq[ProducePolarityEvent],
      consumePolarityEvent: ConsumePolarityEvent,
      matched: Set[SinglePolarityEvent]
  ) extends PolarityEvent {
    override def isConflict(b: PolarityEvent): Boolean = b match {
      case p @ ProducePolarityEvent(_, _, _)    => PolarityEvent.isConflict(this, p)
      case c @ ConsumePolarityEvent(_, _, _)    => PolarityEvent.isConflict(this, c)
      case comm @ CommPolarityEvent(_, _, _, _) => PolarityEvent.isConflict(this, comm)
    }

    def incoming: Seq[SinglePolarityEvent] =
      producePolarityEvent ++ Seq(consumePolarityEvent) diff matched.toSeq
  }

  def from(produce: Produce): ProducePolarityEvent = ProducePolarityEvent(
    produce.channelsHash,
    if (produce.persistent) NonLinear else Linear,
    produce.hash
  )

  def from(consume: Consume, isPeek: Boolean): ConsumePolarityEvent = ConsumePolarityEvent(
    consume.channelsHashes,
    if (consume.persistent) NonLinear else if (isPeek) Peek else Linear,
    consume.hash
  )

  def from(
      comm: COMM,
      incomingConsumes: Set[Consume],
      incomingProduces: Set[Produce]
  ): CommPolarityEvent = comm match {
    case COMM(consume, produces, peeks, _) => {
      val producePolarityEvents = produces.map(from)
      val consumePolarityEvent  = from(consume, peeks.nonEmpty)
      val peekInitiated = comm.peeks.nonEmpty
      val matchedConsume: Seq[SinglePolarityEvent] =
        if (incomingConsumes.contains(consume)) Seq.empty[SinglePolarityEvent]
        else Seq(consumePolarityEvent)
      val matchedProduce = produces.filter(!incomingProduces.contains(_) || peekInitiated).map(from)
      val matched        = matchedConsume ++ matchedProduce
      CommPolarityEvent(
        consume.channelsHashes,
        producePolarityEvents,
        consumePolarityEvent,
        matched.toSet
      )
    }
  }

  def toChannelPolarity(
      comm: COMM,
      incomingConsumes: Set[Consume],
      incomingProduces: Set[Produce]
  ): Seq[(Blake2b256Hash, CommPolarityEvent)] = {
    val commPolarityEvent = from(comm, incomingConsumes, incomingProduces)
    comm.consume.channelsHashes.map((_, commPolarityEvent))
  }

  def toChannelPolarity(
      consume: Consume,
      isPeek: Boolean
  ): Seq[(Blake2b256Hash, ConsumePolarityEvent)] = {
    val consumePolarityEvent = from(consume, isPeek)
    consume.channelsHashes.map((_, consumePolarityEvent))
  }

  def toChannelPolarity(produce: Produce): Seq[(Blake2b256Hash, ProducePolarityEvent)] = {
    val producePolarityEvent = from(produce)
    Seq((produce.channelsHash, producePolarityEvent))
  }

  private def isConflict(x: ProducePolarityEvent, y: ProducePolarityEvent): Boolean = false
  private def isConflict(x: ProducePolarityEvent, y: ConsumePolarityEvent): Boolean = true
  private def isConflict(x: ProducePolarityEvent, y: CommPolarityEvent): Boolean =
    y.incoming.exists {
      case ProducePolarityEvent(_, _, _) => false
      case _                             => true
    }
  private def isConflict(x: ConsumePolarityEvent, y: ProducePolarityEvent): Boolean =
    isConflict(y, x)
  private def isConflict(x: ConsumePolarityEvent, y: ConsumePolarityEvent): Boolean = false
  private def isConflict(x: ConsumePolarityEvent, y: CommPolarityEvent): Boolean = {
    y.incoming.exists {
      case ConsumePolarityEvent(_, _, _) => false
      case _                             => true
    }
  }

  private def isConflict(x: CommPolarityEvent, y: ProducePolarityEvent): Boolean = isConflict(y, x)
  private def isConflict(x: CommPolarityEvent, y: ConsumePolarityEvent): Boolean = isConflict(y, x)
  private def isConflict(x: CommPolarityEvent, y: CommPolarityEvent): Boolean = {
    val isNonLinear = y.matched.exists {
      case ConsumePolarityEvent(_, cardinality, _) => cardinality != NonLinear
      case ProducePolarityEvent(_, cardinality, _) => cardinality != NonLinear
    }
    val incomingDifferentPolarity = (x.incoming.exists {
      case ProducePolarityEvent(_, _, _) => true
      case ConsumePolarityEvent(_, _, _) => false
    }
      && y.incoming.exists {
        case ProducePolarityEvent(_, _, _) => false
        case ConsumePolarityEvent(_, _, _) => true
      }) ||
      (x.incoming.exists {
        case ProducePolarityEvent(_, _, _) => false
        case ConsumePolarityEvent(_, _, _) => true
      } &&
        y.incoming.exists {
          case ProducePolarityEvent(_, _, _) => true
          case ConsumePolarityEvent(_, _, _) => false
        })
    val matchedSame = y.matched.nonEmpty && x.matched.nonEmpty && y.matched == x.matched
    (matchedSame && isNonLinear) || incomingDifferentPolarity

  }

}

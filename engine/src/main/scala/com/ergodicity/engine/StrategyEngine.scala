package com.ergodicity.engine

import service.Portfolio.Portfolio
import strategy.{StrategyBuilder, StrategiesFactory, StrategyId}
import akka.actor.{LoggingFSM, Actor, ActorRef}
import com.ergodicity.engine.StrategyEngine._
import collection.mutable
import collection.immutable
import com.ergodicity.core.position.Position
import com.ergodicity.core.Isin
import com.ergodicity.engine.StrategyEngine.ManagedStrategy
import akka.util.Timeout
import akka.util.duration._
import akka.pattern.ask
import akka.pattern.pipe
import scalaz._
import Scalaz._
import com.ergodicity.core.PositionsTracking.{Positions, GetPositions}

object StrategyEngine {

  case class ManagedStrategy(ref: ActorRef)

  // Engine messages
  case object PrepareStrategies

  case object StartStrategies

  case object StopStrategies

  // Messages from strategies
  sealed trait StrategyNotification {
    def id: StrategyId
  }

  case class StrategyPosition(id: StrategyId, isin: Isin, position: Position) extends StrategyNotification

  case class StrategyReady(id: StrategyId, positions: Map[Isin, Position]) extends StrategyNotification

  // Positions Reconciliation
  sealed trait Reconciliation

  case object Reconciled extends Reconciliation

  case class Mismatched(mismatches: Iterable[Mismatch]) extends Reconciliation

  case class Mismatch(isin: Isin, portfolioPosition: Position, strategiesPosition: Position, strategiesAllocation: Map[StrategyId, Position])

  class ReconciliationFailed(mismatches: Iterable[Mismatch]) extends RuntimeException("Reconciliation failed; Mismatches size = " + mismatches.size)

}

sealed trait StrategyEngineState

object StrategyEngineState {

  case object Idle extends StrategyEngineState

  case object Preparing extends StrategyEngineState

  case object Reconciling extends StrategyEngineState

  case object StrategiesReady extends StrategyEngineState

}

sealed trait StrategyEngineData

object StrategyEngineData {

  case object Void extends StrategyEngineData

  case class AwaitingReadiness(strategies: Iterable[StrategyId]) extends StrategyEngineData {
    def isEmpty = strategies.size == 0
  }


}

abstract class StrategyEngine(factory: StrategiesFactory = StrategiesFactory.empty)
                             (implicit val services: Services) {
  engine: StrategyEngine with Actor =>


  protected val strategies = mutable.Map[StrategyId, ManagedStrategy]()

  def reportPosition(isin: Isin, position: Position)(implicit id: StrategyId) {
    self ! StrategyPosition(id, isin, position)
  }

  def reportReady(positions: Map[Isin, Position])(implicit id: StrategyId) {
    self ! StrategyReady(id, positions)
  }

  protected[engine] def reconcile(portfolioPositions: immutable.Map[Isin, Position],
                                       strategiesPositions: immutable.Map[(StrategyId, Isin), Position]): Reconciliation = {

    // Find each side position for given isin
    val groupedByIsin = (portfolioPositions.keySet ++ strategiesPositions.keySet.map(_._2)).map(isin => {
      val portfolioPos = portfolioPositions.get(isin) getOrElse Position.flat
      val strategiesPos = strategiesPositions.filterKeys(_._2 == isin).values.foldLeft(Position.flat)(_ |+| _)

      (isin, portfolioPos, strategiesPos)
    })

    // Find mismatched position
    val mismatches = groupedByIsin.map {
      case (isin, portfolioPos, strategiesPos) if (portfolioPos == strategiesPos) => None
      case (isin, portfolioPos, strategiesPos) =>
        val allocation = strategiesPositions.filterKeys(_._2 == isin).toSeq.map {
          case ((id, _), position) => id -> position
        }.toMap
        Some(Mismatch(isin, portfolioPos, strategiesPos, allocation))
    }

    // If found any mismath, reconcilation failed
    val flatten = mismatches.flatten
    if (flatten.isEmpty) Reconciled else Mismatched(flatten)
  }
}

class StrategyEngineActor(factory: StrategiesFactory = StrategiesFactory.empty)
                         (implicit services: Services) extends StrategyEngine(factory)(services) with Actor with LoggingFSM[StrategyEngineState, StrategyEngineData] {

  import StrategyEngineState._
  import StrategyEngineData._

  implicit val timeout = Timeout(5.seconds)

  private val positions = mutable.Map[(StrategyId, Isin), Position]()

  startWith(Idle, Void)

  when(Idle) {
    case Event(PrepareStrategies, Void) =>
      factory.strategies foreach start
      log.info("Preparing strategies = " + strategies.keys)
      goto(Preparing) using AwaitingReadiness(strategies.keys)
  }

  when(Preparing) {
    case Event(StrategyReady(id, strategyPositions), w@AwaitingReadiness(awaiting)) =>
      log.info("Strategy ready, Id = " + id + ", positions = " + strategyPositions)
      strategyPositions.foreach {
        case (isin, position) =>
          positions(id -> isin) = position
      }
      val remaining = w.copy(strategies = awaiting filterNot (_ == id))

      if (remaining.isEmpty) {
        (services(Portfolio) ? GetPositions).mapTo[Positions] map (p => reconcile(p.positions, positions.toMap)) pipeTo self
        goto(Reconciling) using Void
      }
      else stay() using remaining
  }

  when(Reconciling) {
    case Event(Reconciled, Void) =>
      goto(StrategiesReady)

    case Event(Mismatched(mismatches), Void) =>
      log.error("Reconciliation failed, mismatches = "+mismatches)
      throw new ReconciliationFailed(mismatches)
  }

  when(StrategiesReady) {
    case Event(_, _) => stay()
  }

  whenUnhandled {
    case Event(pos@StrategyPosition(id, isin, position), _) =>
      positions(id -> isin) = position
      stay()
  }

  initialize

  private def start(builder: StrategyBuilder) {
    log.info("Start strategy, Id = " + builder.id)
    strategies(builder.id) = ManagedStrategy(context.actorOf(builder.props(this), builder.id.toString))
  }
}
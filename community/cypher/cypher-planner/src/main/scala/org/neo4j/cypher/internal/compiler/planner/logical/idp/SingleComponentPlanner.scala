/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.planner.logical.idp

import org.neo4j.cypher.internal.compiler.planner.logical.LeafPlanFinder
import org.neo4j.cypher.internal.compiler.planner.logical.LogicalPlanningContext
import org.neo4j.cypher.internal.compiler.planner.logical.LogicalPlanningSupport.RichHint
import org.neo4j.cypher.internal.compiler.planner.logical.QueryPlannerKit
import org.neo4j.cypher.internal.compiler.planner.logical.SortPlanner
import org.neo4j.cypher.internal.compiler.planner.logical.idp.IDPQueryGraphSolver.extraRequirementForInterestingOrder
import org.neo4j.cypher.internal.compiler.planner.logical.idp.SingleComponentPlanner.planSinglePattern
import org.neo4j.cypher.internal.compiler.planner.logical.idp.expandSolverStep.PrecomputedQPPInnerPlans
import org.neo4j.cypher.internal.compiler.planner.logical.idp.expandSolverStep.QPPInnerPlans
import org.neo4j.cypher.internal.compiler.planner.logical.idp.expandSolverStep.planSinglePatternSide
import org.neo4j.cypher.internal.compiler.planner.logical.idp.expandSolverStep.planSingleProjectEndpoints
import org.neo4j.cypher.internal.compiler.planner.logical.ordering.InterestingOrderConfig
import org.neo4j.cypher.internal.compiler.planner.logical.steps.BestPlans
import org.neo4j.cypher.internal.compiler.planner.logical.steps.leafPlanOptions
import org.neo4j.cypher.internal.ir.NodeConnection
import org.neo4j.cypher.internal.ir.PatternRelationship
import org.neo4j.cypher.internal.ir.QueryGraph
import org.neo4j.cypher.internal.logical.plans.Argument
import org.neo4j.cypher.internal.logical.plans.LogicalPlan
import org.neo4j.exceptions.InternalException
import org.neo4j.time.Stopwatch

/**
 * This class contains the main IDP loop in the cost planner.
 * This planner is based on the paper
 *
 * "Iterative Dynamic Programming: A New Class of Query Optimization Algorithms"
 *
 * written by Donald Kossmann and Konrad Stocker
 */
case class SingleComponentPlanner(
  solverConfig: SingleComponentIDPSolverConfig = DefaultIDPSolverConfig,
  leafPlanFinder: LeafPlanFinder = leafPlanOptions
)(monitor: IDPQueryGraphSolverMonitor) extends SingleComponentPlannerTrait {

  override def planComponent(
    qg: QueryGraph,
    context: LogicalPlanningContext,
    kit: QueryPlannerKit,
    interestingOrderConfig: InterestingOrderConfig
  ): BestPlans = {
    val bestPlansPerAvailableSymbol = leafPlanFinder(context.plannerState.config, qg, interestingOrderConfig, context)

    val qppInnerPlans = new PrecomputedQPPInnerPlans(qg, context)

    val bestPlans =
      if (qg.nodeConnections.nonEmpty) {
        val leaves = bestPlansPerAvailableSymbol.flatMap(bestPlans =>
          bestPlans.bestResultFulfillingReq.toSeq :+ bestPlans.bestResult
        ).toSet

        val orderRequirement = extraRequirementForInterestingOrder(context, interestingOrderConfig)
        val generators = solverConfig.solvers(qppInnerPlans).map(_(qg))
        val generator = IDPQueryGraphSolver.composeSolverSteps(qg, interestingOrderConfig, kit, context, generators)

        val solver = new IDPSolver[NodeConnection, LogicalPlan, LogicalPlanningContext](
          generator = generator,
          projectingSelector = kit.pickBest,
          maxTableSize = solverConfig.maxTableSize,
          iterationDurationLimit = solverConfig.iterationDurationLimit,
          extraRequirement = orderRequirement,
          monitor = monitor,
          stopWatchFactory = () => Stopwatch.start()
        )

        monitor.initTableFor(qg)
        val seed = initTable(qg, kit, leaves, qppInnerPlans, context, interestingOrderConfig)
        monitor.startIDPIterationFor(qg)
        val result = solver(seed, qg.nodeConnections.toSeq, context)
        monitor.endIDPIterationFor(qg, result.bestResult)

        BestResults(result.bestResult, result.bestResultFulfillingReq)
      } else {
        val solutionPlans =
          if (qg.shortestPathPatterns.isEmpty) {
            bestPlansPerAvailableSymbol
              .filter(bestPlans => planFullyCoversQG(qg, bestPlans.bestResult))
          } else {
            bestPlansPerAvailableSymbol.map(_.map(kit.select(_, qg)))
              .filter(bestPlans => planFullyCoversQG(qg, bestPlans.bestResult))
          }
        if (solutionPlans.size != 1) {
          throw new InternalException("Found no leaf plan for connected component. This must not happen. QG: " + qg)
        }

        val result = solutionPlans.head

        monitor.noIDPIterationFor(qg, result.bestResult)
        result
      }

    if (IDPQueryGraphSolver.VERBOSE) {
      println(
        s"Result (picked best plan):\n\tPlan #${bestPlans.bestResult.debugId}\n\t${bestPlans.bestResult.toString}"
      )
      bestPlans.bestResultFulfillingReq.foreach { bSP =>
        println(s"Result (picked best sorted plan):\n\tPlan #${bSP.debugId}\n\t${bSP.toString}")
      }
      println("\n")
    }
    bestPlans
  }

  private def planFullyCoversQG(qg: QueryGraph, plan: LogicalPlan) =
    (qg.idsWithoutOptionalMatchesOrUpdates -- plan.availableSymbols -- qg.argumentIds).isEmpty

  private def initTable(
    qg: QueryGraph,
    kit: QueryPlannerKit,
    leaves: Set[LogicalPlan],
    qppInnerPlans: QPPInnerPlans,
    context: LogicalPlanningContext,
    interestingOrderConfig: InterestingOrderConfig
  ): Seed[NodeConnection, LogicalPlan] = {
    for (pattern <- qg.nodeConnections)
      yield {
        val plans = planSinglePattern(qg, pattern, leaves, qppInnerPlans, context).map(plan => kit.select(plan, qg))
        // From _all_ plans (even if they are sorted), put the best into the seed
        // with `false`. We don't want to compare just the ones that are unsorted
        // in isolation, because it could be that the best overall plan is sorted.
        val best =
          kit.pickBest(plans, s"best overall plan for $pattern")
            .map(p => ((Set(pattern), /* ordered = */ false), p))

        val result: Iterable[((Set[NodeConnection], Boolean), LogicalPlan)] =
          if (interestingOrderConfig.orderToSolve.isEmpty) {
            best
          } else {
            val ordered =
              plans.flatMap(plan => SortPlanner.planIfAsSortedAsPossible(plan, interestingOrderConfig, context))
            // Also add the best sorted plan into the seed with `true`.
            val bestWithSort = kit.pickBest(ordered, s"best sorted plan for $pattern")
              .map(p => ((Set(pattern), /* ordered = */ true), p))
            best ++ bestWithSort
          }

        if (result.isEmpty)
          throw new InternalException(
            "Found no access plan for a pattern relationship in a connected component. This must not happen."
          )

        result
      }
  }.flatten
}

trait SingleComponentPlannerTrait {

  def planComponent(
    qg: QueryGraph,
    context: LogicalPlanningContext,
    kit: QueryPlannerKit,
    interestingOrderConfig: InterestingOrderConfig
  ): BestPlans
  def solverConfig: SingleComponentIDPSolverConfig
}

object SingleComponentPlanner {

  sealed private trait SinglePatternSolutions {
    def getSolutions: Iterable[LogicalPlan]
  }

  private case class NonExpandSolutions(solutions: Option[LogicalPlan]) extends SinglePatternSolutions {
    override def getSolutions: Iterable[LogicalPlan] = solutions
  }

  private case class ExpandSolutions(leftExpand: Option[LogicalPlan], rightExpand: Option[LogicalPlan])
      extends SinglePatternSolutions {
    override def getSolutions: Iterable[LogicalPlan] = Set(leftExpand, rightExpand).flatten
  }

  /**
   * Plan a single [[NodeConnection]].
   * Return plan candidates on top of all leaf plans where it is possible.
   */
  def planSinglePattern(
    qg: QueryGraph,
    patternToSolve: NodeConnection,
    leaves: Set[LogicalPlan],
    qppInnerPlans: QPPInnerPlans,
    context: LogicalPlanningContext
  ): Iterable[LogicalPlan] = {
    val solveds = context.staticComponents.planningAttributes.solveds

    val perLeafSolutions: Map[LogicalPlan, SinglePatternSolutions] = leaves.map { leaf =>
      val solvedQg = solveds.get(leaf.id).asSinglePlannerQuery.lastQueryGraph
      val solutions = patternToSolve match {
        case pattern if solvedQg.nodeConnections.contains(pattern) =>
          // if the leaf already solves the pattern, simply return that
          NonExpandSolutions(Some(leaf))
        case _ if solvedQg.nodeConnections.nonEmpty =>
          // Avoid planning an Expand on a plan that already solves another relationship.
          // That is not supposed to happen when we initialize the table, but rather during IDP.
          NonExpandSolutions(None)
        case pattern: PatternRelationship if solvedQg.allCoveredIds.contains(pattern.name) =>
          NonExpandSolutions(Some(planSingleProjectEndpoints(pattern, leaf, context)))
        case pattern =>
          val (start, end) = pattern.nodes
          val leftExpand = planSinglePatternSide(qg, pattern, leaf, start, qppInnerPlans, context)
          val rightExpand = planSinglePatternSide(qg, pattern, leaf, end, qppInnerPlans, context)
          ExpandSolutions(leftExpand, rightExpand)
      }
      leaf -> solutions
    }.toMap

    val cartesianProductsAndJoins = {
      val (start, end) = patternToSolve.nodes
      val startJoinNodes = Set(start)
      val endJoinNodes = Set(end)
      val maybeStartPlan = leaves
        .find(leaf =>
          solveds(leaf.id).asSinglePlannerQuery.queryGraph.patternNodes == startJoinNodes
            && !leaf.isInstanceOf[Argument]
        )
      val maybeEndPlan = leaves
        .find(leaf =>
          solveds(leaf.id).asSinglePlannerQuery.queryGraph.patternNodes == endJoinNodes
            && !leaf.isInstanceOf[Argument]
        )
        // We are not allowed to plan CP or joins with identical LHS and RHS
        .filter(!maybeStartPlan.contains(_))

      val maybeCartesianProduct =
        planSinglePatternCartesian(qg, patternToSolve, start, maybeStartPlan, maybeEndPlan, qppInnerPlans, context)
      val maybeLeftExpand = maybeStartPlan.map(perLeafSolutions).collect {
        case ExpandSolutions(leftExpand, _) => leftExpand
      }.flatten
      val maybeRightExpand = maybeEndPlan.map(perLeafSolutions).collect {
        case ExpandSolutions(_, rightExpand) => rightExpand
      }.flatten

      val joins = planSinglePatternJoins(
        qg,
        maybeLeftExpand,
        maybeRightExpand,
        startJoinNodes,
        endJoinNodes,
        maybeStartPlan,
        maybeEndPlan,
        context
      )
      maybeCartesianProduct ++ joins
    }

    perLeafSolutions.values.flatMap(_.getSolutions) ++ cartesianProductsAndJoins
  }

  def planSinglePatternCartesian(
    qg: QueryGraph,
    pattern: NodeConnection,
    start: String,
    maybeStartPlan: Option[LogicalPlan],
    maybeEndPlan: Option[LogicalPlan],
    qppInnerPlans: QPPInnerPlans,
    context: LogicalPlanningContext
  ): Option[LogicalPlan] = (maybeStartPlan, maybeEndPlan) match {
    case (Some(startPlan), Some(endPlan)) =>
      planSinglePatternSide(
        qg,
        pattern,
        context.staticComponents.logicalPlanProducer.planCartesianProduct(startPlan, endPlan, context),
        start,
        qppInnerPlans,
        context
      )
    case _ => None
  }

  /**
   * If there are hints and the query graph is small, joins have to be constructed as an alternative here, otherwise the hints might not be able to be fulfilled.
   * Creating joins if the query graph is larger will lead to too many joins.
   */
  def planSinglePatternJoins(
    qg: QueryGraph,
    leftExpand: Option[LogicalPlan],
    rightExpand: Option[LogicalPlan],
    startJoinNodes: Set[String],
    endJoinNodes: Set[String],
    maybeStartPlan: Option[LogicalPlan],
    maybeEndPlan: Option[LogicalPlan],
    context: LogicalPlanningContext
  ): Iterable[LogicalPlan] = (maybeStartPlan, maybeEndPlan) match {
    case (Some(startPlan), Some(endPlan)) if qg.hints.nonEmpty && qg.size == 1 =>
      val startJoinHints = qg.joinHints.filter(_.coveredBy(startJoinNodes))
      val endJoinHints = qg.joinHints.filter(_.coveredBy(endJoinNodes))
      val join1a = leftExpand.map(expand =>
        context.staticComponents.logicalPlanProducer.planNodeHashJoin(
          endJoinNodes,
          expand,
          endPlan,
          endJoinHints,
          context
        )
      )
      val join1b = leftExpand.map(expand =>
        context.staticComponents.logicalPlanProducer.planNodeHashJoin(
          endJoinNodes,
          endPlan,
          expand,
          endJoinHints,
          context
        )
      )
      val join2a = rightExpand.map(expand =>
        context.staticComponents.logicalPlanProducer.planNodeHashJoin(
          startJoinNodes,
          startPlan,
          expand,
          startJoinHints,
          context
        )
      )
      val join2b = rightExpand.map(expand =>
        context.staticComponents.logicalPlanProducer.planNodeHashJoin(
          startJoinNodes,
          expand,
          startPlan,
          startJoinHints,
          context
        )
      )
      join1a ++ join1b ++ join2a ++ join2b
    case _ => None
  }
}

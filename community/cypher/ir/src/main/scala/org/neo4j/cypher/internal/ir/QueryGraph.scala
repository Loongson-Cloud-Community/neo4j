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
package org.neo4j.cypher.internal.ir

import org.neo4j.cypher.internal.ast.Hint
import org.neo4j.cypher.internal.ast.UsingJoinHint
import org.neo4j.cypher.internal.ast.prettifier.ExpressionStringifier
import org.neo4j.cypher.internal.expressions.Expression
import org.neo4j.cypher.internal.expressions.LabelName
import org.neo4j.cypher.internal.expressions.PartialPredicate
import org.neo4j.cypher.internal.expressions.PropertyKeyName
import org.neo4j.cypher.internal.expressions.RelTypeName
import org.neo4j.cypher.internal.ir.ast.IRExpression
import org.neo4j.cypher.internal.ir.helpers.ExpressionConverters.PredicateConverter
import org.neo4j.cypher.internal.util.Foldable.FoldableAny

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.math.Ordering.Implicits
import scala.runtime.ScalaRunTime

/**
 * This is one of the core classes used during query planning. It represents the declarative query,
 * it contains no more information than the AST, but it contains data in a format that is easier
 * to consume by the planner. If you want to trace this back to the original query - one QueryGraph
 * represents all the MATCH, OPTIONAL MATCHes, and update clauses between two WITHs.
 */
case class QueryGraph(
  patternRelationships: Set[PatternRelationship] = Set.empty,
  quantifiedPathPatterns: Set[QuantifiedPathPattern] = Set.empty,
  patternNodes: Set[String] = Set.empty,
  argumentIds: Set[String] = Set.empty,
  selections: Selections = Selections(),
  optionalMatches: IndexedSeq[QueryGraph] = Vector.empty,
  hints: Set[Hint] = Set.empty,
  shortestPathPatterns: Set[ShortestPathPattern] = Set.empty,
  mutatingPatterns: IndexedSeq[MutatingPattern] = IndexedSeq.empty
  // !!! If you change anything here, make sure to update the equals, ++ and hashCode methods at the bottom of this class !!!
) extends UpdateGraph {

  val nodeConnections: Set[NodeConnection] = Set.empty[NodeConnection] ++ patternRelationships ++ quantifiedPathPatterns

  /**
   * Dependencies from this QG to variables - from WHERE predicates and update clauses using expressions
   */
  def dependencies: Set[String] =
    optionalMatches.flatMap(_.dependencies).toSet ++
      selections.predicates.flatMap(_.dependencies) ++
      mutatingPatterns.flatMap(_.dependencies) ++
      quantifiedPathPatterns.flatMap(_.dependencies) ++
      argumentIds

  /**
   * The size of a QG is defined as the number of node connections that are introduced
   */
  def size: Int = nodeConnections.size

  def isEmpty: Boolean = this == QueryGraph.empty

  def nonEmpty: Boolean = !isEmpty

  def mapSelections(f: Selections => Selections): QueryGraph =
    copy(
      selections = f(selections),
      optionalMatches = optionalMatches.map(_.mapSelections(f)),
      quantifiedPathPatterns = quantifiedPathPatterns.map(qpp => qpp.copy(pattern = qpp.pattern.mapSelections(f)))
    )

  def addPatternNodes(nodes: String*): QueryGraph =
    copy(patternNodes = patternNodes ++ nodes)

  def addPatternRelationship(rel: PatternRelationship): QueryGraph =
    copy(
      patternNodes = patternNodes ++ rel.coveredNodeIds,
      patternRelationships = patternRelationships + rel
    )

  def addNodeConnection(connection: NodeConnection): QueryGraph = {
    connection match {
      case patternRelationship: PatternRelationship => addPatternRelationship(patternRelationship)
      case qpp: QuantifiedPathPattern               => addQuantifiedPathPattern(qpp)
    }
  }

  def addPatternRelationships(rels: Set[PatternRelationship]): QueryGraph =
    rels.foldLeft[QueryGraph](this)((qg, rel) => qg.addPatternRelationship(rel))

  def addQuantifiedPathPattern(pattern: QuantifiedPathPattern): QueryGraph =
    copy(
      patternNodes = patternNodes ++ pattern.coveredNodeIds,
      quantifiedPathPatterns = quantifiedPathPatterns + pattern
    )

  def addQuantifiedPathPatterns(pattern: Set[QuantifiedPathPattern]): QueryGraph =
    pattern.foldLeft[QueryGraph](this)((qg, rel) => qg.addQuantifiedPathPattern(rel))

  def addShortestPath(shortestPath: ShortestPathPattern): QueryGraph = {
    val rel = shortestPath.rel
    copy(
      patternNodes = patternNodes + rel.nodes._1 + rel.nodes._2,
      shortestPathPatterns = shortestPathPatterns + shortestPath
    )
  }

  /**
   * @return all recursively included query graphs, with leaf information for Eagerness analysis.
   *         Query graphs from pattern expressions and pattern comprehensions will generate variable names that might clash with existing names, so this method
   *         is not safe to use for planning pattern expressions and pattern comprehensions.
   */
  lazy val allQGsWithLeafInfo: Seq[QgWithLeafInfo] = {
    val iRExpressions: Seq[QgWithLeafInfo] = this.folder.findAllByClass[IRExpression].flatMap((e: IRExpression) =>
      e.query.allQGsWithLeafInfo
    )
    QgWithLeafInfo.qgWithNoStableIdentifierAndOnlyLeaves(this) +:
      (iRExpressions ++
        optionalMatches.flatMap(_.allQGsWithLeafInfo) ++
        quantifiedPathPatterns.flatMap(_.pattern.allQGsWithLeafInfo))
  }

  /**
   * Includes not only pattern nodes in the read part of the query graph, but also pattern nodes from CREATE and MERGE
   */
  def allPatternNodes: collection.Set[String] = {
    val nodes = mutable.Set[String]()
    collectAllPatternNodes(nodes.add)
    nodes
  }

  private def collectAllPatternNodes(f: String => Unit): Unit = {
    patternNodes.foreach(f)
    optionalMatches.foreach(m => m.allPatternNodes.foreach(f))
    for {
      create <- createPatterns
      createNode <- create.nodes
    } {
      f(createNode.idName)
    }
    mergeNodePatterns.foreach(p => f(p.createNode.idName))
    mergeRelationshipPatterns.foreach(p => p.createNodes.foreach(pp => f(pp.idName)))
  }

  def allPatternRelationshipsRead: Set[PatternRelationship] =
    patternRelationships ++ optionalMatches.flatMap(_.allPatternRelationshipsRead) ++ shortestPathPatterns.map(_.rel)

  def allPatternNodesRead: Set[String] =
    patternNodes ++ optionalMatches.flatMap(_.allPatternNodesRead)

  def addShortestPaths(shortestPaths: ShortestPathPattern*): QueryGraph =
    shortestPaths.foldLeft(this)((qg, p) => qg.addShortestPath(p))

  def addArgumentId(newId: String): QueryGraph = copy(argumentIds = argumentIds + newId)

  def addArgumentIds(newIds: Seq[String]): QueryGraph = copy(argumentIds = argumentIds ++ newIds)

  def addSelections(selections: Selections): QueryGraph =
    copy(selections = Selections(selections.predicates ++ this.selections.predicates))

  def addPredicates(predicates: Expression*): QueryGraph = {
    val newSelections = Selections(predicates.flatMap(_.asPredicates).toSet)
    copy(selections = selections ++ newSelections)
  }

  def addPredicates(predicates: Set[Predicate]): QueryGraph = {
    val newSelections = Selections(selections.predicates ++ predicates)
    copy(selections = newSelections)
  }

  def removePredicates(predicates: Set[Predicate]): QueryGraph = {
    val newSelections = Selections(selections.predicates -- predicates)
    copy(selections = newSelections)
  }

  def addPredicates(outerScope: Set[String], predicates: Expression*): QueryGraph = {
    val newSelections = Selections(predicates.flatMap(_.asPredicates(outerScope)).toSet)
    copy(selections = selections ++ newSelections)
  }

  def addHints(addedHints: IterableOnce[Hint]): QueryGraph = {
    copy(hints = hints ++ addedHints)
  }

  def withoutHints(hintsToIgnore: Set[Hint]): QueryGraph = copy(
    hints = hints.diff(hintsToIgnore),
    optionalMatches = optionalMatches.map(_.withoutHints(hintsToIgnore))
  )

  def withoutArguments(): QueryGraph = withArgumentIds(Set.empty)

  def withArgumentIds(newArgumentIds: Set[String]): QueryGraph =
    copy(argumentIds = newArgumentIds)

  def withAddedOptionalMatch(optionalMatch: QueryGraph): QueryGraph = {
    val argumentIds = allCoveredIds intersect optionalMatch.allCoveredIds
    copy(optionalMatches = optionalMatches :+ optionalMatch.addArgumentIds(argumentIds.toIndexedSeq))
  }

  def withOptionalMatches(optionalMatches: IndexedSeq[QueryGraph]): QueryGraph = {
    copy(optionalMatches = optionalMatches)
  }

  def withMergeMatch(matchGraph: QueryGraph): QueryGraph = {
    if (mergeQueryGraph.isEmpty) throw new IllegalArgumentException("Don't add a merge to this non-merge QG")

    // NOTE: Merge can only contain one mutating pattern
    assert(mutatingPatterns.length == 1)
    val newMutatingPattern = mutatingPatterns.collectFirst {
      case p: MergeNodePattern         => p.copy(matchGraph = matchGraph)
      case p: MergeRelationshipPattern => p.copy(matchGraph = matchGraph)
    }.get

    copy(argumentIds = matchGraph.argumentIds, mutatingPatterns = IndexedSeq(newMutatingPattern))
  }

  def withSelections(selections: Selections): QueryGraph = copy(selections = selections)

  def withHints(hints: Set[Hint]): QueryGraph = copy(hints = hints)

  /**
   * Sets both patternNodes and patternRelationships from this pattern relationship. Compare with `addPatternRelationship`.
   * @param pattern the relationship defining the pattern of this query graph
   */
  def withPattern(pattern: PatternRelationship): QueryGraph =
    copy(
      patternNodes = Set(pattern.nodes._1, pattern.nodes._2),
      patternRelationships = Set(pattern)
    )

  def withPatternRelationships(patterns: Set[PatternRelationship]): QueryGraph =
    copy(patternRelationships = patterns)

  def withQuantifiedPathPatterns(patterns: Set[QuantifiedPathPattern]): QueryGraph =
    copy(quantifiedPathPatterns = patterns)

  def withAddedPatternRelationships(patterns: Set[PatternRelationship]): QueryGraph =
    copy(patternRelationships = patternRelationships ++ patterns)

  def withPatternNodes(nodes: Set[String]): QueryGraph =
    copy(patternNodes = nodes)

  private def knownProperties(idName: String): Set[PropertyKeyName] =
    selections.allPropertyPredicatesInvolving.getOrElse(idName, Set.empty).map(_.propertyKey)

  private def possibleLabelsOnNode(node: String): Set[LabelName] = {
    val label = selections
      .allHasLabelsInvolving.getOrElse(node, Set.empty)
      .flatMap(_.labels)
    val labelOrType = selections
      .allHasLabelsOrTypesInvolving.getOrElse(node, Set.empty)
      .flatMap(_.labelsOrTypes).map(_.asLabelName)
    label ++ labelOrType
  }

  def inlinedRelTypes(rel: String): Set[RelTypeName] = {
    patternRelationships
      .find(_.name == rel)
      .toSet[PatternRelationship]
      .flatMap(_.types.toSet)
  }

  private def possibleTypesOnRel(rel: String): Set[RelTypeName] = {
    val whereClauseTypes = selections
      .allHasTypesInvolving.getOrElse(rel, Set.empty)
      .flatMap(_.types)

    val whereClauseLabelOrTypes = selections
      .allHasLabelsOrTypesInvolving.getOrElse(rel, Set.empty)
      .flatMap(_.labelsOrTypes).map(lblOrType => RelTypeName(lblOrType.name)(lblOrType.position))

    inlinedRelTypes(rel) ++ whereClauseTypes ++ whereClauseLabelOrTypes
  }

  private def traverseAllQueryGraphs[A](f: QueryGraph => Set[A]): Set[A] =
    f(this) ++
      optionalMatches.flatMap(_.traverseAllQueryGraphs(f)) ++
      quantifiedPathPatterns.flatMap(_.pattern.traverseAllQueryGraphs(f))

  def allPossibleLabelsOnNode(node: String): Set[LabelName] =
    traverseAllQueryGraphs(_.possibleLabelsOnNode(node))

  def allPossibleTypesOnRel(rel: String): Set[RelTypeName] =
    traverseAllQueryGraphs(_.possibleTypesOnRel(rel))

  def allKnownPropertiesOnIdentifier(idName: String): Set[PropertyKeyName] =
    traverseAllQueryGraphs(_.knownProperties(idName))

  def allSelections: Selections =
    Selections(traverseAllQueryGraphs(_.selections.predicates))

  def coveredIdsForPatterns: Set[String] = {
    val patternRelIds = nodeConnections.flatMap(_.coveredIds)
    patternNodes ++ patternRelIds
  }

  /**
   * Variables are bound after matching this QG, but before optional
   * matches and updates have been applied
   */
  def idsWithoutOptionalMatchesOrUpdates: Set[String] =
    coveredIdsForPatterns ++
      argumentIds ++
      shortestPathPatterns.flatMap(_.name) ++
      quantifiedPathPatterns.flatMap(_.groupings)

  /**
   * All variables that are bound after this QG has been matched
   */
  def allCoveredIds: Set[String] = {
    val otherSymbols = optionalMatches.flatMap(_.allCoveredIds) ++ mutatingPatterns.flatMap(_.coveredIds)
    idsWithoutOptionalMatchesOrUpdates ++ otherSymbols
  }

  def allHints: Set[Hint] =
    hints ++ optionalMatches.flatMap(_.allHints)

  def ++(other: QueryGraph): QueryGraph = {
    other match {
      case QueryGraph(
          otherPatternRelationships,
          otherQuantifiedPathPatterns,
          otherPatternNodes,
          otherArgumentIds,
          otherSelections,
          otherOptionalMatches,
          otherHints,
          otherShortestPathPatterns,
          otherMutatingPatterns
        ) =>
        QueryGraph(
          selections = selections ++ otherSelections,
          patternNodes = patternNodes ++ otherPatternNodes,
          quantifiedPathPatterns = quantifiedPathPatterns ++ otherQuantifiedPathPatterns,
          patternRelationships = patternRelationships ++ otherPatternRelationships,
          optionalMatches = optionalMatches ++ otherOptionalMatches,
          argumentIds = argumentIds ++ otherArgumentIds,
          hints = hints ++ otherHints,
          shortestPathPatterns = shortestPathPatterns ++ otherShortestPathPatterns,
          mutatingPatterns = mutatingPatterns ++ otherMutatingPatterns
        )
    }
  }

  def hasOptionalPatterns: Boolean = optionalMatches.nonEmpty

  def patternNodeLabels: Map[String, Set[LabelName]] = {
    // Node label predicates are extracted from the pattern nodes to predicates in LabelPredicateNormalizer.
    // Therefore, we only need to look in selections.
    patternNodes.collect { case node: String => node -> selections.labelsOnNode(node) }.toMap
  }

  def patternRelationshipTypes: Map[String, RelTypeName] = {
    // Pattern relationship type predicates are inlined in PlannerQueryBuilder::inlineRelationshipTypePredicates().
    // Therefore, we don't need to look at predicates in selections.
    patternRelationships.collect { case PatternRelationship(name, _, _, Seq(relType), _) => name -> relType }.toMap
  }

  /**
   * Returns the connected patterns of this query graph where each connected pattern is represented by a QG.
   * Connected here means can be reached through a relationship pattern.
   * Does not include optional matches, shortest paths or predicates that have dependencies across multiple of the
   * connected query graphs.
   */
  def connectedComponents: Seq[QueryGraph] = {
    val visited = mutable.Set.empty[String]

    val (predicatesWithLocalDependencies, strayPredicates) = selections.predicates.partition {
      p => (p.dependencies -- argumentIds).nonEmpty
    }

    def createComponentQueryGraphStartingFrom(patternNode: String) = {
      val qg = connectedComponentFor(patternNode, visited)
      val coveredIds = qg.idsWithoutOptionalMatchesOrUpdates
      val shortestPaths = shortestPathPatterns.filter {
        p => coveredIds.contains(p.rel.nodes._1) && coveredIds.contains(p.rel.nodes._2)
      }
      val shortestPathIds = shortestPaths.flatMap(p => Set(p.rel.name) ++ p.name)
      val allIds = coveredIds ++ argumentIds ++ shortestPathIds

      val predicates = predicatesWithLocalDependencies.filter(_.dependencies.subsetOf(allIds))
      val filteredHints = hints.filter(_.variables.forall(variable => coveredIds.contains(variable.name)))
      qg.withSelections(Selections(predicates))
        .withArgumentIds(argumentIds)
        .addHints(filteredHints)
        .addShortestPaths(shortestPaths.toIndexedSeq: _*)
    }

    /*
    We want the components that have patterns connected to arguments to be planned first, so we do not pull in arguments
    to other components by mistake
     */
    val argumentComponents = (patternNodes intersect argumentIds).toIndexedSeq.collect {
      case patternNode if !visited(patternNode) =>
        createComponentQueryGraphStartingFrom(patternNode)
    }

    val rest = patternNodes.toIndexedSeq.collect {
      case patternNode if !visited(patternNode) =>
        createComponentQueryGraphStartingFrom(patternNode)
    }

    (argumentComponents ++ rest) match {
      case first +: rest =>
        first.addPredicates(strayPredicates) +: rest
      case x => x
    }
  }

  def withRemovedPatternRelationships(patterns: Set[PatternRelationship]): QueryGraph =
    copy(patternRelationships = patternRelationships -- patterns)

  def joinHints: Set[UsingJoinHint] =
    hints.collect { case hint: UsingJoinHint => hint }

  private def connectedComponentFor(startNode: String, visited: mutable.Set[String]): QueryGraph = {
    val queue = mutable.Queue(startNode)
    var connectedComponent = QueryGraph.empty
    while (queue.nonEmpty) {
      val node = queue.dequeue()
      if (!visited(node)) {
        visited += node

        val (
          patternRelationshipsInConnectedComponent: Set[PatternRelationship],
          quantifiedPathPatternsInConnectedComponent: Set[QuantifiedPathPattern],
          nodes: Set[String]
        ) = findConnectedEntities(node, connectedComponent)

        queue.enqueueAll(nodes)

        connectedComponent = connectedComponent
          .addPatternNodes(node)
          .addPatternRelationships(patternRelationshipsInConnectedComponent)
          .addQuantifiedPathPatterns(quantifiedPathPatternsInConnectedComponent)

        val alreadyHaveArguments = connectedComponent.argumentIds.nonEmpty

        if (
          !alreadyHaveArguments && (argumentsOverLapsWith(
            connectedComponent.idsWithoutOptionalMatchesOrUpdates
          ) || predicatePullsInArguments(node))
        ) {
          connectedComponent = connectedComponent.withArgumentIds(argumentIds)
          val nodesSolvedByArguments = patternNodes intersect connectedComponent.argumentIds
          queue.enqueueAll(nodesSolvedByArguments.toIndexedSeq)
        }
      }
    }
    connectedComponent
  }

  private def findConnectedEntities(
    node: String,
    connectedComponent: QueryGraph
  ): (Set[PatternRelationship], Set[QuantifiedPathPattern], Set[String]) = {

    val filteredPatterns = patternRelationships.filter { rel =>
      rel.coveredNodeIds.contains(node) && !connectedComponent.patternRelationships.contains(rel)
    }
    val filteredQPPatterns = quantifiedPathPatterns.filter { rel =>
      rel.coveredNodeIds.contains(node) && !connectedComponent.quantifiedPathPatterns.contains(rel)
    }

    val patternsWithSameName =
      patternRelationships.filterNot(filteredPatterns).filter { r => filteredPatterns.exists(_.name == r.name) }

    val filteredPatternNodes = filteredPatterns.map(_.otherSide(node))
    val filteredQPPatternNodes = filteredQPPatterns.map(_.otherSide(node))

    val patternsWithSameNameNodes = patternsWithSameName.flatMap(r => Seq(r.left, r.right))

    (
      filteredPatterns ++ patternsWithSameName,
      filteredQPPatterns,
      filteredPatternNodes ++ filteredQPPatternNodes ++ patternsWithSameNameNodes
    )
  }

  private def argumentsOverLapsWith(coveredIds: Set[String]) = (argumentIds intersect coveredIds).nonEmpty

  private def predicatePullsInArguments(node: String) = selections.flatPredicates.exists { p =>
    val dependencies = p.dependencies.map(_.name)
    dependencies(node) && (dependencies intersect argumentIds).nonEmpty
  }

  def containsReads: Boolean = {
    (patternNodes.nonEmpty && (patternNodes -- argumentIds).nonEmpty) ||
    patternRelationships.nonEmpty ||
    quantifiedPathPatterns.nonEmpty ||
    selections.nonEmpty ||
    shortestPathPatterns.nonEmpty ||
    optionalMatches.nonEmpty ||
    containsMergeRecursive ||
    containsPropertyReadsInUpdates
  }

  def writeOnly: Boolean = !containsReads && containsUpdates

  def addMutatingPatterns(pattern: MutatingPattern): QueryGraph = {
    val copyPatterns = new mutable.ArrayBuffer[MutatingPattern](mutatingPatterns.size + 1)
    copyPatterns.appendAll(mutatingPatterns)
    copyPatterns += pattern

    copy(mutatingPatterns = copyPatterns.toIndexedSeq)
  }

  def addMutatingPatterns(patterns: Seq[MutatingPattern]): QueryGraph = {
    val copyPatterns = new ArrayBuffer[MutatingPattern](patterns.size)
    copyPatterns.appendAll(mutatingPatterns)
    copyPatterns.appendAll(patterns)
    copy(mutatingPatterns = copyPatterns.toIndexedSeq)
  }

  def standaloneArgumentPatternNodes: Set[String] = {
    patternNodes
      .intersect(argumentIds)
      .diff(patternRelationships.flatMap(_.coveredIds))
      .diff(shortestPathPatterns.flatMap(_.rel.coveredIds))
      .diff(quantifiedPathPatterns.flatMap(_.coveredNodeIds))
  }

  override def toString: String = {
    var added = false
    val builder = new StringBuilder("QueryGraph {")
    val stringifier = ExpressionStringifier(
      extension = new ExpressionStringifier.Extension {
        override def apply(ctx: ExpressionStringifier)(expression: Expression): String = expression match {
          case pp: PartialPredicate[_] => s"partial(${ctx(pp.coveredPredicate)}, ${ctx(pp.coveringPredicate)})"
          case e                       => e.asCanonicalStringVal
        }
      },
      alwaysParens = false,
      alwaysBacktick = false,
      preferSingleQuotes = false,
      sensitiveParamsAsParams = false
    )

    def addSetIfNonEmptyS(s: Iterable[String], name: String): Unit = addSetIfNonEmpty(s, name, (x: String) => x)
    def addSetIfNonEmpty[T](s: Iterable[T], name: String, f: T => String): Unit = {
      if (s.nonEmpty) {
        if (added)
          builder.append(", ")
        else
          added = true

        val sortedInput = if (s.isInstanceOf[Set[_]]) s.map(x => f(x)).toSeq.sorted else s.map(f)
        builder.append(s"$name: ").append(sortedInput.mkString("['", "', '", "']"))
      }
    }

    addSetIfNonEmptyS(patternNodes, "Nodes")
    addSetIfNonEmpty(patternRelationships, "Rels", (_: PatternRelationship).toString)
    addSetIfNonEmpty(quantifiedPathPatterns, "Quantified path patterns", (_: QuantifiedPathPattern).toString)
    addSetIfNonEmptyS(argumentIds, "Arguments")
    addSetIfNonEmpty(selections.flatPredicates, "Predicates", (e: Expression) => stringifier.apply(e))
    addSetIfNonEmpty(shortestPathPatterns, "Shortest paths", (_: ShortestPathPattern).toString)
    addSetIfNonEmpty(optionalMatches, "Optional Matches: ", (_: QueryGraph).toString)
    addSetIfNonEmpty(hints, "Hints", (_: Hint).toString)
    addSetIfNonEmpty(mutatingPatterns, "MutatingPatterns", (_: MutatingPattern).toString)

    builder.append("}")
    builder.toString()
  }

  /**
   * We have to do this special treatment of QG to avoid problems when checking that the produced plan actually
   * solves what we set out to solve. In some rare circumstances, we'll get a few optional matches that are independent of each other.
   *
   * Given the way our planner works, it can unpredictably plan these optional matches in different orders, which leads to an exception being thrown when
   * checking that the correct query has been solved.
   */
  override def equals(in: Any): Boolean = {
    in match {
      case other @ QueryGraph(
          otherPatternRelationships,
          otherQuantifiedPathPatterns,
          otherPatternNodes,
          otherArgumentIds,
          otherSelections,
          otherOptionalMatches,
          otherHints,
          otherShortestPathPatterns,
          otherMutatingPatterns
        ) =>
        if (this eq other) {
          true
        } else {
          patternRelationships == otherPatternRelationships &&
          quantifiedPathPatterns == otherQuantifiedPathPatterns &&
          patternNodes == otherPatternNodes &&
          argumentIds == otherArgumentIds &&
          selections == otherSelections &&
          optionalMatches.toSet == otherOptionalMatches.toSet &&
          hints == otherHints &&
          shortestPathPatterns == otherShortestPathPatterns &&
          mutatingPatterns == otherMutatingPatterns
        }
      case _ =>
        false
    }
  }

  override lazy val hashCode: Int = this match {
    // The point of this "useless" match case is to catch your attention if you modified the fields of the QueryGraph.
    // Please remember to update the hash code.
    case QueryGraph(_, _, _, _, _, _, _, _, _) =>
      ScalaRunTime._hashCode((
        patternRelationships,
        quantifiedPathPatterns,
        patternNodes,
        argumentIds,
        selections,
        optionalMatches.toSet,
        hints.groupBy(identity),
        shortestPathPatterns,
        mutatingPatterns
      ))
  }

}

object QueryGraph {
  def empty: QueryGraph = QueryGraph()

  implicit object byCoveredIds extends Ordering[QueryGraph] {

    def compare(x: QueryGraph, y: QueryGraph): Int = {
      val xs = x.idsWithoutOptionalMatchesOrUpdates.toIndexedSeq.sorted
      val ys = y.idsWithoutOptionalMatchesOrUpdates.toIndexedSeq.sorted
      Implicits.seqOrdering[Seq, String].compare(xs, ys)
    }
  }

}

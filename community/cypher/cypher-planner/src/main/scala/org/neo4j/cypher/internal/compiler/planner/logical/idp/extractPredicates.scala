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

import org.neo4j.cypher.internal.expressions.AllIterablePredicate
import org.neo4j.cypher.internal.expressions.Expression
import org.neo4j.cypher.internal.expressions.FilterScope
import org.neo4j.cypher.internal.expressions.FunctionInvocation
import org.neo4j.cypher.internal.expressions.FunctionName
import org.neo4j.cypher.internal.expressions.LogicalVariable
import org.neo4j.cypher.internal.expressions.MultiRelationshipPathStep
import org.neo4j.cypher.internal.expressions.NilPathStep
import org.neo4j.cypher.internal.expressions.NodePathStep
import org.neo4j.cypher.internal.expressions.NoneIterablePredicate
import org.neo4j.cypher.internal.expressions.Not
import org.neo4j.cypher.internal.expressions.PathExpression
import org.neo4j.cypher.internal.expressions.Unique
import org.neo4j.cypher.internal.expressions.VarLengthLowerBound
import org.neo4j.cypher.internal.expressions.VarLengthUpperBound
import org.neo4j.cypher.internal.expressions.Variable
import org.neo4j.cypher.internal.ir.VarPatternLength
import org.neo4j.cypher.internal.logical.plans.VariablePredicate

import scala.collection.immutable.ListSet

object extractPredicates {

  // Using type predicates to make this more readable.
  type NodePredicates = ListSet[VariablePredicate]
  type RelationshipPredicates = ListSet[VariablePredicate]
  type SolvedPredicates = ListSet[Expression] // for marking predicates as solved

  def apply(
    availablePredicates: collection.Seq[Expression],
    originalRelationshipName: String,
    originalNodeName: String,
    targetNodeName: String,
    targetNodeIsBound: Boolean,
    maybeVarLength: Option[VarPatternLength] = None
  ): (NodePredicates, RelationshipPredicates, SolvedPredicates) = {

    /*
    We extract predicates that we can evaluate eagerly during the traversal, which allows us to abort traversing
    down paths that would not match. To make it easy to evaluate these predicates, we rewrite them a little bit so
    a single slot can be used for all predicates against a relationship (similarly done for nodes)

    During the folding, we also accumulate the original predicate, which we can mark as solved by this plan.
     */
    val seed: (NodePredicates, RelationshipPredicates, SolvedPredicates) =
      (ListSet.empty, ListSet.empty, ListSet.empty)

    /**
     * Checks if an inner predicate depends on the path (i.e. the relationship or end node). In that case
     * we cannot solve the predicates during the traversal. Unless the target node is bound.
     */
    def pathDependent(innerPredicate: Expression) = {
      val names = innerPredicate.dependencies.map(_.name)
      names.contains(originalRelationshipName) || (names.contains(targetNodeName) && !targetNodeIsBound)
    }

    availablePredicates.foldLeft(seed) {

      // MATCH ()-[r* {prop:1337}]->()
      case ((n, e, s), p @ AllRelationships(variable, `originalRelationshipName`, innerPredicate))
        if targetNodeIsBound || !innerPredicate.dependencies.exists(_.name == targetNodeName) =>
        val predicate = VariablePredicate(variable, innerPredicate)
        (n, e + predicate, s + p)

      // MATCH (a)-[rs*]-(b) WHERE NONE(r IN rs WHERE r.prop=4)
      case ((n, e, s), p @ NoRelationships(variable, `originalRelationshipName`, innerPredicate))
        if targetNodeIsBound || !innerPredicate.dependencies.exists(_.name == targetNodeName) =>
        val predicate = VariablePredicate(variable, Not(innerPredicate)(innerPredicate.position))
        (n, e + predicate, s + p)

      // MATCH p = (a)-[x*]->(b) WHERE ALL(r in relationships(p) WHERE r.prop > 5)
      case (
          (n, e, s),
          p @ AllRelationshipsInPath(`originalNodeName`, `originalRelationshipName`, variable, innerPredicate)
        ) if !pathDependent(innerPredicate) =>
        val predicate = VariablePredicate(variable, innerPredicate)
        (n, e + predicate, s + p)

      // MATCH p = ()-[*]->() WHERE NONE(r in relationships(p) WHERE <innerPredicate>)
      case (
          (n, e, s),
          p @ NoRelationshipInPath(`originalNodeName`, `originalRelationshipName`, variable, innerPredicate)
        ) if !pathDependent(innerPredicate) =>
        val predicate = VariablePredicate(variable, Not(innerPredicate)(innerPredicate.position))
        (n, e + predicate, s + p)

      // MATCH p = ()-[*]->() WHERE ALL(r in nodes(p) WHERE <innerPredicate>)
      case ((n, e, s), p @ AllNodesInPath(`originalNodeName`, `originalRelationshipName`, variable, innerPredicate))
        if !pathDependent(innerPredicate) =>
        val predicate = VariablePredicate(variable, innerPredicate)
        (n + predicate, e, s + p)

      // MATCH p = ()-[*]->() WHERE NONE(r in nodes(p) WHERE <innerPredicate>)
      case ((n, e, s), p @ NoNodeInPath(`originalNodeName`, `originalRelationshipName`, variable, innerPredicate))
        if !pathDependent(innerPredicate) =>
        val predicate = VariablePredicate(variable, Not(innerPredicate)(innerPredicate.position))
        (n + predicate, e, s + p)

      // Inserted by AddUniquenessPredicates
      case ((n, e, s), p @ Unique(Variable(`originalRelationshipName`))) =>
        (n, e, s + p)

      // Inserted by AddVarLengthPredicates. We solve these predicates, iff the var-length expand we are about to plan is more restrictive
      case (
          (n, e, s),
          p @ VarLengthLowerBound(
            Variable(`originalRelationshipName`),
            predicateLowerBound
          )
        ) if predicateLowerBound <= maybeVarLength.map(_.min).getOrElse(Int.MinValue) =>
        (n, e, s + p)
      case (
          (n, e, s),
          p @ VarLengthUpperBound(
            Variable(`originalRelationshipName`),
            predicateUpperBound
          )
        ) if predicateUpperBound >= maybeVarLength.flatMap(_.max).getOrElse(Int.MaxValue) =>
        (n, e, s + p)

      case (acc, _) =>
        acc
    }
  }

  object AllRelationships {

    def unapply(v: Any): Option[(LogicalVariable, String, Expression)] =
      v match {
        case AllIterablePredicate(FilterScope(variable, Some(innerPredicate)), relId @ LogicalVariable(name))
          if variable == relId || !innerPredicate.dependencies(relId) =>
          Some((variable, name, innerPredicate))

        case _ => None
      }
  }

  object NoRelationships {

    def unapply(v: Any): Option[(LogicalVariable, String, Expression)] =
      v match {
        case NoneIterablePredicate(FilterScope(variable, Some(innerPredicate)), relId @ LogicalVariable(name))
          if variable == relId || !innerPredicate.dependencies(relId) =>
          Some((variable, name, innerPredicate))

        case _ => None
      }
  }

  object AllRelationshipsInPath {

    def unapply(v: Any): Option[(String, String, LogicalVariable, Expression)] =
      v match {
        case AllIterablePredicate(
            FilterScope(variable, Some(innerPredicate)),
            FunctionInvocation(
              _, // namespace
              FunctionName(fname),
              false, // distinct
              Seq(
                PathExpression(
                  NodePathStep(
                    startNode: LogicalVariable,
                    MultiRelationshipPathStep(rel: LogicalVariable, _, _, NilPathStep())
                  )
                )
              )
            )
          ) if fname == "relationships" =>
          Some((startNode.name, rel.name, variable, innerPredicate))

        case _ => None
      }
  }

  object AllNodesInPath {

    def unapply(v: Any): Option[(String, String, LogicalVariable, Expression)] =
      v match {
        case AllIterablePredicate(
            FilterScope(variable, Some(innerPredicate)),
            FunctionInvocation(
              _,
              FunctionName(fname),
              false,
              Seq(
                PathExpression(
                  NodePathStep(
                    startNode: LogicalVariable,
                    MultiRelationshipPathStep(rel: LogicalVariable, _, _, NilPathStep())
                  )
                )
              )
            )
          ) if fname == "nodes" =>
          Some((startNode.name, rel.name, variable, innerPredicate))

        case _ => None
      }
  }

  object NoRelationshipInPath {

    def unapply(v: Any): Option[(String, String, LogicalVariable, Expression)] =
      v match {
        case NoneIterablePredicate(
            FilterScope(variable, Some(innerPredicate)),
            FunctionInvocation(
              _,
              FunctionName(fname),
              false,
              Seq(
                PathExpression(
                  NodePathStep(
                    startNode: LogicalVariable,
                    MultiRelationshipPathStep(rel: LogicalVariable, _, _, NilPathStep())
                  )
                )
              )
            )
          ) if fname == "relationships" =>
          Some((startNode.name, rel.name, variable, innerPredicate))

        case _ => None
      }
  }

  object NoNodeInPath {

    def unapply(v: Any): Option[(String, String, LogicalVariable, Expression)] =
      v match {
        case NoneIterablePredicate(
            FilterScope(variable, Some(innerPredicate)),
            FunctionInvocation(
              _,
              FunctionName(fname),
              false,
              Seq(
                PathExpression(
                  NodePathStep(
                    startNode: LogicalVariable,
                    MultiRelationshipPathStep(rel: LogicalVariable, _, _, NilPathStep())
                  )
                )
              )
            )
          ) if fname == "nodes" =>
          Some((startNode.name, rel.name, variable, innerPredicate))

        case _ => None
      }
  }

}

object extractShortestPathPredicates {

  import extractPredicates.NodePredicates
  import extractPredicates.RelationshipPredicates
  import extractPredicates.SolvedPredicates

  def apply(
    availablePredicates: Set[Expression],
    pathName: Option[String],
    relsName: Option[String]
  ): (NodePredicates, RelationshipPredicates, SolvedPredicates) = {

    /*
    We extract predicates that we can evaluate eagerly during the traversal, which allows us to abort traversing
    down paths that would not match. To make it easy to evaluate these predicates, we rewrite them a little bit so
    a single slot can be used for all predicates against a relationship (similarly done for nodes)

    During the folding, we also accumulate the original predicate, which we can mark as solved by this plan.
     */
    val seed: (NodePredicates, RelationshipPredicates, SolvedPredicates) =
      (ListSet.empty, ListSet.empty, ListSet.empty)

    /**
     * Checks if an inner predicate depends on the path (i.e. the relationship or end node). In that case
     * we cannot solve the predicates during the traversal.
     */
    def pathDependent(innerPredicate: Expression) = {
      val names = innerPredicate.dependencies.map(_.name)
      names.intersect(pathName.toSet ++ relsName.toSet).nonEmpty
    }

    availablePredicates.foldLeft(seed) {
      // MATCH p=shortestPath((a)-[rs*]-(b)) WHERE all(r IN rs WHERE r.prop = 2)
      case ((n, e, s), p @ AllRelationshipsUnnamedPath(variable, `relsName`, innerPredicate)) =>
        val predicate = VariablePredicate(variable, innerPredicate)
        (n, e + predicate, s + p)

      // MATCH p=shortestPath((a)-[rs*]-(b)) WHERE NONE(r IN rs WHERE r.prop = 2)
      case ((n, e, s), p @ NoRelationshipsUnnamedPath(variable, `relsName`, innerPredicate)) =>
        val predicate = VariablePredicate(variable, Not(innerPredicate)(innerPredicate.position))
        (n, e + predicate, s + p)

      // MATCH p = shortestPath((a)-[x*]->(b)) WHERE ALL(r in relationships(p) WHERE r.prop > 5)
      case (
          (n, e, s),
          p @ AllRelationshipsInNamedPath(`pathName`, variable, innerPredicate)
        ) if !pathDependent(innerPredicate) =>
        val predicate = VariablePredicate(variable, innerPredicate)
        (n, e + predicate, s + p)

      // MATCH p = shortestPath((a)-[*]->(b)) WHERE NONE(r in relationships(p) WHERE <innerPredicate>)
      case (
          (n, e, s),
          p @ NoRelationshipInNamedPath(`pathName`, variable, innerPredicate)
        ) if !pathDependent(innerPredicate) =>
        val predicate = VariablePredicate(variable, Not(innerPredicate)(innerPredicate.position))
        (n, e + predicate, s + p)

      // MATCH p = shortestPath((a)-[*]->(b)) WHERE ALL(r in nodes(p) WHERE <innerPredicate>)
      case ((n, e, s), p @ AllNodesInNamedPath(`pathName`, variable, innerPredicate))
        if !pathDependent(innerPredicate) =>
        val predicate = VariablePredicate(variable, innerPredicate)
        (n + predicate, e, s + p)

      // MATCH p = shortestPath((a)-[*]->(b)) WHERE NONE(r in nodes(p) WHERE <innerPredicate>)
      case ((n, e, s), p @ NoNodeInNamedPath(`pathName`, variable, innerPredicate)) if !pathDependent(innerPredicate) =>
        val predicate = VariablePredicate(variable, Not(innerPredicate)(innerPredicate.position))
        (n + predicate, e, s + p)

      case (acc, _) =>
        acc
    }
  }

  object AllRelationshipsUnnamedPath {

    def unapply(v: Any): Option[(LogicalVariable, Option[String], Expression)] =
      v match {
        case AllIterablePredicate(FilterScope(variable, Some(innerPredicate)), relId @ LogicalVariable(name))
          if variable == relId || !innerPredicate.dependencies(relId) =>
          Some((variable, Some(name), innerPredicate))

        case _ => None
      }
  }

  object NoRelationshipsUnnamedPath {

    def unapply(v: Any): Option[(LogicalVariable, Option[String], Expression)] =
      v match {
        case NoneIterablePredicate(FilterScope(variable, Some(innerPredicate)), relId @ LogicalVariable(name))
          if variable == relId || !innerPredicate.dependencies(relId) =>
          Some((variable, Some(name), innerPredicate))

        case _ => None
      }
  }

  object AllRelationshipsInNamedPath {

    def unapply(v: Any): Option[(Option[String], LogicalVariable, Expression)] =
      v match {
        case AllIterablePredicate(
            FilterScope(variable, Some(innerPredicate)),
            FunctionInvocation(
              _, // namespace
              FunctionName(fname),
              false, // distinct
              Seq(Variable(pathName))
            )
          ) if fname == "relationships" =>
          Some((Some(pathName), variable, innerPredicate))

        case _ => None
      }
  }

  object AllNodesInNamedPath {

    def unapply(v: Any): Option[(Option[String], LogicalVariable, Expression)] =
      v match {
        case AllIterablePredicate(
            FilterScope(variable, Some(innerPredicate)),
            FunctionInvocation(
              _,
              FunctionName(fname),
              false,
              Seq(Variable(pathName))
            )
          ) if fname == "nodes" =>
          Some((Some(pathName), variable, innerPredicate))

        case _ => None
      }
  }

  object NoRelationshipInNamedPath {

    def unapply(v: Any): Option[(Option[String], LogicalVariable, Expression)] =
      v match {
        case NoneIterablePredicate(
            FilterScope(variable, Some(innerPredicate)),
            FunctionInvocation(
              _,
              FunctionName(fname),
              false,
              Seq(Variable(pathName))
            )
          ) if fname == "relationships" =>
          Some((Some(pathName), variable, innerPredicate))

        case _ => None
      }
  }

  object NoNodeInNamedPath {

    def unapply(v: Any): Option[(Option[String], LogicalVariable, Expression)] =
      v match {
        case NoneIterablePredicate(
            FilterScope(variable, Some(innerPredicate)),
            FunctionInvocation(
              _,
              FunctionName(fname),
              false,
              Seq(Variable(pathName))
            )
          ) if fname == "nodes" =>
          Some((Some(pathName), variable, innerPredicate))

        case _ => None
      }
  }

}

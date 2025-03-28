/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.neo4j.cypher.internal.rewriting.rewriters

import org.neo4j.cypher.internal.ast.Match
import org.neo4j.cypher.internal.ast.Where
import org.neo4j.cypher.internal.expressions
import org.neo4j.cypher.internal.expressions.Expression
import org.neo4j.cypher.internal.expressions.Pattern
import org.neo4j.cypher.internal.expressions.Range
import org.neo4j.cypher.internal.expressions.RelationshipChain
import org.neo4j.cypher.internal.expressions.RelationshipPattern
import org.neo4j.cypher.internal.expressions.ScopeExpression
import org.neo4j.cypher.internal.expressions.ShortestPaths
import org.neo4j.cypher.internal.expressions.VarLengthLowerBound
import org.neo4j.cypher.internal.expressions.VarLengthUpperBound
import org.neo4j.cypher.internal.expressions.Variable
import org.neo4j.cypher.internal.util.ASTNode
import org.neo4j.cypher.internal.util.Foldable.SkipChildren
import org.neo4j.cypher.internal.util.Foldable.TraverseChildren
import org.neo4j.cypher.internal.util.InputPosition
import org.neo4j.cypher.internal.util.Rewriter
import org.neo4j.cypher.internal.util.StepSequencer
import org.neo4j.cypher.internal.util.bottomUp

case object RelationshipVarLengthPredicatesInMatchAndMerge extends StepSequencer.Condition

case object AddVarLengthPredicates extends AddRelationshipPredicates {

  override val rewriter: Rewriter = bottomUp(Rewriter.lift {
    case matchClause @ Match(_, pattern: Pattern, _, where) =>
      val relationships = collectVarLengthRelationships(pattern)
      val newWhere = withPredicates(matchClause, relationships, where)
      matchClause.copy(where = newWhere)(matchClause.position)
  })

  def collectVarLengthRelationships(pattern: ASTNode): Seq[RelationshipPattern] =
    pattern.folder.treeFold(Seq.empty[RelationshipPattern]) {
      case _: ScopeExpression =>
        acc => SkipChildren(acc)

      case _: ShortestPaths =>
        acc => SkipChildren(acc)

      case RelationshipChain(_, rel @ RelationshipPattern(_, _, Some(_), _, _, _), _) =>
        acc => TraverseChildren(acc :+ rel)
    }

  protected def withPredicates(
    pattern: ASTNode,
    rels: Seq[RelationshipPattern],
    where: Option[Where]
  ): Option[Where] = {
    val pos = pattern.position
    val maybePredicate: Option[Expression] = createPredicateFor(rels, pos)
    addPredicateToWhere(where, pos, maybePredicate)
  }

  def createPredicateFor(relationships: Seq[RelationshipPattern], pos: InputPosition): Option[Expression] = {
    createPredicatesFor(relationships, pos).reduceOption(expressions.And(_, _)(pos))
  }

  override def postConditions: Set[StepSequencer.Condition] =
    Set(RelationshipVarLengthPredicatesInMatchAndMerge)

  def createPredicatesFor(relationships: Seq[RelationshipPattern], pos: InputPosition): Seq[Expression] =
    relationships.flatMap {
      case RelationshipPattern(Some(relName), _, Some(None), _, _, _) =>
        createSizePredicatesForVarLengthRelationship(relName.name, 1, None, pos)
      case RelationshipPattern(Some(relName), _, Some(Some(Range(lowerLiteral, upperLiteral))), _, _, _) =>
        val lowerValue = lowerLiteral.map(_.value.longValue()).getOrElse(1L)
        val maybeUpperValue = upperLiteral.map(_.value.longValue())
        createSizePredicatesForVarLengthRelationship(relName.name, lowerValue, maybeUpperValue, pos)
      case e => throw new IllegalStateException(s"Did expect named var-length relationship. Got: $e")
    }

  private def createSizePredicatesForVarLengthRelationship(
    relName: String,
    lowerBoundValue: Long,
    maybeUpperBoundValue: Option[Long],
    pos: InputPosition
  ): Seq[Expression] = {
    def relNameVar = Variable(relName)(pos)
    val lowerBound = VarLengthLowerBound(relNameVar, lowerBoundValue)(pos)
    val upperBound = maybeUpperBoundValue.map { max =>
      VarLengthUpperBound(relNameVar, max)(pos)
    }
    Seq(lowerBound) ++ upperBound
  }
}

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
package org.neo4j.cypher.internal.compiler.planner.logical.cardinality

import org.neo4j.cypher.internal.ast.semantics.SemanticTable
import org.neo4j.cypher.internal.compiler.planner.logical.Metrics.CardinalityModel
import org.neo4j.cypher.internal.compiler.planner.logical.Metrics.LabelInfo
import org.neo4j.cypher.internal.compiler.planner.logical.Metrics.RelTypeInfo
import org.neo4j.cypher.internal.compiler.planner.logical.SimpleMetricsFactory
import org.neo4j.cypher.internal.compiler.planner.logical.simpleExpressionEvaluator
import org.neo4j.cypher.internal.compiler.planner.logical.steps.index.IndexCompatiblePredicatesProviderContext
import org.neo4j.cypher.internal.expressions.BooleanExpression
import org.neo4j.cypher.internal.expressions.Expression
import org.neo4j.cypher.internal.ir.Selections
import org.neo4j.cypher.internal.planner.spi.GraphStatistics
import org.neo4j.cypher.internal.planner.spi.IndexDescriptor
import org.neo4j.cypher.internal.planner.spi.IndexDescriptor.IndexType
import org.neo4j.cypher.internal.util.Selectivity

/**
 * Test that CompositeExpressionSelectivityCalculator returns the same results as ExpressionSelectivityCalculator for single expressions.
 */
abstract class CompositeExpressionSelectivityCalculatorWithSingleExpressionsTest
    extends ExpressionSelectivityCalculatorTest {

  override protected def setUpCalculator(
    labelInfo: LabelInfo,
    relTypeInfo: RelTypeInfo,
    stats: GraphStatistics,
    semanticTable: SemanticTable
  ): Expression => Selectivity = {
    val planContext = mockPlanContext(stats)
    val compositeCalculator = CompositeExpressionSelectivityCalculator(planContext)
    val cardinalityModel: CardinalityModel = SimpleMetricsFactory.newCardinalityEstimator(
      SimpleMetricsFactory.newQueryGraphCardinalityModel(
        planContext,
        compositeCalculator
      ),
      compositeCalculator,
      simpleExpressionEvaluator
    )
    exp: Expression => {
      compositeCalculator(
        Selections.from(exp),
        labelInfo,
        relTypeInfo,
        semanticTable,
        IndexCompatiblePredicatesProviderContext.default,
        cardinalityModel
      )
    }
  }
}

class RangeCompositeExpressionSelectivityCalculatorWithSingleExpressionsTest
    extends CompositeExpressionSelectivityCalculatorWithSingleExpressionsTest {
  override def getIndexType: IndexDescriptor.IndexType = IndexType.Range

  override val substringPredicatesWithClues: Seq[((Expression, Expression) => BooleanExpression, String)] =
    Seq(startsWith _, contains _, endsWith _)
      .map(mkExpr => (mkExpr, mkExpr(null, null).getClass.getSimpleName))
}

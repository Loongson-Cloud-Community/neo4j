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
package org.neo4j.cypher.internal.logical.plans

import org.neo4j.cypher.internal.util.attribution.IdGen

/**
 * For every source row, call the procedure 'call'.
 *
 *   If the procedure returns a stream, produce one row per result in this stream with result appended to the row
 *   If the procedure returns void, produce the source row
 */
case class ProcedureCall(override val source: LogicalPlan, call: ResolvedCall)(implicit idGen: IdGen)
    extends LogicalUnaryPlan(idGen) {

  override def withLhs(newLHS: LogicalPlan)(idGen: IdGen): LogicalUnaryPlan = copy(source = newLHS)(idGen)

  override val availableSymbols: Set[String] =
    source.availableSymbols ++ call.callResults.map { result => result.variable.name }
}

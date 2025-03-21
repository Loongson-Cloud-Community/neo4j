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
package org.neo4j.cypher.internal.util

/**
 * Describes a notification
 */
trait InternalNotification

case class CartesianProductNotification(position: InputPosition, isolatedVariables: Set[String])
    extends InternalNotification

case class UnboundedShortestPathNotification(position: InputPosition) extends InternalNotification

case class DeprecatedFunctionNotification(position: InputPosition, oldName: String, newName: String)
    extends InternalNotification

case class DeprecatedRelTypeSeparatorNotification(position: InputPosition, rewrittenExpression: String)
    extends InternalNotification

case class DeprecatedNodesOrRelationshipsInSetClauseNotification(position: InputPosition) extends InternalNotification

case class SubqueryVariableShadowing(position: InputPosition, varName: String) extends InternalNotification

case class UnionReturnItemsInDifferentOrder(position: InputPosition) extends InternalNotification

case class HomeDatabaseNotPresent(databaseName: String) extends InternalNotification

case class FixedLengthRelationshipInShortestPath(position: InputPosition) extends InternalNotification

case class DeprecatedDatabaseNameNotification(databaseName: String, position: Option[InputPosition])
    extends InternalNotification

case class DeprecatedRuntimeNotification(msg: String)
    extends InternalNotification

case class DeprecatedTextIndexProvider(position: InputPosition) extends InternalNotification

case class UnsatisfiableRelationshipTypeExpression(position: InputPosition, labelExpression: String)
    extends InternalNotification

case class RepeatedRelationshipReference(position: InputPosition, relName: String) extends InternalNotification

case class RepeatedVarLengthRelationshipReference(position: InputPosition, relName: String) extends InternalNotification

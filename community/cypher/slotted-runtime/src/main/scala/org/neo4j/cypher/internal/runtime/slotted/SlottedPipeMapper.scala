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
package org.neo4j.cypher.internal.runtime.slotted

import org.neo4j.cypher.internal
import org.neo4j.cypher.internal.ast.semantics.SemanticTable
import org.neo4j.cypher.internal.expressions.Equals
import org.neo4j.cypher.internal.expressions.SignedDecimalIntegerLiteral
import org.neo4j.cypher.internal.ir
import org.neo4j.cypher.internal.ir.CreatePattern
import org.neo4j.cypher.internal.ir.RemoveLabelPattern
import org.neo4j.cypher.internal.ir.SetLabelPattern
import org.neo4j.cypher.internal.ir.SetNodePropertiesFromMapPattern
import org.neo4j.cypher.internal.ir.SetNodePropertiesPattern
import org.neo4j.cypher.internal.ir.SetNodePropertyPattern
import org.neo4j.cypher.internal.ir.SetPropertiesFromMapPattern
import org.neo4j.cypher.internal.ir.SetPropertiesPattern
import org.neo4j.cypher.internal.ir.SetPropertyPattern
import org.neo4j.cypher.internal.ir.SetRelationshipPropertiesFromMapPattern
import org.neo4j.cypher.internal.ir.SetRelationshipPropertiesPattern
import org.neo4j.cypher.internal.ir.SetRelationshipPropertyPattern
import org.neo4j.cypher.internal.ir.SimpleMutatingPattern
import org.neo4j.cypher.internal.ir.VarPatternLength
import org.neo4j.cypher.internal.logical.plans
import org.neo4j.cypher.internal.logical.plans.Aggregation
import org.neo4j.cypher.internal.logical.plans.AllNodesScan
import org.neo4j.cypher.internal.logical.plans.AntiConditionalApply
import org.neo4j.cypher.internal.logical.plans.Apply
import org.neo4j.cypher.internal.logical.plans.Argument
import org.neo4j.cypher.internal.logical.plans.AssertSameRelationship
import org.neo4j.cypher.internal.logical.plans.BFSPruningVarExpand
import org.neo4j.cypher.internal.logical.plans.CartesianProduct
import org.neo4j.cypher.internal.logical.plans.ConditionalApply
import org.neo4j.cypher.internal.logical.plans.Create
import org.neo4j.cypher.internal.logical.plans.DeleteExpression
import org.neo4j.cypher.internal.logical.plans.DeleteNode
import org.neo4j.cypher.internal.logical.plans.DeletePath
import org.neo4j.cypher.internal.logical.plans.DeleteRelationship
import org.neo4j.cypher.internal.logical.plans.DetachDeleteExpression
import org.neo4j.cypher.internal.logical.plans.DetachDeleteNode
import org.neo4j.cypher.internal.logical.plans.DetachDeletePath
import org.neo4j.cypher.internal.logical.plans.DirectedAllRelationshipsScan
import org.neo4j.cypher.internal.logical.plans.DirectedRelationshipIndexContainsScan
import org.neo4j.cypher.internal.logical.plans.DirectedRelationshipIndexEndsWithScan
import org.neo4j.cypher.internal.logical.plans.DirectedRelationshipIndexScan
import org.neo4j.cypher.internal.logical.plans.DirectedRelationshipIndexSeek
import org.neo4j.cypher.internal.logical.plans.DirectedRelationshipTypeScan
import org.neo4j.cypher.internal.logical.plans.DirectedRelationshipUniqueIndexSeek
import org.neo4j.cypher.internal.logical.plans.DirectedUnionRelationshipTypesScan
import org.neo4j.cypher.internal.logical.plans.Distinct
import org.neo4j.cypher.internal.logical.plans.Eager
import org.neo4j.cypher.internal.logical.plans.EmptyResult
import org.neo4j.cypher.internal.logical.plans.ErrorPlan
import org.neo4j.cypher.internal.logical.plans.ExhaustiveLimit
import org.neo4j.cypher.internal.logical.plans.Expand
import org.neo4j.cypher.internal.logical.plans.ExpandAll
import org.neo4j.cypher.internal.logical.plans.ExpandInto
import org.neo4j.cypher.internal.logical.plans.Foreach
import org.neo4j.cypher.internal.logical.plans.ForeachApply
import org.neo4j.cypher.internal.logical.plans.InjectCompilationError
import org.neo4j.cypher.internal.logical.plans.IntersectionNodeByLabelsScan
import org.neo4j.cypher.internal.logical.plans.Limit
import org.neo4j.cypher.internal.logical.plans.LoadCSV
import org.neo4j.cypher.internal.logical.plans.LogicalPlan
import org.neo4j.cypher.internal.logical.plans.Merge
import org.neo4j.cypher.internal.logical.plans.MultiNodeIndexSeek
import org.neo4j.cypher.internal.logical.plans.NodeByLabelScan
import org.neo4j.cypher.internal.logical.plans.NodeHashJoin
import org.neo4j.cypher.internal.logical.plans.NodeIndexContainsScan
import org.neo4j.cypher.internal.logical.plans.NodeIndexEndsWithScan
import org.neo4j.cypher.internal.logical.plans.NodeIndexScan
import org.neo4j.cypher.internal.logical.plans.NodeIndexSeek
import org.neo4j.cypher.internal.logical.plans.NodeUniqueIndexSeek
import org.neo4j.cypher.internal.logical.plans.NonFuseable
import org.neo4j.cypher.internal.logical.plans.Optional
import org.neo4j.cypher.internal.logical.plans.OptionalExpand
import org.neo4j.cypher.internal.logical.plans.OrderedAggregation
import org.neo4j.cypher.internal.logical.plans.OrderedDistinct
import org.neo4j.cypher.internal.logical.plans.OrderedUnion
import org.neo4j.cypher.internal.logical.plans.PartialSort
import org.neo4j.cypher.internal.logical.plans.PartialTop
import org.neo4j.cypher.internal.logical.plans.Prober
import org.neo4j.cypher.internal.logical.plans.ProduceResult
import org.neo4j.cypher.internal.logical.plans.Projection
import org.neo4j.cypher.internal.logical.plans.RemoveLabels
import org.neo4j.cypher.internal.logical.plans.RollUpApply
import org.neo4j.cypher.internal.logical.plans.SelectOrAntiSemiApply
import org.neo4j.cypher.internal.logical.plans.SelectOrSemiApply
import org.neo4j.cypher.internal.logical.plans.Selection
import org.neo4j.cypher.internal.logical.plans.SetLabels
import org.neo4j.cypher.internal.logical.plans.SetNodeProperties
import org.neo4j.cypher.internal.logical.plans.SetNodePropertiesFromMap
import org.neo4j.cypher.internal.logical.plans.SetNodeProperty
import org.neo4j.cypher.internal.logical.plans.SetProperties
import org.neo4j.cypher.internal.logical.plans.SetPropertiesFromMap
import org.neo4j.cypher.internal.logical.plans.SetProperty
import org.neo4j.cypher.internal.logical.plans.SetRelationshipProperties
import org.neo4j.cypher.internal.logical.plans.SetRelationshipPropertiesFromMap
import org.neo4j.cypher.internal.logical.plans.SetRelationshipProperty
import org.neo4j.cypher.internal.logical.plans.Skip
import org.neo4j.cypher.internal.logical.plans.Sort
import org.neo4j.cypher.internal.logical.plans.Top
import org.neo4j.cypher.internal.logical.plans.Top1WithTies
import org.neo4j.cypher.internal.logical.plans.Trail
import org.neo4j.cypher.internal.logical.plans.TransactionApply
import org.neo4j.cypher.internal.logical.plans.TransactionForeach
import org.neo4j.cypher.internal.logical.plans.UndirectedAllRelationshipsScan
import org.neo4j.cypher.internal.logical.plans.UndirectedRelationshipIndexContainsScan
import org.neo4j.cypher.internal.logical.plans.UndirectedRelationshipIndexEndsWithScan
import org.neo4j.cypher.internal.logical.plans.UndirectedRelationshipIndexScan
import org.neo4j.cypher.internal.logical.plans.UndirectedRelationshipIndexSeek
import org.neo4j.cypher.internal.logical.plans.UndirectedRelationshipTypeScan
import org.neo4j.cypher.internal.logical.plans.UndirectedRelationshipUniqueIndexSeek
import org.neo4j.cypher.internal.logical.plans.UndirectedUnionRelationshipTypesScan
import org.neo4j.cypher.internal.logical.plans.Union
import org.neo4j.cypher.internal.logical.plans.UnionNodeByLabelsScan
import org.neo4j.cypher.internal.logical.plans.UnwindCollection
import org.neo4j.cypher.internal.logical.plans.ValueHashJoin
import org.neo4j.cypher.internal.logical.plans.VarExpand
import org.neo4j.cypher.internal.macros.AssertMacros.checkOnlyWhenAssertionsAreEnabled
import org.neo4j.cypher.internal.physicalplanning.LongSlot
import org.neo4j.cypher.internal.physicalplanning.PhysicalPlan
import org.neo4j.cypher.internal.physicalplanning.RefSlot
import org.neo4j.cypher.internal.physicalplanning.Slot
import org.neo4j.cypher.internal.physicalplanning.SlotAllocation
import org.neo4j.cypher.internal.physicalplanning.SlotAllocation.LOAD_CSV_METADATA_KEY
import org.neo4j.cypher.internal.physicalplanning.SlotConfiguration
import org.neo4j.cypher.internal.physicalplanning.SlotConfiguration.ApplyPlanSlotKey
import org.neo4j.cypher.internal.physicalplanning.SlotConfiguration.CachedPropertySlotKey
import org.neo4j.cypher.internal.physicalplanning.SlotConfiguration.MetaDataSlotKey
import org.neo4j.cypher.internal.physicalplanning.SlotConfiguration.OuterNestedApplyPlanSlotKey
import org.neo4j.cypher.internal.physicalplanning.SlotConfiguration.SlotWithKeyAndAliases
import org.neo4j.cypher.internal.physicalplanning.SlotConfiguration.VariableSlotKey
import org.neo4j.cypher.internal.physicalplanning.SlotConfiguration.isRefSlotAndNotAlias
import org.neo4j.cypher.internal.physicalplanning.SlotConfigurationUtils
import org.neo4j.cypher.internal.physicalplanning.SlotConfigurationUtils.finalizeSlotConfiguration
import org.neo4j.cypher.internal.physicalplanning.SlottedIndexedProperty
import org.neo4j.cypher.internal.physicalplanning.VariablePredicates.expressionSlotForPredicate
import org.neo4j.cypher.internal.physicalplanning.ast.NodeFromSlot
import org.neo4j.cypher.internal.physicalplanning.ast.NullCheckVariable
import org.neo4j.cypher.internal.physicalplanning.ast.RelationshipFromSlot
import org.neo4j.cypher.internal.runtime.CypherRow
import org.neo4j.cypher.internal.runtime.QueryIndexRegistrator
import org.neo4j.cypher.internal.runtime.ReadableRow
import org.neo4j.cypher.internal.runtime.interpreted.GroupingExpression
import org.neo4j.cypher.internal.runtime.interpreted.commands
import org.neo4j.cypher.internal.runtime.interpreted.commands.convert.ExpressionConverters
import org.neo4j.cypher.internal.runtime.interpreted.commands.expressions.AggregationExpression
import org.neo4j.cypher.internal.runtime.interpreted.commands.expressions.DeleteOperation
import org.neo4j.cypher.internal.runtime.interpreted.commands.expressions.Expression
import org.neo4j.cypher.internal.runtime.interpreted.commands.expressions.SideEffect
import org.neo4j.cypher.internal.runtime.interpreted.pipes.EagerAggregationPipe
import org.neo4j.cypher.internal.runtime.interpreted.pipes.EmptyResultPipe
import org.neo4j.cypher.internal.runtime.interpreted.pipes.IndexSeekModeFactory
import org.neo4j.cypher.internal.runtime.interpreted.pipes.LazyLabel
import org.neo4j.cypher.internal.runtime.interpreted.pipes.LazyPropertyKey
import org.neo4j.cypher.internal.runtime.interpreted.pipes.LazyType
import org.neo4j.cypher.internal.runtime.interpreted.pipes.MergePipe
import org.neo4j.cypher.internal.runtime.interpreted.pipes.OrderedAggregationPipe
import org.neo4j.cypher.internal.runtime.interpreted.pipes.PartialSortPipe
import org.neo4j.cypher.internal.runtime.interpreted.pipes.PartialTop1Pipe
import org.neo4j.cypher.internal.runtime.interpreted.pipes.PartialTopNPipe
import org.neo4j.cypher.internal.runtime.interpreted.pipes.Pipe
import org.neo4j.cypher.internal.runtime.interpreted.pipes.PipeMapper
import org.neo4j.cypher.internal.runtime.interpreted.pipes.ProjectionPipe
import org.neo4j.cypher.internal.runtime.interpreted.pipes.QueryState
import org.neo4j.cypher.internal.runtime.interpreted.pipes.RelationshipTypes
import org.neo4j.cypher.internal.runtime.interpreted.pipes.SetPipe
import org.neo4j.cypher.internal.runtime.interpreted.pipes.SetPropertiesOperation
import org.neo4j.cypher.internal.runtime.interpreted.pipes.SetPropertyFromMapOperation
import org.neo4j.cypher.internal.runtime.interpreted.pipes.SetPropertyOperation
import org.neo4j.cypher.internal.runtime.interpreted.pipes.Top1Pipe
import org.neo4j.cypher.internal.runtime.interpreted.pipes.Top1WithTiesPipe
import org.neo4j.cypher.internal.runtime.interpreted.pipes.TopNPipe
import org.neo4j.cypher.internal.runtime.slotted
import org.neo4j.cypher.internal.runtime.slotted.SlottedPipeMapper.DistinctAllPrimitive
import org.neo4j.cypher.internal.runtime.slotted.SlottedPipeMapper.DistinctWithReferences
import org.neo4j.cypher.internal.runtime.slotted.SlottedPipeMapper.computeSlotMappings
import org.neo4j.cypher.internal.runtime.slotted.SlottedPipeMapper.createProjectionForIdentifier
import org.neo4j.cypher.internal.runtime.slotted.SlottedPipeMapper.createProjectionsForResult
import org.neo4j.cypher.internal.runtime.slotted.SlottedPipeMapper.findDistinctPhysicalOp
import org.neo4j.cypher.internal.runtime.slotted.SlottedPipeMapper.partitionGroupingExpressions
import org.neo4j.cypher.internal.runtime.slotted.SlottedPipeMapper.symbolsToSlots
import org.neo4j.cypher.internal.runtime.slotted.SlottedPipeMapper.translateColumnOrder
import org.neo4j.cypher.internal.runtime.slotted.aggregation.SlottedGroupingAggTable
import org.neo4j.cypher.internal.runtime.slotted.aggregation.SlottedNonGroupingAggTable
import org.neo4j.cypher.internal.runtime.slotted.aggregation.SlottedOrderedGroupingAggTable
import org.neo4j.cypher.internal.runtime.slotted.aggregation.SlottedOrderedNonGroupingAggTable
import org.neo4j.cypher.internal.runtime.slotted.aggregation.SlottedPrimitiveGroupingAggTable
import org.neo4j.cypher.internal.runtime.slotted.expressions.CreateSlottedNode
import org.neo4j.cypher.internal.runtime.slotted.expressions.CreateSlottedRelationship
import org.neo4j.cypher.internal.runtime.slotted.expressions.SlottedRemoveLabelsOperation
import org.neo4j.cypher.internal.runtime.slotted.pipes.AllNodesScanSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.AllOrderedDistinctSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.AllOrderedDistinctSlottedPrimitivePipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.AntiConditionalApplySlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.ApplySlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.ArgumentSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.AssertSameRelationshipSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.BFSPruningVarLengthExpandSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.CartesianProductSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.ConditionalApplySlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.CreateNodeSlottedCommand
import org.neo4j.cypher.internal.runtime.slotted.pipes.CreateRelationshipSlottedCommand
import org.neo4j.cypher.internal.runtime.slotted.pipes.CreateSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.DirectedAllRelationshipsScanSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.DirectedRelationshipIndexContainsScanSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.DirectedRelationshipIndexEndsWithScanSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.DirectedRelationshipIndexScanSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.DirectedRelationshipIndexSeekSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.DirectedRelationshipTypeScanSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.DirectedUnionRelationshipTypesScanSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.DistinctSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.DistinctSlottedPrimitivePipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.DistinctSlottedSinglePrimitivePipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.EagerSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.ExpandAllSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.ExpandIntoSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.ForeachSlottedApplyPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.ForeachSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.GroupSlot
import org.neo4j.cypher.internal.runtime.slotted.pipes.IntersectionNodesByLabelsScanSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.LoadCSVSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.LockingMergeSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.NodeHashJoinSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.NodeHashJoinSlottedPipe.KeyOffsets
import org.neo4j.cypher.internal.runtime.slotted.pipes.NodeHashJoinSlottedPipe.SlotMapping
import org.neo4j.cypher.internal.runtime.slotted.pipes.NodeHashJoinSlottedSingleNodePipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.NodeIndexContainsScanSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.NodeIndexEndsWithScanSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.NodeIndexScanSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.NodeIndexSeekSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.NodesByLabelScanSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.OptionalExpandAllSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.OptionalExpandIntoSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.OptionalSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.OrderedDistinctSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.OrderedDistinctSlottedPrimitivePipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.OrderedDistinctSlottedSinglePrimitivePipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.OrderedUnionSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.ProduceResultSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.RollUpApplySlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.SelectOrSemiApplySlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.SlottedSetLabelsOperation
import org.neo4j.cypher.internal.runtime.slotted.pipes.SlottedSetNodePropertiesOperation
import org.neo4j.cypher.internal.runtime.slotted.pipes.SlottedSetNodePropertyFromMapOperation
import org.neo4j.cypher.internal.runtime.slotted.pipes.SlottedSetNodePropertyOperation
import org.neo4j.cypher.internal.runtime.slotted.pipes.SlottedSetRelationshipPropertiesOperation
import org.neo4j.cypher.internal.runtime.slotted.pipes.SlottedSetRelationshipPropertyFromMapOperation
import org.neo4j.cypher.internal.runtime.slotted.pipes.SlottedSetRelationshipPropertyOperation
import org.neo4j.cypher.internal.runtime.slotted.pipes.SortSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.TrailSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.TransactionApplySlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.TransactionForeachSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.UndirectedAllRelationshipsScanSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.UndirectedRelationshipIndexContainsScanSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.UndirectedRelationshipIndexEndsWithScanSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.UndirectedRelationshipIndexScanSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.UndirectedRelationshipIndexSeekSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.UndirectedRelationshipTypeScanSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.UndirectedUnionRelationshipTypesScanSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.UnionNodesByLabelsScanSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.UnionSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.UnwindSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.ValueHashJoinSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.VarLengthExpandSlottedPipe
import org.neo4j.cypher.internal.runtime.slotted.pipes.VarLengthExpandSlottedPipe.SlottedVariablePredicate
import org.neo4j.cypher.internal.util.attribution.Id
import org.neo4j.cypher.internal.util.symbols.CTNode
import org.neo4j.cypher.internal.util.symbols.CTRelationship
import org.neo4j.exceptions.CantCompileQueryException
import org.neo4j.exceptions.InternalException

import scala.annotation.nowarn
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

class SlottedPipeMapper(
  fallback: PipeMapper,
  expressionConverters: ExpressionConverters,
  physicalPlan: PhysicalPlan,
  readOnly: Boolean,
  indexRegistrator: QueryIndexRegistrator
)(implicit semanticTable: SemanticTable)
    extends PipeMapper {

  override def onLeaf(plan: LogicalPlan): Pipe = {

    val id = plan.id
    val convertExpressions = (e: internal.expressions.Expression) => expressionConverters.toCommandExpression(id, e)
    val slots = physicalPlan.slotConfigurations(id)
    val argumentSize = physicalPlan.argumentSizes(id)
    finalizeSlotConfiguration(slots)

    val pipe = plan match {
      case AllNodesScan(column, _) =>
        AllNodesScanSlottedPipe(column, slots)(id)

      case NodeIndexScan(column, label, properties, _, indexOrder, indexType) =>
        NodeIndexScanSlottedPipe(
          column,
          label,
          properties.map(SlottedIndexedProperty(column, _, slots)),
          indexRegistrator.registerQueryIndex(indexType, label, properties),
          indexOrder,
          slots
        )(id)

      case NodeIndexContainsScan(column, label, property, valueExpr, _, indexOrder, indexType) =>
        NodeIndexContainsScanSlottedPipe(
          column,
          label,
          SlottedIndexedProperty(column, property, slots),
          indexRegistrator.registerQueryIndex(indexType, label, property),
          convertExpressions(valueExpr),
          slots,
          indexOrder
        )(id)

      case NodeIndexEndsWithScan(column, label, property, valueExpr, _, indexOrder, indexType) =>
        NodeIndexEndsWithScanSlottedPipe(
          column,
          label,
          SlottedIndexedProperty(column, property, slots),
          indexRegistrator.registerQueryIndex(indexType, label, property),
          convertExpressions(valueExpr),
          slots,
          indexOrder
        )(id)

      case NodeIndexSeek(column, label, properties, valueExpr, _, indexOrder, indexType) =>
        val indexSeekMode = IndexSeekModeFactory(unique = false, readOnly = readOnly).fromQueryExpression(valueExpr)
        NodeIndexSeekSlottedPipe(
          column,
          label,
          properties.map(SlottedIndexedProperty(column, _, slots)).toIndexedSeq,
          indexRegistrator.registerQueryIndex(indexType, label, properties),
          valueExpr.map(convertExpressions),
          indexSeekMode,
          indexOrder,
          slots
        )(id)

      case NodeUniqueIndexSeek(column, label, properties, valueExpr, _, indexOrder, indexType) =>
        val indexSeekMode = IndexSeekModeFactory(unique = true, readOnly = readOnly).fromQueryExpression(valueExpr)
        NodeIndexSeekSlottedPipe(
          column,
          label,
          properties.map(SlottedIndexedProperty(column, _, slots)).toIndexedSeq,
          indexRegistrator.registerQueryIndex(indexType, label, properties),
          valueExpr.map(convertExpressions),
          indexSeekMode,
          indexOrder,
          slots
        )(id = id)

      case NodeByLabelScan(column, label, _, indexOrder) =>
        indexRegistrator.registerLabelScan()
        NodesByLabelScanSlottedPipe(column, LazyLabel(label), slots, indexOrder)(id)

      case UnionNodeByLabelsScan(column, labels, _, indexOrder) =>
        indexRegistrator.registerLabelScan()
        UnionNodesByLabelsScanSlottedPipe(
          slots.getLongOffsetFor(column),
          labels.map(label => LazyLabel(label)),
          indexOrder
        )(id)

      case IntersectionNodeByLabelsScan(column, labels, _, indexOrder) =>
        indexRegistrator.registerLabelScan()
        IntersectionNodesByLabelsScanSlottedPipe(
          slots.getLongOffsetFor(column),
          labels.map(label => LazyLabel(label)),
          indexOrder
        )(id)

      case DirectedRelationshipUniqueIndexSeek(
          column,
          leftNode,
          rightNode,
          typeToken,
          properties,
          valueExpr,
          _,
          indexOrder,
          indexType
        ) =>
        val indexSeekMode = IndexSeekModeFactory(unique = true, readOnly = readOnly).fromQueryExpression(valueExpr)
        DirectedRelationshipIndexSeekSlottedPipe(
          column,
          leftNode,
          rightNode,
          typeToken,
          properties.map(SlottedIndexedProperty(column, _, slots)).toIndexedSeq,
          indexRegistrator.registerQueryIndex(indexType, typeToken, properties),
          valueExpr.map(convertExpressions),
          indexSeekMode,
          indexOrder,
          slots
        )(id)

      case DirectedRelationshipIndexSeek(
          column,
          leftNode,
          rightNode,
          typeToken,
          properties,
          valueExpr,
          _,
          indexOrder,
          indexType
        ) =>
        val indexSeekMode = IndexSeekModeFactory(unique = false, readOnly = readOnly).fromQueryExpression(valueExpr)
        DirectedRelationshipIndexSeekSlottedPipe(
          column,
          leftNode,
          rightNode,
          typeToken,
          properties.map(SlottedIndexedProperty(column, _, slots)).toIndexedSeq,
          indexRegistrator.registerQueryIndex(indexType, typeToken, properties),
          valueExpr.map(convertExpressions),
          indexSeekMode,
          indexOrder,
          slots
        )(id)

      case UndirectedRelationshipUniqueIndexSeek(
          column,
          leftNode,
          rightNode,
          typeToken,
          properties,
          valueExpr,
          _,
          indexOrder,
          indexType
        ) =>
        val indexSeekMode = IndexSeekModeFactory(unique = true, readOnly = readOnly).fromQueryExpression(valueExpr)
        UndirectedRelationshipIndexSeekSlottedPipe(
          column,
          leftNode,
          rightNode,
          typeToken,
          properties.map(SlottedIndexedProperty(column, _, slots)).toIndexedSeq,
          indexRegistrator.registerQueryIndex(indexType, typeToken, properties),
          valueExpr.map(convertExpressions),
          indexSeekMode,
          indexOrder,
          slots
        )(id)

      case UndirectedRelationshipIndexSeek(
          column,
          leftNode,
          rightNode,
          typeToken,
          properties,
          valueExpr,
          _,
          indexOrder,
          indexType
        ) =>
        val indexSeekMode = IndexSeekModeFactory(unique = false, readOnly = readOnly).fromQueryExpression(valueExpr)
        UndirectedRelationshipIndexSeekSlottedPipe(
          column,
          leftNode,
          rightNode,
          typeToken,
          properties.map(SlottedIndexedProperty(column, _, slots)).toIndexedSeq,
          indexRegistrator.registerQueryIndex(indexType, typeToken, properties),
          valueExpr.map(convertExpressions),
          indexSeekMode,
          indexOrder,
          slots
        )(id)

      case DirectedRelationshipIndexScan(
          column,
          leftNode,
          rightNode,
          typeToken,
          properties,
          _,
          indexOrder,
          indexType
        ) =>
        DirectedRelationshipIndexScanSlottedPipe(
          column,
          leftNode,
          rightNode,
          typeToken,
          properties.map(SlottedIndexedProperty(column, _, slots)).toIndexedSeq,
          indexRegistrator.registerQueryIndex(indexType, typeToken, properties),
          indexOrder,
          slots
        )(id)

      case UndirectedRelationshipIndexScan(
          column,
          leftNode,
          rightNode,
          typeToken,
          properties,
          _,
          indexOrder,
          indexType
        ) =>
        UndirectedRelationshipIndexScanSlottedPipe(
          column,
          leftNode,
          rightNode,
          typeToken,
          properties.map(SlottedIndexedProperty(column, _, slots)).toIndexedSeq,
          indexRegistrator.registerQueryIndex(indexType, typeToken, properties),
          indexOrder,
          slots
        )(id)

      case DirectedAllRelationshipsScan(name, start, end, _) =>
        DirectedAllRelationshipsScanSlottedPipe(
          slots.getLongOffsetFor(name),
          slots.getLongOffsetFor(start),
          slots.getLongOffsetFor(end)
        )(id)

      case UndirectedAllRelationshipsScan(name, start, end, _) =>
        UndirectedAllRelationshipsScanSlottedPipe(
          slots.getLongOffsetFor(name),
          slots.getLongOffsetFor(start),
          slots.getLongOffsetFor(end)
        )(id)

      case DirectedRelationshipTypeScan(name, start, typ, end, _, indexOrder) =>
        indexRegistrator.registerTypeScan()
        DirectedRelationshipTypeScanSlottedPipe(
          slots.getLongOffsetFor(name),
          slots.getLongOffsetFor(start),
          LazyType(typ),
          slots.getLongOffsetFor(end),
          indexOrder
        )(id)

      case UndirectedRelationshipTypeScan(name, start, typ, end, _, indexOrder) =>
        indexRegistrator.registerTypeScan()
        UndirectedRelationshipTypeScanSlottedPipe(
          slots.getLongOffsetFor(name),
          slots.getLongOffsetFor(start),
          LazyType(typ),
          slots.getLongOffsetFor(end),
          indexOrder
        )(id)

      case DirectedUnionRelationshipTypesScan(name, start, types, end, _, indexOrder) =>
        indexRegistrator.registerTypeScan()
        DirectedUnionRelationshipTypesScanSlottedPipe(
          slots.getLongOffsetFor(name),
          slots.getLongOffsetFor(start),
          types.map(t => LazyType(t)(semanticTable)),
          slots.getLongOffsetFor(end),
          indexOrder
        )(id)

      case UndirectedUnionRelationshipTypesScan(name, start, types, end, _, indexOrder) =>
        indexRegistrator.registerTypeScan()
        UndirectedUnionRelationshipTypesScanSlottedPipe(
          slots.getLongOffsetFor(name),
          slots.getLongOffsetFor(start),
          types.map(t => LazyType(t)(semanticTable)),
          slots.getLongOffsetFor(end),
          indexOrder
        )(id)

      case DirectedRelationshipIndexContainsScan(
          name,
          startNode,
          endNode,
          typeToken,
          property,
          valueExpr,
          _,
          indexOrder,
          indexType
        ) =>
        DirectedRelationshipIndexContainsScanSlottedPipe(
          name,
          startNode,
          endNode,
          SlottedIndexedProperty(name, property, slots),
          indexRegistrator.registerQueryIndex(indexType, typeToken, property),
          convertExpressions(valueExpr),
          slots,
          indexOrder
        )(id)

      case UndirectedRelationshipIndexContainsScan(
          name,
          startNode,
          endNode,
          typeToken,
          property,
          valueExpr,
          _,
          indexOrder,
          indexType
        ) =>
        UndirectedRelationshipIndexContainsScanSlottedPipe(
          name,
          startNode,
          endNode,
          SlottedIndexedProperty(name, property, slots),
          indexRegistrator.registerQueryIndex(indexType, typeToken, property),
          convertExpressions(valueExpr),
          slots,
          indexOrder
        )(id)

      case DirectedRelationshipIndexEndsWithScan(
          name,
          startNode,
          endNode,
          typeToken,
          property,
          valueExpr,
          _,
          indexOrder,
          indexType
        ) =>
        DirectedRelationshipIndexEndsWithScanSlottedPipe(
          name,
          startNode,
          endNode,
          SlottedIndexedProperty(name, property, slots),
          indexRegistrator.registerQueryIndex(indexType, typeToken, property),
          convertExpressions(valueExpr),
          slots,
          indexOrder
        )(id)

      case UndirectedRelationshipIndexEndsWithScan(
          name,
          startNode,
          endNode,
          typeToken,
          property,
          valueExpr,
          _,
          indexOrder,
          indexType
        ) =>
        UndirectedRelationshipIndexEndsWithScanSlottedPipe(
          name,
          startNode,
          endNode,
          SlottedIndexedProperty(name, property, slots),
          indexRegistrator.registerQueryIndex(indexType, typeToken, property),
          convertExpressions(valueExpr),
          slots,
          indexOrder
        )(id)

      case _: Argument =>
        ArgumentSlottedPipe()(id)

      // Currently used for testing only
      case _: MultiNodeIndexSeek =>
        throw new CantCompileQueryException(s"Slotted runtime does not support $plan")

      case _ =>
        fallback.onLeaf(plan)
    }
    pipe.rowFactory = SlottedCypherRowFactory(slots, argumentSize)
    pipe
  }

  override def onOneChildPlan(plan: LogicalPlan, source: Pipe): Pipe = {

    val id = plan.id
    val convertExpressions = (e: internal.expressions.Expression) => expressionConverters.toCommandExpression(id, e)

    val slots = physicalPlan.slotConfigurations(id)
    // some operators will overwrite this value
    var argumentSize = physicalPlan.argumentSizes.getOrElse(id, SlotConfiguration.Size.zero)
    finalizeSlotConfiguration(slots)

    def compileEffects(sideEffect: SimpleMutatingPattern): Seq[SideEffect] = sideEffect match {

      case CreatePattern(nodes, relationships) =>
        val nodeOps = nodes.map {
          case ir.CreateNode(node, labels, properties) =>
            CreateSlottedNode(
              CreateNodeSlottedCommand(
                slots.getLongOffsetFor(node),
                labels.toSeq.map(LazyLabel.apply),
                properties.map(convertExpressions)
              ),
              allowNullOrNaNProperty = true
            )

        }
        val relOps = relationships.map { r: ir.CreateRelationship =>
          CreateSlottedRelationship(
            CreateRelationshipSlottedCommand(
              slots.getLongOffsetFor(r.idName),
              SlotConfigurationUtils.makeGetPrimitiveNodeFromSlotFunctionFor(slots(r.startNode)),
              LazyType(r.relType.name),
              SlotConfigurationUtils.makeGetPrimitiveNodeFromSlotFunctionFor(slots(r.endNode)),
              r.properties.map(convertExpressions),
              r.idName,
              r.startNode,
              r.endNode
            ),
            allowNullOrNaNProperty = true
          )
        }
        nodeOps ++ relOps
      case ir.DeleteExpression(expression, forced) => Seq(DeleteOperation(convertExpressions(expression), forced))
      case SetLabelPattern(node, labelNames) =>
        Seq(SlottedSetLabelsOperation(slots(node), labelNames.map(LazyLabel.apply)))
      case RemoveLabelPattern(node, labelNames) =>
        Seq(SlottedRemoveLabelsOperation(slots(node), labelNames.map(LazyLabel.apply)))
      case SetNodePropertyPattern(node, propertyKey, value) =>
        val needsExclusiveLock = internal.expressions.Expression.hasPropertyReadDependency(node, value, propertyKey)
        Seq(SlottedSetNodePropertyOperation(
          slots(node),
          LazyPropertyKey(propertyKey),
          convertExpressions(value),
          needsExclusiveLock
        ))
      case SetNodePropertiesPattern(node, items) =>
        val needsExclusiveLock = items.exists {
          case (p, e) => internal.expressions.Expression.hasPropertyReadDependency(node, e, p)
        }
        val size = items.size
        val keys = new Array[LazyPropertyKey](size)
        val values = new Array[Expression](size)
        items.zipWithIndex.foreach {
          case ((k, e), i) =>
            keys(i) = LazyPropertyKey(k)
            values(i) = convertExpressions(e)
        }

        Seq(SlottedSetNodePropertiesOperation(slots(node), keys, values, needsExclusiveLock))
      case SetNodePropertiesFromMapPattern(node, map, removeOtherProps) =>
        val needsExclusiveLock = internal.expressions.Expression.mapExpressionHasPropertyReadDependency(node, map)
        Seq(SlottedSetNodePropertyFromMapOperation(
          slots(node),
          convertExpressions(map),
          removeOtherProps,
          needsExclusiveLock
        ))
      case SetRelationshipPropertyPattern(relationship, propertyKey, value) =>
        val needsExclusiveLock =
          internal.expressions.Expression.hasPropertyReadDependency(relationship, value, propertyKey)
        Seq(SlottedSetRelationshipPropertyOperation(
          slots(relationship),
          LazyPropertyKey(propertyKey),
          convertExpressions(value),
          needsExclusiveLock
        ))
      case SetRelationshipPropertiesPattern(rel, items) =>
        val needsExclusiveLock = items.exists {
          case (p, e) => internal.expressions.Expression.hasPropertyReadDependency(rel, e, p)
        }
        val size = items.size
        val keys = new Array[LazyPropertyKey](size)
        val values = new Array[Expression](size)
        items.zipWithIndex.foreach {
          case ((k, e), i) =>
            keys(i) = LazyPropertyKey(k)
            values(i) = convertExpressions(e)
        }

        Seq(SlottedSetRelationshipPropertiesOperation(slots(rel), keys, values, needsExclusiveLock))
      case SetRelationshipPropertiesFromMapPattern(relationship, map, removeOtherProps) =>
        val needsExclusiveLock =
          internal.expressions.Expression.mapExpressionHasPropertyReadDependency(relationship, map)
        Seq(SlottedSetRelationshipPropertyFromMapOperation(
          slots(relationship),
          convertExpressions(map),
          removeOtherProps,
          needsExclusiveLock
        ))
      case SetPropertyPattern(entityExpression, propertyKeyName, expression) =>
        Seq(SetPropertyOperation(
          convertExpressions(entityExpression),
          LazyPropertyKey(propertyKeyName),
          convertExpressions(expression)
        ))

      case SetPropertiesPattern(entity, items) =>
        val size = items.size
        val keys = new Array[LazyPropertyKey](size)
        val values = new Array[Expression](size)
        items.zipWithIndex.foreach {
          case ((k, e), i) =>
            keys(i) = LazyPropertyKey(k)
            values(i) = convertExpressions(e)
        }

        Seq(SetPropertiesOperation(convertExpressions(entity), keys, values))
      case SetPropertiesFromMapPattern(entityExpression, expression, removeOtherProps) =>
        Seq(SetPropertyFromMapOperation(
          convertExpressions(entityExpression),
          convertExpressions(expression),
          removeOtherProps
        ))

      case other => throw new IllegalStateException(s"Cannot merge with $other")
    }

    val pipe = plan match {
      case ProduceResult(_, columns) =>
        val runtimeColumns = createProjectionsForResult(columns, slots)
        ProduceResultSlottedPipe(source, runtimeColumns)(id)

      case Expand(_, from, dir, types, to, relName, ExpandAll) =>
        val fromSlot = slots(from)
        val relOffset = slots.getLongOffsetFor(relName)
        val toOffset = slots.getLongOffsetFor(to)
        ExpandAllSlottedPipe(source, fromSlot, relOffset, toOffset, dir, RelationshipTypes(types.toArray), slots)(id)

      case Expand(_, from, dir, types, to, relName, ExpandInto) =>
        val fromSlot = slots(from)
        val relOffset = slots.getLongOffsetFor(relName)
        val toSlot = slots(to)
        ExpandIntoSlottedPipe(source, fromSlot, relOffset, toSlot, dir, RelationshipTypes(types.toArray), slots)(id)

      case OptionalExpand(_, fromName, dir, types, toName, relName, ExpandAll, predicate) =>
        val fromSlot = slots(fromName)
        val relOffset = slots.getLongOffsetFor(relName)
        val toOffset = slots.getLongOffsetFor(toName)
        OptionalExpandAllSlottedPipe(
          source,
          fromSlot,
          relOffset,
          toOffset,
          dir,
          RelationshipTypes(types.toArray),
          slots,
          predicate.map(convertExpressions)
        )(id)

      case OptionalExpand(_, fromName, dir, types, toName, relName, ExpandInto, predicate) =>
        val fromSlot = slots(fromName)
        val relOffset = slots.getLongOffsetFor(relName)
        val toSlot = slots(toName)

        OptionalExpandIntoSlottedPipe(
          source,
          fromSlot,
          relOffset,
          toSlot,
          dir,
          RelationshipTypes(types.toArray),
          slots,
          predicate.map(convertExpressions)
        )(id)

      case VarExpand(
          sourcePlan,
          fromName,
          dir,
          projectedDir,
          types,
          toName,
          relName,
          VarPatternLength(min, max),
          expansionMode,
          nodePredicates,
          relationshipPredicates
        ) =>
        val shouldExpandAll = expansionMode match {
          case ExpandAll  => true
          case ExpandInto => false
        }
        val fromSlot = slots(fromName)
        val relOffset = slots.getReferenceOffsetFor(relName)
        val toSlot = slots(toName)

        // The node/relationship predicates are evaluated on the source pipeline, not the produced one
        val sourceSlots = physicalPlan.slotConfigurations(sourcePlan.id)
        val nodeSlottedPredicates = nodePredicates.map(nodePred =>
          SlottedVariablePredicate(
            expressionSlotForPredicate(nodePred),
            expressionConverters.toCommandExpression(id, nodePred.predicate)
          )
        )
        val relSlottedPredicates = relationshipPredicates.map(relPred =>
          SlottedVariablePredicate(
            expressionSlotForPredicate(relPred),
            expressionConverters.toCommandExpression(id, relPred.predicate)
          )
        )

        argumentSize = SlotConfiguration.Size(sourceSlots.numberOfLongs, sourceSlots.numberOfReferences)
        VarLengthExpandSlottedPipe(
          source,
          fromSlot,
          relOffset,
          toSlot,
          dir,
          projectedDir,
          RelationshipTypes(types.toArray),
          min,
          max,
          shouldExpandAll,
          slots,
          nodePredicates = nodeSlottedPredicates,
          relationshipPredicates = relSlottedPredicates,
          argumentSize = argumentSize
        )(id)

      case BFSPruningVarExpand(
          _,
          from,
          dir,
          types,
          to,
          includeStartNode,
          max,
          depthName,
          nodePredicates,
          relationshipPredicates
        ) =>
        val fromSlot = slots(from)
        val toOffset = slots.getLongOffsetFor(to)
        val depthOffset = depthName.map(slots.getReferenceOffsetFor)

        // The node/relationship predicates are evaluated on the source pipeline, not the produced one
        val sourceSlots = physicalPlan.slotConfigurations(source.id)
        val nodeSlottedPredicates = nodePredicates.map(nodePred =>
          SlottedVariablePredicate(
            expressionSlotForPredicate(nodePred),
            expressionConverters.toCommandExpression(id, nodePred.predicate)
          )
        )
        val relSlottedPredicates = relationshipPredicates.map(relPred =>
          SlottedVariablePredicate(
            expressionSlotForPredicate(relPred),
            expressionConverters.toCommandExpression(id, relPred.predicate)
          )
        )

        argumentSize = SlotConfiguration.Size(sourceSlots.numberOfLongs, sourceSlots.numberOfReferences)
        BFSPruningVarLengthExpandSlottedPipe(
          source,
          fromSlot,
          toOffset,
          depthOffset,
          RelationshipTypes(types.toArray),
          dir,
          includeStartNode,
          max,
          slots,
          nodePredicates = nodeSlottedPredicates,
          relationshipPredicates = relSlottedPredicates
        )(id = id)

      case Optional(inner, symbols) =>
        val nullableSlots = symbolsToSlots(inner.availableSymbols -- symbols, slots)
        OptionalSlottedPipe(source, nullableSlots)(id)

      case Projection(_, _, expressions) =>
        val toProject = expressions collect {
          case (k, e) if isRefSlotAndNotAlias(slots, k) => k -> e
        }
        ProjectionPipe(source, expressionConverters.toCommandProjection(id, toProject))(id)

      case Create(_, nodes, relationships) =>
        CreateSlottedPipe(
          source,
          nodes.map(n =>
            CreateNodeSlottedCommand(
              slots.getLongOffsetFor(n.idName),
              n.labels.toSeq.map(LazyLabel.apply),
              n.properties.map(convertExpressions)
            )
          ).toIndexedSeq,
          relationships.map(r =>
            CreateRelationshipSlottedCommand(
              slots.getLongOffsetFor(r.idName),
              SlotConfigurationUtils.makeGetPrimitiveNodeFromSlotFunctionFor(slots(r.startNode)),
              LazyType(r.relType.name),
              SlotConfigurationUtils.makeGetPrimitiveNodeFromSlotFunctionFor(slots(r.endNode)),
              r.properties.map(convertExpressions),
              r.idName,
              r.startNode,
              r.endNode
            )
          ).toIndexedSeq
        )(id)

      case Merge(_, createNodes, createRelationships, onMatch, onCreate, nodesToLock) =>
        val creates = createNodes.map {
          case ir.CreateNode(node, labels, properties) =>
            CreateSlottedNode(
              CreateNodeSlottedCommand(
                slots.getLongOffsetFor(node),
                labels.toSeq.map(LazyLabel.apply),
                properties.map(convertExpressions)
              ),
              allowNullOrNaNProperty = false
            )
        } ++ createRelationships.map {
          r: ir.CreateRelationship =>
            CreateSlottedRelationship(
              CreateRelationshipSlottedCommand(
                slots.getLongOffsetFor(r.idName),
                SlotConfigurationUtils.makeGetPrimitiveNodeFromSlotFunctionFor(slots(r.startNode)),
                LazyType(r.relType.name),
                SlotConfigurationUtils.makeGetPrimitiveNodeFromSlotFunctionFor(slots(r.endNode)),
                r.properties.map(convertExpressions),
                r.idName,
                r.startNode,
                r.endNode
              ),
              allowNullOrNaNProperty = false
            )
        }

        if (nodesToLock.isEmpty) new MergePipe(
          source,
          (creates ++ onCreate.flatMap(compileEffects)).toArray,
          onMatch.flatMap(compileEffects).toArray
        )(id = id)
        else new LockingMergeSlottedPipe(
          source,
          (creates ++ onCreate.flatMap(compileEffects)).toArray,
          onMatch.flatMap(compileEffects).toArray,
          nodesToLock.map(n => slots(n)).toArray
        )(id = id)

      case Foreach(_, variable, expression, mutations) =>
        val innerVariableSlot =
          slots.get(variable).getOrElse(throw new InternalException(s"Foreach variable '$variable' has no slot"))
        ForeachSlottedPipe(
          source,
          innerVariableSlot,
          convertExpressions(expression),
          mutations.flatMap(compileEffects).toArray
        )(id = id)

      case SetNodeProperty(_, name, propertyKey, expression) =>
        val needsExclusiveLock =
          internal.expressions.Expression.hasPropertyReadDependency(name, expression, propertyKey)
        SetPipe(
          source,
          SlottedSetNodePropertyOperation(
            slots(name),
            LazyPropertyKey(propertyKey),
            convertExpressions(expression),
            needsExclusiveLock
          )
        )(id = id)

      case SetNodeProperties(_, name, items) =>
        val needsExclusiveLock = items.exists {
          case (p, e) => internal.expressions.Expression.hasPropertyReadDependency(name, e, p)
        }
        val size = items.size
        val keys = new Array[LazyPropertyKey](size)
        val values = new Array[Expression](size)
        items.zipWithIndex.foreach {
          case ((k, e), i) =>
            keys(i) = LazyPropertyKey(k)
            values(i) = convertExpressions(e)
        }
        SetPipe(source, SlottedSetNodePropertiesOperation(slots(name), keys, values, needsExclusiveLock))(id = id)

      case SetNodePropertiesFromMap(_, name, expression, removeOtherProps) =>
        val needsExclusiveLock =
          internal.expressions.Expression.mapExpressionHasPropertyReadDependency(name, expression)
        SetPipe(
          source,
          SlottedSetNodePropertyFromMapOperation(
            slots(name),
            convertExpressions(expression),
            removeOtherProps,
            needsExclusiveLock
          )
        )(id = id)

      case SetRelationshipProperty(_, name, propertyKey, expression) =>
        val needsExclusiveLock =
          internal.expressions.Expression.hasPropertyReadDependency(name, expression, propertyKey)
        SetPipe(
          source,
          SlottedSetRelationshipPropertyOperation(
            slots(name),
            LazyPropertyKey(propertyKey),
            convertExpressions(expression),
            needsExclusiveLock
          )
        )(id = id)

      case SetRelationshipProperties(_, name, items) =>
        val needsExclusiveLock = items.exists {
          case (p, e) => internal.expressions.Expression.hasPropertyReadDependency(name, e, p)
        }
        val size = items.size
        val keys = new Array[LazyPropertyKey](size)
        val values = new Array[Expression](size)
        items.zipWithIndex.foreach {
          case ((k, e), i) =>
            keys(i) = LazyPropertyKey(k)
            values(i) = convertExpressions(e)
        }
        SetPipe(source, SlottedSetRelationshipPropertiesOperation(slots(name), keys, values, needsExclusiveLock))(id =
          id
        )

      case SetRelationshipPropertiesFromMap(_, name, expression, removeOtherProps) =>
        val needsExclusiveLock =
          internal.expressions.Expression.mapExpressionHasPropertyReadDependency(name, expression)
        SetPipe(
          source,
          SlottedSetRelationshipPropertyFromMapOperation(
            slots(name),
            convertExpressions(expression),
            removeOtherProps,
            needsExclusiveLock
          )
        )(id = id)

      case EmptyResult(_) =>
        EmptyResultPipe(source)(id)

      case UnwindCollection(_, name, expression) =>
        val offset = slots.getReferenceOffsetFor(name)
        UnwindSlottedPipe(source, convertExpressions(expression), offset, slots)(id)

      case Aggregation(_, groupingExpressions, aggregationExpression) =>
        val aggregation = aggregationExpression.map {
          case (key, expression) =>
            slots.getReferenceOffsetFor(key) -> convertExpressions(expression).asInstanceOf[AggregationExpression]
        }

        val keys = groupingExpressions.keys.toArray

        val longSlotGroupingValues = keys.collect {
          case key if slots(key).isLongSlot =>
            groupingExpressions(key) match {
              case NodeFromSlot(offset, _)                               => offset
              case RelationshipFromSlot(offset, _)                       => offset
              case NullCheckVariable(_, NodeFromSlot(offset, _))         => offset
              case NullCheckVariable(_, RelationshipFromSlot(offset, _)) => offset
              case x => throw new InternalException(
                  s"Cannot build slotted aggregation pipe. Unexpected grouping expression: $x"
                )
            }
        }

        val longSlotGroupingKeys: Array[Int] = keys.collect {
          case x if slots(x).isLongSlot => slots(x).offset
        }

        // Choose the right kind of aggregation table factory based on what grouping columns we have
        val tableFactory =
          if (groupingExpressions.isEmpty) {
            SlottedNonGroupingAggTable.Factory(slots, aggregation)
          } else if (
            longSlotGroupingValues.length == groupingExpressions.size &&
            longSlotGroupingValues.length == longSlotGroupingKeys.length
          ) {
            // If we are able to use primitive for all incoming and outgoing grouping columns, we can use the more effective
            // Primitive table that leverages that the fact that grouping can be done a single array of longs
            SlottedPrimitiveGroupingAggTable.Factory(slots, longSlotGroupingValues, longSlotGroupingKeys, aggregation)
          } else {
            SlottedGroupingAggTable.Factory(
              slots,
              expressionConverters.toGroupingExpression(id, groupingExpressions, Seq.empty),
              aggregation
            )
          }
        EagerAggregationPipe(source, tableFactory)(id)

      case OrderedAggregation(_, groupingExpressions, aggregationExpression, orderToLeverage) =>
        val aggregation = aggregationExpression.map {
          case (key, expression) =>
            slots.getReferenceOffsetFor(key) -> convertExpressions(expression).asInstanceOf[AggregationExpression]
        }

        val (orderedGroupingColumns, unorderedGroupingColumns) =
          partitionGroupingExpressions(expressionConverters, groupingExpressions, orderToLeverage, id)
        val tableFactory =
          if (unorderedGroupingColumns.isEmpty) {
            SlottedOrderedNonGroupingAggTable.Factory(slots, orderedGroupingColumns, aggregation)
          } else {
            SlottedOrderedGroupingAggTable.Factory(slots, orderedGroupingColumns, unorderedGroupingColumns, aggregation)
          }
        OrderedAggregationPipe(source, tableFactory)(id = id)

      case Distinct(_, groupingExpressions) =>
        chooseDistinctPipe(groupingExpressions, Seq.empty, slots, source, id)

      case OrderedDistinct(_, groupingExpressions, orderToLeverage) =>
        chooseDistinctPipe(groupingExpressions, orderToLeverage, slots, source, id)

      case Top(_, sortItems, _) if sortItems.isEmpty => source

      case Top(_, sortItems, SignedDecimalIntegerLiteral("1")) =>
        Top1Pipe(source, SlottedExecutionContextOrdering.asComparator(sortItems.map(translateColumnOrder(slots, _))))(
          id = id
        )

      case Top1WithTies(_, sortItems) =>
        Top1WithTiesPipe(
          source,
          SlottedExecutionContextOrdering.asComparator(sortItems.map(translateColumnOrder(slots, _)))
        )(id = id)

      case Top(_, sortItems, limit) =>
        TopNPipe(
          source,
          convertExpressions(limit),
          SlottedExecutionContextOrdering.asComparator(sortItems.map(translateColumnOrder(slots, _)))
        )(id = id)

      case PartialTop(_, _, stillToSortSuffix, _, _) if stillToSortSuffix.isEmpty => source

      case PartialTop(_, alreadySortedPrefix, stillToSortSuffix, SignedDecimalIntegerLiteral("1"), _) =>
        PartialTop1Pipe(
          source,
          SlottedExecutionContextOrdering.asComparator(alreadySortedPrefix.map(translateColumnOrder(slots, _)).toList),
          SlottedExecutionContextOrdering.asComparator(stillToSortSuffix.map(translateColumnOrder(slots, _)).toList)
        )(id = id)

      case PartialTop(_, alreadySortedPrefix, stillToSortSuffix, limit, skipSortingPrefixLength) =>
        PartialTopNPipe(
          source,
          convertExpressions(limit),
          skipSortingPrefixLength.map(convertExpressions),
          SlottedExecutionContextOrdering.asComparator(alreadySortedPrefix.map(translateColumnOrder(slots, _)).toList),
          SlottedExecutionContextOrdering.asComparator(stillToSortSuffix.map(translateColumnOrder(slots, _)).toList)
        )(id = id)

      // Pipes that do not themselves read/write slots should be fine to use the fallback (non-slot aware pipes)
      case _: Selection |
        _: Limit |
        _: ExhaustiveLimit |
        _: ErrorPlan |
        _: Skip |
        _: NonFuseable |
        _: InjectCompilationError |
        _: Prober =>
        fallback.onOneChildPlan(plan, source)

      case Sort(_, sortItems) =>
        SortSlottedPipe(
          source,
          SlottedExecutionContextOrdering.asComparator(sortItems.map(translateColumnOrder(slots, _)))
        )(id = id)

      case PartialSort(_, alreadySortedPrefix, stillToSortSuffix, skipSortingPrefixLength) =>
        PartialSortPipe(
          source,
          SlottedExecutionContextOrdering.asComparator(alreadySortedPrefix.map(translateColumnOrder(slots, _))),
          SlottedExecutionContextOrdering.asComparator(stillToSortSuffix.map(translateColumnOrder(slots, _))),
          skipSortingPrefixLength.map(convertExpressions)
        )(id = id)

      case Eager(_, _) =>
        EagerSlottedPipe(source, slots)(id)

      case _: DeleteNode |
        _: DeleteRelationship |
        _: DeletePath |
        _: DeleteExpression |
        _: DetachDeleteNode |
        _: DetachDeletePath |
        _: DetachDeleteExpression =>
        fallback.onOneChildPlan(plan, source)

      case _: SetLabels |
        _: SetProperty |
        _: SetProperties |
        _: SetPropertiesFromMap |
        _: RemoveLabels =>
        fallback.onOneChildPlan(plan, source)

      case LoadCSV(_, url, variableName, format, fieldTerminator, legacyCsvQuoteEscaping, bufferSize) =>
        val lineVariableOffset = slots.getReferenceOffsetFor(variableName)
        val metaDataOffset = slots.getMetaDataOffsetFor(LOAD_CSV_METADATA_KEY)
        LoadCSVSlottedPipe(
          source,
          format,
          convertExpressions(url),
          lineVariableOffset,
          metaDataOffset,
          fieldTerminator,
          legacyCsvQuoteEscaping,
          bufferSize
        )(id)

      case _ =>
        fallback.onOneChildPlan(plan, source)
    }
    pipe.rowFactory = SlottedCypherRowFactory(slots, argumentSize)
    pipe
  }

  override def onTwoChildPlan(plan: LogicalPlan, lhs: Pipe, rhs: Pipe): Pipe = {

    val slotConfigs = physicalPlan.slotConfigurations
    val id = plan.id
    val convertExpressions = (e: internal.expressions.Expression) => expressionConverters.toCommandExpression(id, e)
    val slots = slotConfigs(id)
    // some plans (e.g. Apply) have no argument size attribute set
    val argumentSize = physicalPlan.argumentSizes.getOrElse(id, SlotConfiguration.Size.zero)
    finalizeSlotConfiguration(slots)

    val pipe = plan match {
      case Apply(_, _, _) =>
        ApplySlottedPipe(lhs, rhs)(id)

      case RollUpApply(_, rhsPlan, collectionName, identifierToCollect) =>
        val rhsSlots = slotConfigs(rhsPlan.id)
        val identifierToCollectExpression = createProjectionForIdentifier(rhsSlots)(identifierToCollect)
        val collectionRefSlotOffset = slots.getReferenceOffsetFor(collectionName)
        RollUpApplySlottedPipe(lhs, rhs, collectionRefSlotOffset, identifierToCollectExpression, slots)(id = id)

      case _: CartesianProduct =>
        val lhsPlan = plan.lhs.get
        val lhsSlots = slotConfigs(lhsPlan.id)

        // Verify the assumption that the only shared slots we have are arguments which are identical on both lhs and rhs.
        // This assumption enables us to use array copy within CartesianProductSlottedPipe.
        checkOnlyWhenAssertionsAreEnabled(verifyOnlyArgumentsAreSharedSlots(plan, physicalPlan))

        CartesianProductSlottedPipe(lhs, rhs, lhsSlots.numberOfLongs, lhsSlots.numberOfReferences, slots, argumentSize)(
          id
        )

      case joinPlan: NodeHashJoin =>
        val nodes = joinPlan.nodes.toArray // Make sure that leftNodes and rightNodes have the same order
        val leftNodes = KeyOffsets.create(slots, nodes)
        val rhsSlots = slotConfigs(joinPlan.right.id)
        val rightNodes = KeyOffsets.create(rhsSlots, nodes)

        // Verify the assumption that the argument slots are the same on both sides
        checkOnlyWhenAssertionsAreEnabled(verifyArgumentsAreTheSameOnBothSides(plan, physicalPlan))
        val rhsSlotMappings = computeSlotMappings(rhsSlots, argumentSize, slots)

        if (leftNodes.isSingle) {
          NodeHashJoinSlottedSingleNodePipe(leftNodes.asSingle, rightNodes.asSingle, lhs, rhs, slots, rhsSlotMappings)(
            id
          )
        } else {
          NodeHashJoinSlottedPipe(leftNodes, rightNodes, lhs, rhs, slots, rhsSlotMappings)(id)
        }

      case ValueHashJoin(lhsPlan, rhsPlan, Equals(lhsAstExp, rhsAstExp)) =>
        val lhsCmdExp = convertExpressions(lhsAstExp)
        val rhsCmdExp = convertExpressions(rhsAstExp)
        val rhsSlots = slotConfigs(rhsPlan.id)

        // Verify the assumption that the only shared slots we have are arguments which are identical on both lhs and rhs.
        // This assumption enables us to use array copy within ValueHashJoin.
        checkOnlyWhenAssertionsAreEnabled(verifyArgumentsAreTheSameOnBothSides(plan, physicalPlan))
        val rhsSlotMappings = computeSlotMappings(rhsSlots, argumentSize, slots)

        ValueHashJoinSlottedPipe(lhsCmdExp, rhsCmdExp, lhs, rhs, slots, rhsSlotMappings)(id)

      case ConditionalApply(left, right, items) =>
        val (longIds, refIds) = items.partition(idName =>
          slots.get(idName) match {
            case Some(_: LongSlot) => true
            case Some(_: RefSlot)  => false
            case _                 => throw new InternalException("We expect only an existing LongSlot or RefSlot here")
          }
        )
        val longOffsets = longIds.map(e => slots.getLongOffsetFor(e))
        val refOffsets = refIds.map(e => slots.getReferenceOffsetFor(e))
        val nullableSlots = symbolsToSlots(right.availableSymbols -- left.availableSymbols, slots)
        ConditionalApplySlottedPipe(lhs, rhs, longOffsets.toArray, refOffsets.toArray, slots, nullableSlots)(id)

      case AntiConditionalApply(left, right, items) =>
        val (longIds, refIds) = items.partition(idName =>
          slots.get(idName) match {
            case Some(_: LongSlot) => true
            case Some(_: RefSlot)  => false
            case _                 => throw new InternalException("We expect only an existing LongSlot or RefSlot here")
          }
        )
        val longOffsets = longIds.map(e => slots.getLongOffsetFor(e))
        val refOffsets = refIds.map(e => slots.getReferenceOffsetFor(e))
        val nullableSlots = symbolsToSlots(right.availableSymbols -- left.availableSymbols, slots)
        AntiConditionalApplySlottedPipe(lhs, rhs, longOffsets.toArray, refOffsets.toArray, slots, nullableSlots)(id)

      case ForeachApply(_, _, variable, expression) =>
        val innerVariableSlot =
          slots.get(variable).getOrElse(throw new InternalException(s"Foreach variable '$variable' has no slot"))
        ForeachSlottedApplyPipe(lhs, rhs, innerVariableSlot, convertExpressions(expression))(id)

      case TransactionForeach(_, _, batchSize, onErrorBehaviour, maybeReportAs) =>
        TransactionForeachSlottedPipe(
          lhs,
          rhs,
          expressionConverters.toCommandExpression(id, batchSize),
          onErrorBehaviour,
          maybeReportAs.map(slots.apply)
        )(id = id)

      case TransactionApply(lhsPlan, rhsPlan, batchSize, onErrorBehaviour, maybeReportAs) =>
        TransactionApplySlottedPipe(
          lhs,
          rhs,
          expressionConverters.toCommandExpression(id, batchSize),
          onErrorBehaviour,
          (rhsPlan.availableSymbols -- lhsPlan.availableSymbols).map(slots.apply),
          maybeReportAs.map(slots.apply)
        )(id = id)

      case SelectOrSemiApply(_, _, expression) =>
        SelectOrSemiApplySlottedPipe(
          lhs,
          rhs,
          expressionConverters.toCommandExpression(id, expression),
          negated = false,
          slots
        )(id)

      case SelectOrAntiSemiApply(_, _, expression) =>
        SelectOrSemiApplySlottedPipe(
          lhs,
          rhs,
          expressionConverters.toCommandExpression(id, expression),
          negated = true,
          slots
        )(id)

      case Union(_, _) =>
        val lhsSlots = slotConfigs(lhs.id)
        val rhsSlots = slotConfigs(rhs.id)
        UnionSlottedPipe(
          lhs,
          rhs,
          slots,
          SlottedPipeMapper.computeUnionRowMapping(lhsSlots, slots),
          SlottedPipeMapper.computeUnionRowMapping(rhsSlots, slots)
        )(id = id)

      case OrderedUnion(_, _, sortedColumns) =>
        val lhsSlots = slotConfigs(lhs.id)
        val rhsSlots = slotConfigs(rhs.id)
        OrderedUnionSlottedPipe(
          lhs,
          rhs,
          slots,
          SlottedPipeMapper.computeUnionRowMapping(lhsSlots, slots),
          SlottedPipeMapper.computeUnionRowMapping(rhsSlots, slots),
          SlottedExecutionContextOrdering.asComparator(sortedColumns.map(translateColumnOrder(slots, _)))
        )(id = id)

      case AssertSameRelationship(relationship, _, _) =>
        AssertSameRelationshipSlottedPipe(lhs, rhs, relationship, slots(relationship))(id = id)

      case Trail(
          _,
          _,
          repetition,
          start,
          end,
          innerStart,
          innerEnd,
          groupNodes,
          groupRelationships,
          innerRelationships,
          previouslyBoundRelationships,
          previouslyBoundRelationshipGroups,
          reverseGroupVariableProjections
        ) =>
        val rhsSlots = slotConfigs(rhs.id)
        val lhsSlots = slotConfigs(lhs.id)
        TrailSlottedPipe(
          lhs,
          rhs,
          repetition,
          slots(start),
          slots.getLongOffsetFor(end),
          rhsSlots.getLongOffsetFor(innerStart),
          trailStateMetadataSlot = rhsSlots.getMetaDataOffsetFor(SlotAllocation.TRAIL_STATE_METADATA_KEY),
          rhsSlots(innerEnd),
          groupNodes.map(n => GroupSlot(rhsSlots(n.singletonName), slots(n.groupName))).toArray,
          groupRelationships.map(r => GroupSlot(rhsSlots(r.singletonName), slots(r.groupName))).toArray,
          innerRelationships.map(r => rhsSlots(r)),
          previouslyBoundRelationships.map(r => lhsSlots(r)),
          previouslyBoundRelationshipGroups.map(r => lhsSlots(r)),
          slots,
          rhsSlots,
          argumentSize,
          reverseGroupVariableProjections
        )(id = id)

      case _ =>
        fallback.onTwoChildPlan(plan, lhs, rhs)
    }
    pipe.rowFactory = SlottedCypherRowFactory(slots, argumentSize)
    pipe
  }

  private def chooseDistinctPipe(
    groupingExpressions: Map[String, internal.expressions.Expression],
    orderToLeverage: Seq[internal.expressions.Expression],
    slots: SlotConfiguration,
    source: Pipe,
    id: Id
  ): Pipe = {
    val convertExpressions = (e: internal.expressions.Expression) => expressionConverters.toCommandExpression(id, e)

    val runtimeProjections: Map[Slot, commands.expressions.Expression] = groupingExpressions.map {
      case (key, expression) =>
        slots(key) -> convertExpressions(expression)
    }

    val physicalDistinctOp = findDistinctPhysicalOp(groupingExpressions, orderToLeverage)

    physicalDistinctOp match {
      case DistinctAllPrimitive(offsets, orderedOffsets) if offsets.size == 1 && orderedOffsets.isEmpty =>
        val (toSlot, runtimeExpression) = runtimeProjections.head
        DistinctSlottedSinglePrimitivePipe(source, slots, toSlot, offsets.head, runtimeExpression)(id)

      case DistinctAllPrimitive(offsets, orderedOffsets) if offsets.size == 1 && orderedOffsets == offsets =>
        val (toSlot, runtimeExpression) = runtimeProjections.head
        OrderedDistinctSlottedSinglePrimitivePipe(source, slots, toSlot, offsets.head, runtimeExpression)(id)

      case DistinctAllPrimitive(offsets, orderedOffsets) =>
        if (orderToLeverage.isEmpty) {
          DistinctSlottedPrimitivePipe(
            source,
            slots,
            offsets.sorted.toArray,
            expressionConverters.toGroupingExpression(id, groupingExpressions, orderToLeverage)
          )(id)
        } else if (orderedOffsets == offsets) {
          AllOrderedDistinctSlottedPrimitivePipe(
            source,
            slots,
            offsets.sorted.toArray,
            expressionConverters.toGroupingExpression(id, groupingExpressions, orderToLeverage)
          )(id)
        } else {
          OrderedDistinctSlottedPrimitivePipe(
            source,
            slots,
            orderedOffsets.sorted.toArray,
            offsets.filterNot(orderedOffsets.contains(_)).sorted.toArray,
            expressionConverters.toGroupingExpression(id, groupingExpressions, orderToLeverage)
          )(id)
        }

      case DistinctWithReferences =>
        if (orderToLeverage.isEmpty) {
          DistinctSlottedPipe(
            source,
            slots,
            expressionConverters.toGroupingExpression(id, groupingExpressions, orderToLeverage)
          )(id)
        } else if (groupingExpressions.values.forall(orderToLeverage.contains)) {
          AllOrderedDistinctSlottedPipe(
            source,
            slots,
            expressionConverters.toGroupingExpression(id, groupingExpressions, orderToLeverage)
          )(id)
        } else {
          val (ordered, unordered) =
            partitionGroupingExpressions(expressionConverters, groupingExpressions, orderToLeverage, id)
          OrderedDistinctSlottedPipe(source, slots, ordered, unordered)(id)
        }
    }
  }

  // Verifies the assumption that all shared slots are arguments with slot offsets within the first argument size number of slots
  // and the number of shared slots are identical to the argument size.
  private def verifyOnlyArgumentsAreSharedSlots(plan: LogicalPlan, physicalPlan: PhysicalPlan): Boolean = {
    val argumentSize = physicalPlan.argumentSizes(plan.id)
    val lhsPlan = plan.lhs.get
    val rhsPlan = plan.rhs.get
    val lhsSlots = physicalPlan.slotConfigurations(lhsPlan.id)
    val rhsSlots = physicalPlan.slotConfigurations(rhsPlan.id)
    val sharedSlots =
      rhsSlots.filterSlots({
        case (VariableSlotKey(k), _) => lhsSlots.get(k).isDefined
        case (CachedPropertySlotKey(k), slot) =>
          slot.offset < argumentSize.nReferences && lhsSlots.hasCachedPropertySlot(k)
        case (MetaDataSlotKey(k), slot) => slot.offset < argumentSize.nReferences && lhsSlots.hasMetaDataSlot(k)
        case (key: ApplyPlanSlotKey, _) => throw new InternalException(s"Unexpected slot key $key")
        case (key: OuterNestedApplyPlanSlotKey, _) => throw new InternalException(s"Unexpected slot key $key")
      })

    val (sharedLongSlots, sharedRefSlots) = sharedSlots.partition(_.isLongSlot)

    @nowarn("msg=return statement")
    def checkSharedSlots(slots: Seq[Slot], expectedSlots: Int): Boolean = {
      val sorted = slots.sortBy(_.offset)
      var prevOffset = -1
      for (slot <- sorted) {
        if (
          slot.offset == prevOffset || // if we have aliases for the same slot, we will get it again
          slot.offset == prevOffset + 1
        ) { // otherwise we expect the next shared slot to sit at the next offset
          prevOffset = slot.offset
        } else {
          return false
        }
      }
      prevOffset + 1 == expectedSlots
    }

    val longSlotsOk = checkSharedSlots(sharedLongSlots.toSeq, argumentSize.nLongs)
    val refSlotsOk = checkSharedSlots(sharedRefSlots.toSeq, argumentSize.nReferences)

    if (!longSlotsOk || !refSlotsOk) {
      val longSlotsMessage =
        if (longSlotsOk) "" else s"#long arguments=${argumentSize.nLongs} shared long slots: $sharedLongSlots "
      val refSlotsMessage =
        if (refSlotsOk) "" else s"#ref arguments=${argumentSize.nReferences} shared ref slots: $sharedRefSlots "
      throw new InternalException(
        s"Unexpected slot configuration. Shared slots not only within argument size: $longSlotsMessage$refSlotsMessage"
      )
    }

    true
  }

  private def verifyArgumentsAreTheSameOnBothSides(plan: LogicalPlan, physicalPlan: PhysicalPlan): Boolean = {
    val argumentSize = physicalPlan.argumentSizes(plan.id)
    val lhsPlan = plan.lhs.get
    val rhsPlan = plan.rhs.get
    val lhsSlots = physicalPlan.slotConfigurations(lhsPlan.id)
    val rhsSlots = physicalPlan.slotConfigurations(rhsPlan.id)

    val lhsArgLongSlots = mutable.ArrayBuffer.empty[(String, Slot)]
    val lhsArgRefSlots = mutable.ArrayBuffer.empty[(String, Slot)]
    val rhsArgLongSlots = mutable.ArrayBuffer.empty[(String, Slot)]
    val rhsArgRefSlots = mutable.ArrayBuffer.empty[(String, Slot)]
    lhsSlots.foreachSlotAndAliases({
      case SlotWithKeyAndAliases(VariableSlotKey(key), slot, aliases) =>
        if (slot.isLongSlot && slot.offset < argumentSize.nLongs) {
          lhsArgLongSlots += (key -> slot)
          aliases.foreach(alias => lhsArgLongSlots += (alias -> slot))
        } else if (!slot.isLongSlot && slot.offset < argumentSize.nReferences) {
          lhsArgRefSlots += (key -> slot)
          aliases.foreach(alias => lhsArgRefSlots += (alias -> slot))
        }
      case SlotWithKeyAndAliases(CachedPropertySlotKey(key), slot, _) =>
        if (slot.offset < argumentSize.nReferences) {
          lhsArgRefSlots += (key.asCanonicalStringVal -> slot)
        }
      case SlotWithKeyAndAliases(MetaDataSlotKey(key), slot, _) =>
        if (slot.offset < argumentSize.nReferences) {
          lhsArgRefSlots += (key -> slot)
        }
      case SlotWithKeyAndAliases(key: ApplyPlanSlotKey, _, _) =>
        throw new InternalException(s"Unexpected slot key $key")
      case SlotWithKeyAndAliases(key: OuterNestedApplyPlanSlotKey, _, _) =>
        throw new InternalException(s"Unexpected slot key $key")
    })
    rhsSlots.foreachSlotAndAliases({
      case SlotWithKeyAndAliases(VariableSlotKey(key), slot, aliases) =>
        if (slot.isLongSlot && slot.offset < argumentSize.nLongs) {
          rhsArgLongSlots += (key -> slot)
          aliases.foreach(alias => rhsArgLongSlots += (alias -> slot))
        } else if (!slot.isLongSlot && slot.offset < argumentSize.nReferences) {
          rhsArgRefSlots += (key -> slot)
          aliases.foreach(alias => rhsArgRefSlots += (alias -> slot))
        }
      case SlotWithKeyAndAliases(CachedPropertySlotKey(key), slot, _) =>
        if (slot.offset < argumentSize.nReferences) {
          rhsArgRefSlots += (key.asCanonicalStringVal -> slot)
        }
      case SlotWithKeyAndAliases(MetaDataSlotKey(key), slot, _) =>
        if (slot.offset < argumentSize.nReferences) {
          rhsArgRefSlots += (key -> slot)
        }
      case SlotWithKeyAndAliases(key: ApplyPlanSlotKey, _, _) =>
        throw new InternalException(s"Unexpected slot key $key")
      case SlotWithKeyAndAliases(key: OuterNestedApplyPlanSlotKey, _, _) =>
        throw new InternalException(s"Unexpected slot key $key")
    })

    def sameSlotsInOrder(a: ArrayBuffer[(String, Slot)], b: ArrayBuffer[(String, Slot)]): Boolean =
      a.sortBy(_._1).zip(b.sortBy(_._1)) forall {
        case ((k1, slot1), (k2, slot2)) =>
          k1 == k2 && slot1.offset == slot2.offset && slot1.isTypeCompatibleWith(slot2)
      }

    val longSlotsOk = lhsArgLongSlots.size == rhsArgLongSlots.size && sameSlotsInOrder(lhsArgLongSlots, rhsArgLongSlots)
    val refSlotsOk = lhsArgRefSlots.size == rhsArgRefSlots.size && sameSlotsInOrder(lhsArgRefSlots, rhsArgRefSlots)

    if (!longSlotsOk || !refSlotsOk) {
      val longSlotsMessage =
        if (longSlotsOk) "" else s"#long arguments=${argumentSize.nLongs} lhs: $lhsArgLongSlots rhs: $rhsArgLongSlots "
      val refSlotsMessage =
        if (refSlotsOk) "" else s"#ref arguments=${argumentSize.nReferences} lhs: $lhsArgRefSlots rhs: $rhsArgRefSlots "
      throw new InternalException(
        s"Unexpected slot configuration. Arguments differ between lhs and rhs: $longSlotsMessage$refSlotsMessage"
      )
    }
    true
  }
}

object SlottedPipeMapper {

  case class SlotMappings(
    slotMapping: Array[SlotMapping],
    cachedPropertyMappings: Array[(Int, Int)]
  )

  /** Computes slot mappings from one slot configuration to another
   *
   * Given the values:
   *                  0,    1, 2, 3, 4, 5, 6      0, 1,  2,  3,  4, 5
   *  fromSlots = [arg1, arg2, a, b, c]       [arg3, k, p1,  m, p2, d]
   *  toSlots =   [arg1, arg2, a, c, d, e, b] [arg3, k,  m, p1, p2]
   *  argumentSize.nLongs = 2
   *  argumentSize.nReferences = 1
   *
   * it produces the output:
   * SlotMapping:
   *  SlotMapping(fromOffset=2, toOffset=2, fromIsLongSlot=true, toIsLongSlot=true)
   *  SlotMapping(fromOffset=3, toOffset=6, fromIsLongSlot=true, toIsLongSlot=true)
   *  SlotMapping(fromOffset=4, toOffset=3, fromIsLongSlot=true, toIsLongSlot=true)
   *  SlotMapping(fromOffset=1, toOffset=1, fromIsLongSlot=false, toIsLongSlot=false)
   *  SlotMapping(fromOffset=3, toOffset=2, fromIsLongSlot=false, toIsLongSlot=false)
   *  SlotMapping(fromOffset=5, toOffset=4, fromIsLongSlot=false, toIsLongSlot=true)
   *
   * CachedPropertyMapping:
   *  (2 -> 3)
   *  (4 -> 4)
   *
   * */
  def computeSlotMappings(
    fromSlots: SlotConfiguration,
    argumentSize: SlotConfiguration.Size,
    toSlots: SlotConfiguration
  ): SlotMappings = {
    val slotMappings = collection.mutable.ArrayBuffer.newBuilder[SlotMapping]
    val cachedPropertyMappings = collection.mutable.ArrayBuffer.newBuilder[(Int, Int)]

    fromSlots.foreachSlotAndAliasesOrdered({
      case SlotWithKeyAndAliases(VariableSlotKey(key), fromSlot, _)
        if (fromSlot.isLongSlot && fromSlot.offset >= argumentSize.nLongs) || (!fromSlot.isLongSlot && fromSlot.offset >= argumentSize.nReferences) =>
        toSlots.get(key).foreach { toSlot =>
          slotMappings += SlotMapping(fromSlot.offset, toSlot.offset, fromSlot.isLongSlot, toSlot.isLongSlot)
        }
      case SlotWithKeyAndAliases(_: VariableSlotKey, _, _)             => // do nothing, part of arguments
      case SlotWithKeyAndAliases(_: ApplyPlanSlotKey, _, _)            => // do nothing, part of arguments
      case SlotWithKeyAndAliases(_: OuterNestedApplyPlanSlotKey, _, _) => // do nothing, part of arguments
      case SlotWithKeyAndAliases(CachedPropertySlotKey(cnp), _, _) =>
        val offset = fromSlots.getCachedPropertyOffsetFor(cnp)
        if (offset >= argumentSize.nReferences) {
          cachedPropertyMappings += offset -> toSlots.getCachedPropertyOffsetFor(cnp)
        }
      case SlotWithKeyAndAliases(MetaDataSlotKey(key), _, _) =>
        val fromOffset = fromSlots.getMetaDataOffsetFor(key)
        if (fromOffset >= argumentSize.nReferences) {
          val toOffset = toSlots.getMetaDataOffsetFor(key)
          slotMappings += SlotMapping(fromOffset, toOffset, fromIsLongSlot = false, toIsLongSlot = false)
        }
    })

    SlotMappings(slotMappings.result().toArray, cachedPropertyMappings.result().toArray)
  }

  /**
   * We use these objects to figure out:
   * a) can we use the primitive distinct pipe?
   * b) if we can, what offsets are interesting
   */
  sealed trait DistinctPhysicalOp {
    def addExpression(e: internal.expressions.Expression, ordered: Boolean): DistinctPhysicalOp
  }

  case class DistinctAllPrimitive(offsets: Seq[Int], orderedOffsets: Seq[Int]) extends DistinctPhysicalOp {

    override def addExpression(e: internal.expressions.Expression, ordered: Boolean): DistinctPhysicalOp = e match {
      case v: NodeFromSlot =>
        val oo = if (ordered) orderedOffsets :+ v.offset else orderedOffsets
        DistinctAllPrimitive(offsets :+ v.offset, oo)
      case v: RelationshipFromSlot =>
        val oo = if (ordered) orderedOffsets :+ v.offset else orderedOffsets
        DistinctAllPrimitive(offsets :+ v.offset, oo)
      case _ =>
        DistinctWithReferences
    }
  }

  case object DistinctWithReferences extends DistinctPhysicalOp {

    override def addExpression(e: internal.expressions.Expression, ordered: Boolean): DistinctPhysicalOp =
      DistinctWithReferences
  }

  def findDistinctPhysicalOp(
    groupingExpressions: Map[String, internal.expressions.Expression],
    orderToLeverage: Seq[internal.expressions.Expression]
  ): DistinctPhysicalOp = {
    groupingExpressions.foldLeft[DistinctPhysicalOp](DistinctAllPrimitive(Seq.empty, Seq.empty)) {
      case (acc: DistinctPhysicalOp, (_, expression)) =>
        acc.addExpression(expression, orderToLeverage.contains(expression))
    }
  }

  def createProjectionsForResult(columns: Seq[String], slots: SlotConfiguration): Seq[(String, Expression)] = {
    val runtimeColumns: Seq[(String, commands.expressions.Expression)] =
      columns.map(createProjectionForIdentifier(slots))
    runtimeColumns
  }

  private def createProjectionForIdentifier(slots: SlotConfiguration)(identifier: String): (String, Expression) = {
    val slot = slots.get(identifier).getOrElse(
      throw new InternalException(s"Did not find `$identifier` in the slot configuration")
    )
    identifier -> SlottedPipeMapper.projectSlotExpression(slot)
  }

  private def projectSlotExpression(slot: Slot): commands.expressions.Expression = slot match {
    case LongSlot(offset, false, CTNode) =>
      slotted.expressions.NodeFromSlot(offset)
    case LongSlot(offset, true, CTNode) =>
      slotted.expressions.NullCheck(offset, slotted.expressions.NodeFromSlot(offset))
    case LongSlot(offset, false, CTRelationship) =>
      slotted.expressions.RelationshipFromSlot(offset)
    case LongSlot(offset, true, CTRelationship) =>
      slotted.expressions.NullCheck(offset, slotted.expressions.RelationshipFromSlot(offset))

    case RefSlot(offset, _, _) =>
      slotted.expressions.ReferenceFromSlot(offset)

    case _ =>
      throw new InternalException(s"Do not know how to project $slot")
  }

  /**
   * A mapping from one input slot configuration to the output slot configuration that dictates what to copy in a Union.
   */
  sealed trait UnionSlotMapping
  case class CopyLongSlot(sourceOffset: Int, targetOffset: Int) extends UnionSlotMapping
  case class CopyRefSlot(sourceOffset: Int, targetOffset: Int) extends UnionSlotMapping
  case class CopyCachedProperty(sourceOffset: Int, targetOffset: Int) extends UnionSlotMapping
  case class ProjectLongToRefSlot(sourceSlot: LongSlot, targetOffset: Int) extends UnionSlotMapping

  /**
   * A [[UnionSlotMapping]] is a function that actually performs the copying.
   */
  trait RowMapping extends {
    def mapRows(incoming: ReadableRow, outgoing: CypherRow, state: QueryState): Unit
  }

  /**
   * compute mapping from incoming to outgoing pipeline, the slot order may differ
   * between the output and the input (lhs and rhs) and it may be the case that
   * we have a reference slot in the output but a long slot on one of the inputs,
   * e.g. MATCH (n) RETURN n UNION RETURN 42 AS n
   */
  def computeUnionSlotMappings(in: SlotConfiguration, out: SlotConfiguration): Iterable[UnionSlotMapping] = {
    in.mapSlotsDoNotSkipAliases {
      case (VariableSlotKey(k), inLongSlot: LongSlot) =>
        out.get(k).map {
          case l: LongSlot => CopyLongSlot(inLongSlot.offset, l.offset)
          case r: RefSlot  => ProjectLongToRefSlot(inLongSlot, r.offset)
        }
      case (VariableSlotKey(k), inSlot: RefSlot) =>
        // This means out must be a ref slot as well, if it exists, otherwise slot allocation was wrong
        out.get(k).map {
          case l: LongSlot => throw new IllegalStateException(s"Expected Union output slot to be a refslot but was: $l")
          case r: RefSlot  => CopyRefSlot(inSlot.offset, r.offset)
        }
      case (CachedPropertySlotKey(cachedProp), inRefSlot) =>
        out.getCachedPropertySlot(cachedProp).map {
          outRefSlot => CopyCachedProperty(inRefSlot.offset, outRefSlot.offset)
        }
      case (MetaDataSlotKey(key), inRefSlot) =>
        out.getMetaDataSlot(key).map {
          outRefSlot => CopyRefSlot(inRefSlot.offset, outRefSlot.offset)
        }
      case (ApplyPlanSlotKey(id), slot) =>
        out.getArgumentSlot(id).map {
          outArgumentSlot => CopyLongSlot(slot.offset, outArgumentSlot.offset)
        }
      case (OuterNestedApplyPlanSlotKey(id), slot) =>
        out.getNestedArgumentSlot(id).map {
          outArgumentSlot => CopyLongSlot(slot.offset, outArgumentSlot.offset)
        }
    }.flatten
  }

  /**
   * Compute the [[RowMapping]] from [[UnionSlotMapping]]s, which can be then applied to Rows at runtime.
   */
  def computeUnionRowMapping(in: SlotConfiguration, out: SlotConfiguration): RowMapping = {
    val mappings = computeUnionSlotMappings(in, out)

    // Collect all 4 types of mappings
    case class ProjectExpressionToRefSlot(expression: Expression, targetOffset: Int)
    val copyLongSlots = mappings.collect { case c: CopyLongSlot => c }.toArray.sortBy(_.targetOffset)
    val copyRefSlots = mappings.collect { case c: CopyRefSlot => c }.toArray.sortBy(_.targetOffset)
    val copyCachedProperties = mappings.collect { case c: CopyCachedProperty => c }.toArray.sortBy(_.targetOffset)
    val projectExpressionToRefSlots = mappings.collect {
      case c: ProjectLongToRefSlot =>
        // Pre-compute projection expression
        val projectionExpression = projectSlotExpression(c.sourceSlot)
        ProjectExpressionToRefSlot(projectionExpression, c.targetOffset)
    }.toArray.sortBy(_.targetOffset)

    // Apply all transformations
    (in, out, state) => {
      var i = 0
      while (i < copyLongSlots.length) {
        val x = copyLongSlots(i)
        out.setLongAt(x.targetOffset, in.getLongAt(x.sourceOffset))
        i += 1
      }
      i = 0
      while (i < copyRefSlots.length) {
        val x = copyRefSlots(i)
        out.setRefAt(x.targetOffset, in.getRefAt(x.sourceOffset))
        i += 1
      }
      i = 0
      while (i < copyCachedProperties.length) {
        val x = copyCachedProperties(i)
        out.setCachedPropertyAt(x.targetOffset, in.getCachedPropertyAt(x.sourceOffset))
        i += 1
      }
      i = 0
      while (i < projectExpressionToRefSlots.length) {
        val x = projectExpressionToRefSlots(i)
        out.setRefAt(x.targetOffset, x.expression(in, state))
        i += 1
      }
    }
  }

  /**
   * Translate a [[plans.ColumnOrder]] into a [[slotted.ColumnOrder]] where every row is expected to have the same slot configuration
   */
  def translateColumnOrder(slots: SlotConfiguration, s: plans.ColumnOrder): slotted.ColumnOrder = s match {
    case plans.Ascending(name) =>
      slots.get(name) match {
        case Some(slot) => slotted.Ascending(slot)
        case None       => throw new InternalException(s"Did not find `$name` in the pipeline information")
      }
    case plans.Descending(name) =>
      slots.get(name) match {
        case Some(slot) => slotted.Descending(slot)
        case None       => throw new InternalException(s"Did not find `$name` in the pipeline information")
      }
  }

  /**
   * Translate a [[plans.ColumnOrder]] into a [[slotted.ColumnOrder2]] where rows have different slot configurations depending
   * on which downstream they originate from (lhs or rhs).
   */
  def translateColumnOrder2(
    lhsSlots: SlotConfiguration,
    rhsSlots: SlotConfiguration,
    s: plans.ColumnOrder
  ): slotted.ColumnOrder2 = s match {
    case plans.Ascending(name) =>
      val lhsSlot = lhsSlots.get(name) match {
        case Some(slot) => slot
        case None       => throw new InternalException(s"Did not find `$name` in the pipeline information")
      }
      val rhsSlot = rhsSlots.get(name) match {
        case Some(slot) => slot
        case None       => throw new InternalException(s"Did not find `$name` in the pipeline information")
      }
      Ascending2(lhsSlot, rhsSlot)

    case plans.Descending(name) =>
      val lhsSlot = lhsSlots.get(name) match {
        case Some(slot) => slot
        case None       => throw new InternalException(s"Did not find `$name` in the pipeline information")
      }
      val rhsSlot = rhsSlots.get(name) match {
        case Some(slot) => slot
        case None       => throw new InternalException(s"Did not find `$name` in the pipeline information")
      }
      Descending2(lhsSlot, rhsSlot)
  }

  def partitionGroupingExpressions(
    expressionConverters: ExpressionConverters,
    groupingExpressions: Map[String, internal.expressions.Expression],
    orderToLeverage: Seq[internal.expressions.Expression],
    id: Id
  ): (GroupingExpression, GroupingExpression) = {
    val (orderedGroupingExpressions, unorderedGroupingExpressions) = groupingExpressions.partition { case (_, v) =>
      orderToLeverage.contains(v)
    }
    val orderedGroupingColumns =
      expressionConverters.toGroupingExpression(id, orderedGroupingExpressions, orderToLeverage)
    val unorderedGroupingColumns =
      expressionConverters.toGroupingExpression(id, unorderedGroupingExpressions, orderToLeverage)
    (orderedGroupingColumns, unorderedGroupingColumns)
  }

  def symbolsToSlots(symbols: Set[String], slotConfiguration: SlotConfiguration): Array[Slot] =
    symbols.map(k => slotConfiguration.get(k).get).toArray
}

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
package org.neo4j.cypher.internal

import org.neo4j.cypher.internal.NotificationWrapping.asKernelNotification
import org.neo4j.cypher.internal.cache.CypherQueryCaches
import org.neo4j.cypher.internal.cache.CypherQueryCaches.ExecutionPlanCacheKey
import org.neo4j.cypher.internal.compiler.phases.CachableLogicalPlanState
import org.neo4j.cypher.internal.frontend.PlannerName
import org.neo4j.cypher.internal.frontend.phases.CompilationPhaseTracer
import org.neo4j.cypher.internal.logical.plans.LogicalPlan
import org.neo4j.cypher.internal.logical.plans.LogicalPlanToPlanBuilderString
import org.neo4j.cypher.internal.logical.plans.ProcedureCall
import org.neo4j.cypher.internal.logical.plans.ProcedureDbmsAccess
import org.neo4j.cypher.internal.logical.plans.ProduceResult
import org.neo4j.cypher.internal.logical.plans.SchemaIndexLookupUsage
import org.neo4j.cypher.internal.logical.plans.SchemaLabelIndexUsage
import org.neo4j.cypher.internal.logical.plans.SchemaRelationshipIndexUsage
import org.neo4j.cypher.internal.macros.AssertMacros
import org.neo4j.cypher.internal.options.CypherExecutionMode
import org.neo4j.cypher.internal.plandescription.PlanDescriptionBuilder
import org.neo4j.cypher.internal.planner.spi.PlanningAttributes
import org.neo4j.cypher.internal.planner.spi.PlanningAttributes.EffectiveCardinalities
import org.neo4j.cypher.internal.planner.spi.PlanningAttributes.ProvidedOrders
import org.neo4j.cypher.internal.planning.CypherPlanner
import org.neo4j.cypher.internal.planning.ExceptionTranslatingQueryContext
import org.neo4j.cypher.internal.planning.LogicalPlanResult
import org.neo4j.cypher.internal.result.ClosingExecutionResult
import org.neo4j.cypher.internal.result.ExplainExecutionResult
import org.neo4j.cypher.internal.result.FailedExecutionResult
import org.neo4j.cypher.internal.result.InternalExecutionResult
import org.neo4j.cypher.internal.result.StandardInternalExecutionResult
import org.neo4j.cypher.internal.runtime.DBMS
import org.neo4j.cypher.internal.runtime.DBMS_READ
import org.neo4j.cypher.internal.runtime.ExplainMode
import org.neo4j.cypher.internal.runtime.InputDataStream
import org.neo4j.cypher.internal.runtime.InternalQueryType
import org.neo4j.cypher.internal.runtime.NormalMode
import org.neo4j.cypher.internal.runtime.ProfileMode
import org.neo4j.cypher.internal.runtime.QueryContext
import org.neo4j.cypher.internal.runtime.READ_ONLY
import org.neo4j.cypher.internal.runtime.READ_WRITE
import org.neo4j.cypher.internal.runtime.ResourceManager
import org.neo4j.cypher.internal.runtime.ResourceMonitor
import org.neo4j.cypher.internal.runtime.WRITE
import org.neo4j.cypher.internal.runtime.interpreted.TransactionBoundQueryContext
import org.neo4j.cypher.internal.runtime.interpreted.TransactionBoundQueryContext.IndexSearchMonitor
import org.neo4j.cypher.internal.runtime.interpreted.TransactionalContextWrapper
import org.neo4j.cypher.internal.util.InternalNotification
import org.neo4j.cypher.internal.util.TaskCloser
import org.neo4j.cypher.internal.util.attribution.SequentialIdGen
import org.neo4j.exceptions.InternalException
import org.neo4j.graphdb.ExecutionPlanDescription
import org.neo4j.graphdb.impl.notification.NotificationCodeWithDescription
import org.neo4j.kernel.api.query.CompilerInfo
import org.neo4j.kernel.api.query.LookupIndexUsage
import org.neo4j.kernel.api.query.QueryObfuscator
import org.neo4j.kernel.api.query.RelationshipTypeIndexUsage
import org.neo4j.kernel.api.query.SchemaIndexUsage
import org.neo4j.kernel.impl.query.NotificationConfiguration
import org.neo4j.kernel.impl.query.QueryExecution
import org.neo4j.kernel.impl.query.QueryExecutionMonitor
import org.neo4j.kernel.impl.query.QuerySubscriber
import org.neo4j.kernel.impl.query.TransactionalContext
import org.neo4j.monitoring.Monitors
import org.neo4j.values.virtual.MapValue

import java.util.function.Supplier

import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.CollectionHasAsScala
import scala.jdk.CollectionConverters.SeqHasAsJava

/**
 * Composite [[Compiler]], which uses a [[CypherPlanner]] and [[CypherRuntime]] to compile
 * a query into a [[ExecutableQuery]].
 *
 * @param planner the planner
 * @param runtime the runtime
 * @param contextManager the runtime context manager
 * @param kernelMonitors monitors support
 * @tparam CONTEXT type of runtime context used
 */
case class CypherCurrentCompiler[CONTEXT <: RuntimeContext](
  planner: CypherPlanner,
  runtime: CypherRuntime[CONTEXT],
  contextManager: RuntimeContextManager[CONTEXT],
  kernelMonitors: Monitors,
  queryCaches: CypherQueryCaches
) extends org.neo4j.cypher.internal.Compiler {

  /**
   * Compile [[InputQuery]] into [[ExecutableQuery]].
   *
   * @param query                   query to convert
   * @param tracer                  compilation tracer to which events of the compilation process are reported
   * @param transactionalContext    transactional context to use during compilation (in logical and physical planning)
   * @throws org.neo4j.exceptions.Neo4jException public cypher exceptions on compilation problems
   * @return a compiled and executable query
   */
  override def compile(
    query: InputQuery,
    tracer: CompilationPhaseTracer,
    transactionalContext: TransactionalContext,
    params: MapValue
  ): ExecutableQuery = {

    // we only pass in the runtime to be able to support checking against the correct CommandManagementRuntime
    val logicalPlanResult = query match {
      case fullyParsedQuery: FullyParsedQuery =>
        planner.plan(fullyParsedQuery, tracer, transactionalContext, params, runtime)
      case preParsedQuery: PreParsedQuery =>
        planner.parseAndPlan(preParsedQuery, tracer, transactionalContext, params, runtime)
    }

    AssertMacros.checkOnlyWhenAssertionsAreEnabled(
      logicalPlanResult.logicalPlanState.planningAttributes.hasEqualSizeAttributes,
      "All planning attributes should contain the same plans"
    )

    val planState = logicalPlanResult.logicalPlanState
    val logicalPlan = planState.logicalPlan
    val queryType = getQueryType(planState)
    val (executionPlan, attributes) =
      queryCaches.executionPlanCache.computeIfAbsent(
        cacheWhen = logicalPlanResult.shouldBeCached,
        key = ExecutionPlanCacheKey(query.options.runtimeCacheKey, logicalPlan, planState.planningAttributes.cacheKey),
        compute =
          computeExecutionPlan(query, transactionalContext, logicalPlanResult, planState, logicalPlan, queryType)
      )

    new CypherExecutableQuery(
      logicalPlan,
      queryType == READ_ONLY || queryType == DBMS_READ,
      attributes.effectiveCardinalities,
      logicalPlanResult.plannerContext.debugOptions.rawCardinalitiesEnabled,
      attributes.providedOrders,
      executionPlan,
      logicalPlanResult.notifications ++ query.notifications,
      logicalPlanResult.reusability,
      logicalPlanResult.paramNames.toArray,
      logicalPlanResult.extractedParams,
      buildCompilerInfo(logicalPlan, planState.plannerName, executionPlan.runtimeName),
      planState.plannerName,
      queryType,
      logicalPlanResult.shouldBeCached,
      contextManager.config.enableMonitors,
      logicalPlanResult.queryObfuscator,
      contextManager.config.renderPlanDescription
    )
  }

  private def computeExecutionPlan(
    query: InputQuery,
    transactionalContext: TransactionalContext,
    logicalPlanResult: LogicalPlanResult,
    planState: CachableLogicalPlanState,
    logicalPlan: LogicalPlan,
    queryType: InternalQueryType
  ): (ExecutionPlan, PlanningAttributes) = {
    val runtimeContext = contextManager.create(
      logicalPlanResult.plannerContext.planContext,
      transactionalContext.kernelTransaction().schemaRead(),
      logicalPlanResult.plannerContext.clock,
      logicalPlanResult.plannerContext.debugOptions,
      query.options.useCompiledExpressions,
      query.options.materializedEntitiesMode,
      query.options.queryOptions.operatorEngine,
      query.options.queryOptions.interpretedPipesFallback,
      planState.anonymousVariableNameGenerator
    )

    // Make copy, so per-runtime logical plan rewriting does not mutate cached attributes
    val planningAttributesCopy = planState.planningAttributes.createCopy()

    val logicalQuery = LogicalQuery(
      logicalPlan,
      planState.queryText,
      queryType == READ_ONLY || queryType == DBMS_READ,
      planState.returnColumns.toArray,
      planState.semanticTable,
      planningAttributesCopy.effectiveCardinalities,
      planningAttributesCopy.providedOrders,
      planningAttributesCopy.leveragedOrders,
      planState.hasLoadCSV,
      new SequentialIdGen(planningAttributesCopy.cardinalities.size),
      query.options.queryOptions.executionMode == CypherExecutionMode.profile
    )

    try {
      (runtime.compileToExecutable(logicalQuery, runtimeContext), planningAttributesCopy)
    } catch {
      case e: Exception =>
        // The logical plan is valuable information if we fail to create an executionPlan
        val lpStr = LogicalPlanToPlanBuilderString(logicalPlan)
        val planInfo = new InternalException("Failed with plan:\n" + lpStr)
        e.addSuppressed(planInfo)
        throw e
    }
  }

  private def buildCompilerInfo(logicalPlan: LogicalPlan, plannerName: PlannerName, runtimeName: RuntimeName) = {
    val schemaIndexUsage = ListBuffer.empty[SchemaIndexUsage]
    val relationshipTypeIndexUsage = ListBuffer.empty[RelationshipTypeIndexUsage]
    val lookupIndexUsage = ListBuffer.empty[LookupIndexUsage]

    logicalPlan.indexUsage().foreach {
      case SchemaLabelIndexUsage(identifier, labelId, label, propertyKeys) =>
        schemaIndexUsage.addOne(new SchemaIndexUsage(
          identifier,
          labelId,
          label,
          propertyKeys.map(_.nameId.id).toArray,
          propertyKeys.map(_.name): _*
        ))

      case SchemaRelationshipIndexUsage(identifier, relTypeId, relType, propertyKeys) =>
        relationshipTypeIndexUsage.addOne(new RelationshipTypeIndexUsage(
          identifier,
          relTypeId,
          relType,
          propertyKeys.map(_.nameId.id).toArray,
          propertyKeys.map(_.name).toArray
        ))

      case SchemaIndexLookupUsage(identifier, entityType) =>
        lookupIndexUsage.addOne(new LookupIndexUsage(identifier, entityType))
    }

    new CompilerInfo(
      plannerName.name,
      runtimeName.name,
      schemaIndexUsage.asJava,
      relationshipTypeIndexUsage.asJava,
      lookupIndexUsage.asJava
    )
  }

  private def getQueryType(planState: CachableLogicalPlanState): InternalQueryType = {
    // check system and procedure runtimes first, because if this is true solveds will be empty
    runtime match {
      case m: AdministrationCommandRuntime if m.isApplicableAdministrationCommand(planState.logicalPlan) =>
        DBMS
      case _ =>
        val procedureOrSchema = SchemaCommandRuntime.queryType(planState.logicalPlan)
        if (procedureOrSchema.isDefined) {
          procedureOrSchema.get
        } else if (planHasDBMSProcedure(planState.logicalPlan)) {
          if (planState.planningAttributes.solveds(planState.logicalPlan.id).readOnly) {
            DBMS_READ
          } else {
            DBMS
          }
        } else if (planState.planningAttributes.solveds(planState.logicalPlan.id).readOnly) {
          READ_ONLY
        } else if (columnNames(planState.logicalPlan).isEmpty) {
          WRITE
        } else {
          READ_WRITE
        }
    }
  }

  private def planHasDBMSProcedure(logicalPlan: LogicalPlan): Boolean =
    logicalPlan.folder.treeExists {
      case procCall: ProcedureCall if procCall.call.signature.accessMode == ProcedureDbmsAccess => true
    }

  private def columnNames(logicalPlan: LogicalPlan): Array[String] =
    logicalPlan match {
      case produceResult: ProduceResult => produceResult.columns.toArray

      case _ => Array()
    }

  protected class CypherExecutableQuery(
    logicalPlan: LogicalPlan,
    readOnly: Boolean,
    effectiveCardinalities: EffectiveCardinalities,
    rawCardinalitiesInPlanDescription: Boolean,
    providedOrders: ProvidedOrders,
    executionPlan: ExecutionPlan,
    planningNotifications: IndexedSeq[InternalNotification],
    reusabilityState: ReusabilityState,
    override val paramNames: Array[String],
    override val extractedParams: MapValue,
    override val compilerInfo: CompilerInfo,
    plannerName: PlannerName,
    internalQueryType: InternalQueryType,
    override val shouldBeCached: Boolean,
    enableMonitors: Boolean,
    override val queryObfuscator: QueryObfuscator,
    renderPlanDescription: Boolean
  ) extends ExecutableQuery {

    // Monitors are implemented via dynamic proxies which are slow compared to NOOP which is why we want to able to completely disable
    private val searchMonitor =
      if (enableMonitors) kernelMonitors.newMonitor(classOf[IndexSearchMonitor]) else IndexSearchMonitor.NOOP

    private val resourceMonitor =
      if (enableMonitors) kernelMonitors.newMonitor(classOf[ResourceMonitor]) else ResourceMonitor.NOOP

    private val planDescriptionBuilder =
      PlanDescriptionBuilder(
        executionPlan.rewrittenPlan.getOrElse(logicalPlan),
        plannerName,
        readOnly,
        effectiveCardinalities,
        rawCardinalitiesInPlanDescription,
        providedOrders,
        executionPlan,
        renderPlanDescription
      )

    private def getQueryContext(transactionalContext: TransactionalContext, taskCloser: TaskCloser) = {
      val resourceManager = executionPlan.threadSafeExecutionResources() match {
        case Some(resourceManagerFactory) => resourceManagerFactory(resourceMonitor)
        case None =>
          new ResourceManager(resourceMonitor, transactionalContext.kernelTransaction().memoryTracker())
      }
      val txContextWrapper = TransactionalContextWrapper(transactionalContext)
      val statement = transactionalContext.statement()
      statement.registerCloseableResource(resourceManager)
      taskCloser.addTask(_ => statement.unregisterCloseableResource(resourceManager))

      val ctx = new TransactionBoundQueryContext(txContextWrapper, resourceManager)(searchMonitor)
      new ExceptionTranslatingQueryContext(ctx)
    }

    override def notifications: IndexedSeq[InternalNotification] = planningNotifications

    override def execute(
      transactionalContext: TransactionalContext,
      isOutermostQuery: Boolean,
      queryOptions: QueryOptions,
      params: MapValue,
      prePopulateResults: Boolean,
      input: InputDataStream,
      queryMonitor: QueryExecutionMonitor,
      subscriber: QuerySubscriber
    ): QueryExecution = {

      val taskCloser = new TaskCloser
      val queryContext = getQueryContext(transactionalContext, taskCloser) // We create the QueryContext here
      if (isOutermostQuery) {
        taskCloser.addTask(success => {
          val context = queryContext.transactionalContext
          if (!success) {
            context.rollback()
          } else {
            context.close()
          }
        })
      }
      taskCloser.addTask(_ => queryContext.resources.close())
      try {
        innerExecute(
          transactionalContext,
          queryOptions,
          taskCloser,
          queryContext,
          params,
          prePopulateResults,
          input,
          queryMonitor,
          subscriber,
          isOutermostQuery
        )
      } catch {
        case e: Throwable =>
          QuerySubscriber.safelyOnError(subscriber, e)
          taskCloser.close(false)
          transactionalContext.rollback()
          new FailedExecutionResult(columnNames(logicalPlan), internalQueryType, subscriber)
      }
    }

    private def innerExecute(
      transactionalContext: TransactionalContext,
      queryOptions: QueryOptions,
      taskCloser: TaskCloser,
      queryContext: QueryContext,
      params: MapValue,
      prePopulateResults: Boolean,
      input: InputDataStream,
      queryMonitor: QueryExecutionMonitor,
      subscriber: QuerySubscriber,
      isOutermostQuery: Boolean
    ): InternalExecutionResult = {

      val innerExecutionMode = queryOptions.queryOptions.executionMode match {
        case CypherExecutionMode.explain => ExplainMode
        case CypherExecutionMode.profile => ProfileMode
        case CypherExecutionMode.default => NormalMode
      }

      val monitor = if (isOutermostQuery) queryMonitor else QueryExecutionMonitor.NO_OP
      monitor.startExecution(transactionalContext.executingQuery())

      val notificationConfig =
        transactionalContext.queryExecutingConfiguration().notificationFilters()
      val filteredPlannerNotifications = (planningNotifications ++ executionPlan.notifications)
        .map(asKernelNotification(Some(queryOptions.offset)))
        .filter(notificationConfig.includes(_))
      val inner =
        if (innerExecutionMode == ExplainMode) {
          taskCloser.close(success = true)
          val columns = columnNames(logicalPlan)

          new ExplainExecutionResult(
            columns,
            planDescriptionBuilder.explain(),
            internalQueryType,
            filteredPlannerNotifications.toSet,
            subscriber
          )
        } else {

          val runtimeResult =
            executionPlan.run(queryContext, innerExecutionMode, params, prePopulateResults, input, subscriber)

          val filteredRuntimeNotifications = runtimeResult.notifications().asScala
            .map(asKernelNotification(None))
            .filter(filterNotifications(_, notificationConfig))
          if (isOutermostQuery) {
            transactionalContext.executingQuery().onExecutionStarted(runtimeResult)
          }
          taskCloser.addTask(_ => runtimeResult.close())

          new StandardInternalExecutionResult(
            runtimeResult,
            taskCloser,
            internalQueryType,
            innerExecutionMode,
            planDescriptionBuilder,
            subscriber,
            filteredPlannerNotifications ++ filteredRuntimeNotifications
          )
        }

      ClosingExecutionResult.wrapAndInitiate(
        transactionalContext.executingQuery(),
        inner,
        monitor,
        subscriber
      )
    }

    private def filterNotifications(
      notification: NotificationCodeWithDescription#Notification,
      notificationConfig: NotificationConfiguration
    ): Boolean =
      notificationConfig.includes(notification)

    override def reusabilityState(lastCommittedTxId: () => Long, ctx: TransactionalContext): ReusabilityState =
      reusabilityState

    override def planDescriptionSupplier(): Supplier[ExecutionPlanDescription] = {
      val builder = planDescriptionBuilder
      () => builder.explain()
    }
  }

  /**
   * Clear the caches of this caching compiler.
   *
   * @return the number of entries that were cleared in any cache
   */
  def clearCaches(): Long = {
    // TODO: global clear on queryCaches?
    Math.max(planner.clearCaches(), queryCaches.executionPlanCache.clear())
  }

  def clearExecutionPlanCache(): Unit = queryCaches.executionPlanCache.clear()
}

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
package org.neo4j.cypher.internal.frontend.phases

import org.neo4j.cypher.internal.frontend.phases.CompilationPhaseTracer.CompilationPhase.AST_REWRITE
import org.neo4j.cypher.internal.rewriting.conditions.noReferenceEqualityAmongVariables
import org.neo4j.cypher.internal.util.StepSequencer
import org.neo4j.cypher.internal.util.symbols.ParameterTypeInfo

/**
 * Normalize the AST into a form easier for the planner to work with.
 */
case class AstRewriting(parameterTypeMapping: Map[String, ParameterTypeInfo] = Map.empty)
    extends Phase[BaseContext, BaseState, BaseState] {

  override def process(in: BaseState, context: BaseContext): BaseState = {
    val rewrittenStatement = ASTRewriter.rewrite(
      in.statement(),
      in.semantics(),
      parameterTypeMapping,
      context.cypherExceptionFactory,
      in.anonymousVariableNameGenerator
    )
    in.withStatement(rewrittenStatement)
  }

  override def phase = AST_REWRITE

  override def postConditions: Set[StepSequencer.Condition] = {
    // noReferenceEqualityAmongVariables is broken by later phases, e.g. Namespacer.
    // This can be fixed in a subsequent investigation.
    (ASTRewriter.postConditions - noReferenceEqualityAmongVariables)
      .map(StatementCondition.wrap)
  }
}

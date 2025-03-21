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
package org.neo4j.procedure.impl;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.neo4j.collection.RawIterator;
import org.neo4j.function.ThrowingFunction;
import org.neo4j.internal.kernel.api.exceptions.ProcedureException;
import org.neo4j.internal.kernel.api.procs.Neo4jTypes;
import org.neo4j.internal.kernel.api.procs.ProcedureHandle;
import org.neo4j.internal.kernel.api.procs.ProcedureSignature;
import org.neo4j.internal.kernel.api.procs.QualifiedName;
import org.neo4j.internal.kernel.api.procs.UserAggregationReducer;
import org.neo4j.internal.kernel.api.procs.UserFunctionHandle;
import org.neo4j.internal.kernel.api.procs.UserFunctionSignature;
import org.neo4j.kernel.api.ResourceMonitor;
import org.neo4j.kernel.api.procedure.CallableProcedure;
import org.neo4j.kernel.api.procedure.CallableUserAggregationFunction;
import org.neo4j.kernel.api.procedure.CallableUserFunction;
import org.neo4j.kernel.api.procedure.Context;
import org.neo4j.kernel.api.procedure.GlobalProcedures;
import org.neo4j.kernel.lifecycle.LifecycleAdapter;
import org.neo4j.logging.InternalLog;
import org.neo4j.logging.NullLog;
import org.neo4j.procedure.builtin.SpecialBuiltInProcedures;
import org.neo4j.util.VisibleForTesting;
import org.neo4j.values.AnyValue;

/**
 * This is the coordinating service for procedures in the DBMS. It loads procedures from a specified
 * directory at startup, but also allows programmatic registration of them - and then, of course, allows
 * invoking procedures.
 */
public class GlobalProceduresRegistry extends LifecycleAdapter implements GlobalProcedures {
    private final ProcedureRegistry registry = new ProcedureRegistry();
    private final TypeCheckers typeCheckers;
    private final ComponentRegistry safeComponents = new ComponentRegistry();
    private final ComponentRegistry allComponents = new ComponentRegistry();
    private final ProcedureCompiler compiler;
    private final Supplier<List<CallableProcedure>> builtin;
    private final Path proceduresDirectory;
    private final InternalLog log;

    @VisibleForTesting
    public GlobalProceduresRegistry() {
        this(SpecialBuiltInProcedures.from("N/A", "N/A"), null, NullLog.getInstance(), ProcedureConfig.DEFAULT);
    }

    public GlobalProceduresRegistry(
            Supplier<List<CallableProcedure>> builtin,
            Path proceduresDirectory,
            InternalLog log,
            ProcedureConfig config) {
        this.builtin = builtin;
        this.proceduresDirectory = proceduresDirectory;
        this.log = log;
        this.typeCheckers = new TypeCheckers();
        this.compiler = new ProcedureCompiler(typeCheckers, safeComponents, allComponents, log, config);
    }

    /**
     * Register a new procedure. This method must not be called concurrently with {@link #procedure(QualifiedName)}.
     * @param proc the procedure.
     */
    @Override
    public void register(CallableProcedure proc) throws ProcedureException {
        register(proc, false);
    }

    /**
     * Register a new function. This method must not be called concurrently with {@link #procedure(QualifiedName)}.
     * @param function the function.
     */
    @Override
    public void register(CallableUserFunction function) throws ProcedureException {
        register(function, false);
    }

    /**
     * Register a new function. This method must not be called concurrently with {@link #procedure(QualifiedName)}.
     * @param function the function.
     */
    @Override
    public void register(CallableUserAggregationFunction function) throws ProcedureException {
        register(function, false);
    }

    /**
     * Register a new function. This method must not be called concurrently with {@link #function(QualifiedName)}.
     * @param function the function.
     */
    @Override
    public void register(CallableUserFunction function, boolean overrideCurrentImplementation)
            throws ProcedureException {
        registry.register(function, overrideCurrentImplementation, false);
    }

    /**
     * Register a new built in function. This method must not be called concurrently with {@link #function(QualifiedName)}.
     * @param function the function.
     */
    @Override
    public void registerBuiltIn(CallableUserFunction function) throws ProcedureException {
        registry.register(function, false, true);
    }

    /**
     * Register a new aggregation function. This method must not be called concurrently with {@link #aggregationFunction(QualifiedName)}.
     * @param function the function.
     */
    @Override
    public void register(CallableUserAggregationFunction function, boolean overrideCurrentImplementation)
            throws ProcedureException {
        registry.register(function, overrideCurrentImplementation, false);
    }

    /**
     * Register a new procedure. This method must not be called concurrently with {@link #procedure(QualifiedName)}.
     * @param proc the procedure.
     */
    @Override
    public void register(CallableProcedure proc, boolean overrideCurrentImplementation) throws ProcedureException {
        registry.register(proc, overrideCurrentImplementation);
    }

    /**
     * Register a new internal procedure defined with annotations on a java class.
     * @param proc the procedure class
     */
    @Override
    public void registerProcedure(Class<?> proc) throws ProcedureException {
        registerProcedure(proc, false);
    }

    /**
     * Register a new internal procedure defined with annotations on a java class.
     * @param proc the procedure class
     * @param overrideCurrentImplementation set to true if procedures within this class should override older procedures with the same name
     */
    @Override
    public void registerProcedure(Class<?> proc, boolean overrideCurrentImplementation) throws ProcedureException {
        for (CallableProcedure procedure : compiler.compileProcedure(proc, true)) {
            register(procedure, overrideCurrentImplementation);
        }
    }

    /**
     * Register a new function defined with annotations on a java class.
     * @param func the function class
     */
    @Override
    public void registerBuiltInFunctions(Class<?> func) throws ProcedureException {
        for (CallableUserFunction function :
                compiler.withoutNamingRestrictions().compileFunction(func, true)) {
            register(function, false);
        }
    }

    /**
     * Register a new function defined with annotations on a java class.
     * @param func the function class
     */
    @Override
    public void registerFunction(Class<?> func) throws ProcedureException {
        registerFunction(func, false);
    }

    /**
     * Register a new aggregation function defined with annotations on a java class.
     * @param func the function class
     */
    @Override
    public void registerAggregationFunction(Class<?> func, boolean overrideCurrentImplementation)
            throws ProcedureException {
        for (CallableUserAggregationFunction function : compiler.compileAggregationFunction(func)) {
            register(function, overrideCurrentImplementation);
        }
    }

    /**
     * Register a new aggregation function defined with annotations on a java class.
     * @param func the function class
     */
    @Override
    public void registerAggregationFunction(Class<?> func) throws ProcedureException {
        registerAggregationFunction(func, false);
    }

    /**
     * Register a new function defined with annotations on a java class.
     * @param func the function class
     */
    @Override
    public void registerFunction(Class<?> func, boolean overrideCurrentImplementation) throws ProcedureException {
        for (CallableUserFunction function : compiler.compileFunction(func, false)) {
            register(function, overrideCurrentImplementation);
        }
    }

    /**
     * Registers a type and its mapping to Neo4jTypes
     *
     * @param javaClass
     *         the class of the native type
     * @param type
     *         the mapping to Neo4jTypes
     */
    @Override
    public void registerType(Class<?> javaClass, Neo4jTypes.AnyType type) {
        typeCheckers.registerType(javaClass, new TypeCheckers.DefaultValueConverter(type));
    }

    /**
     * Registers a component, these become available in reflective procedures for injection.
     * @param cls the type of component to be registered (this is what users 'ask' for in their field declaration)
     * @param provider a function that supplies the component, given the context of a procedure invocation
     * @param safe set to false if this component can bypass security, true if it respects security
     */
    @Override
    public <T> void registerComponent(
            Class<T> cls, ThrowingFunction<Context, T, ProcedureException> provider, boolean safe) {
        if (safe) {
            safeComponents.register(cls, provider);
        }
        allComponents.register(cls, provider);
    }

    /**
     * Lookup registered component providers functions that capable to provide user requested type in scope of procedure invocation context
     * @param cls the type of registered component
     * @param safe set to false if desired component can bypass security, true if it respects security
     * @return registered provider function if registered, null otherwise
     */
    @Override
    public <T> ThrowingFunction<Context, T, ProcedureException> lookupComponentProvider(Class<T> cls, boolean safe) {
        var registry = safe ? safeComponents : allComponents;
        return registry.providerFor(cls);
    }

    @Override
    public ProcedureHandle procedure(QualifiedName name) throws ProcedureException {
        return registry.procedure(name);
    }

    @Override
    public UserFunctionHandle function(QualifiedName name) {
        return registry.function(name);
    }

    @Override
    public UserFunctionHandle aggregationFunction(QualifiedName name) {
        return registry.aggregationFunction(name);
    }

    @Override
    public int[] getIdsOfFunctionsMatching(Predicate<CallableUserFunction> predicate) {
        return registry.getIdsOfFunctionsMatching(predicate);
    }

    @Override
    public int[] getIdsOfAggregatingFunctionsMatching(Predicate<CallableUserAggregationFunction> predicate) {
        return registry.getIdsOfAggregatingFunctionsMatching(predicate);
    }

    @Override
    public Set<ProcedureSignature> getAllProcedures() {
        return registry.getAllProcedures();
    }

    @Override
    public int[] getIdsOfProceduresMatching(Predicate<CallableProcedure> predicate) {
        return registry.getIdsOfProceduresMatching(predicate);
    }

    @Override
    public Stream<UserFunctionSignature> getAllNonAggregatingFunctions() {
        return registry.getAllNonAggregatingFunctions();
    }

    @Override
    public Stream<UserFunctionSignature> getAllAggregatingFunctions() {
        return registry.getAllAggregatingFunctions();
    }

    @Override
    public RawIterator<AnyValue[], ProcedureException> callProcedure(
            Context ctx, int id, AnyValue[] input, ResourceMonitor resourceMonitor) throws ProcedureException {
        return registry.callProcedure(ctx, id, input, resourceMonitor);
    }

    @Override
    public AnyValue callFunction(Context ctx, int id, AnyValue[] input) throws ProcedureException {
        return registry.callFunction(ctx, id, input);
    }

    @Override
    public UserAggregationReducer createAggregationFunction(Context ctx, int id) throws ProcedureException {
        return registry.createAggregationFunction(ctx, id);
    }

    @Override
    public void start() throws Exception {
        ProcedureJarLoader loader = new ProcedureJarLoader(compiler, log);
        ProcedureJarLoader.Callables callables = loader.loadProceduresFromDir(proceduresDirectory);
        for (CallableProcedure procedure : callables.procedures()) {
            register(procedure);
        }

        for (CallableUserFunction function : callables.functions()) {
            register(function);
        }

        for (CallableUserAggregationFunction function : callables.aggregationFunctions()) {
            register(function);
        }

        // And register built-in procedures
        for (var procedure : builtin.get()) {
            register(procedure);
        }
    }

    @VisibleForTesting
    @Override
    public void unregister(QualifiedName name) {
        registry.unregister(name);
    }
}

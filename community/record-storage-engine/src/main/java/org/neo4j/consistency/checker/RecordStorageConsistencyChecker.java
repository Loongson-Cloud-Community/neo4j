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
package org.neo4j.consistency.checker;

import static org.neo4j.configuration.GraphDatabaseInternalSettings.consistency_checker_fail_fast_threshold;
import static org.neo4j.consistency.checker.ParallelExecution.DEFAULT_IDS_PER_CHUNK;
import static org.neo4j.consistency.checker.SchemaChecker.moreDescriptiveRecordToStrings;
import static org.neo4j.internal.helpers.collection.Iterators.resourceIterator;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.collections.api.map.primitive.MutableIntObjectMap;
import org.eclipse.collections.api.set.primitive.MutableIntSet;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.neo4j.annotations.documented.ReporterFactory;
import org.neo4j.configuration.Config;
import org.neo4j.consistency.RecordType;
import org.neo4j.consistency.checking.ConsistencyCheckIncompleteException;
import org.neo4j.consistency.checking.ConsistencyFlags;
import org.neo4j.consistency.checking.cache.CacheAccess;
import org.neo4j.consistency.checking.cache.CacheSlots;
import org.neo4j.consistency.checking.cache.DefaultCacheAccess;
import org.neo4j.consistency.checking.index.IndexAccessors;
import org.neo4j.consistency.checking.index.IndexAccessors.IndexAccessorLookup;
import org.neo4j.consistency.checking.index.IndexDescriptorProvider;
import org.neo4j.consistency.report.ConsistencyReporter;
import org.neo4j.consistency.report.ConsistencySummaryStatistics;
import org.neo4j.consistency.report.InconsistencyMessageLogger;
import org.neo4j.consistency.report.InconsistencyReport;
import org.neo4j.consistency.statistics.Counts;
import org.neo4j.counts.CountsAccessor;
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.index.internal.gbptree.RecoveryCleanupWorkCollector;
import org.neo4j.internal.batchimport.cache.ByteArray;
import org.neo4j.internal.counts.CountsBuilder;
import org.neo4j.internal.counts.GBPTreeCountsStore;
import org.neo4j.internal.counts.GBPTreeGenericCountsStore;
import org.neo4j.internal.counts.GBPTreeRelationshipGroupDegreesStore;
import org.neo4j.internal.counts.Updater;
import org.neo4j.internal.helpers.collection.LongRange;
import org.neo4j.internal.helpers.progress.ProgressListener;
import org.neo4j.internal.helpers.progress.ProgressMonitorFactory;
import org.neo4j.internal.id.IdGenerator;
import org.neo4j.internal.id.IdGeneratorFactory;
import org.neo4j.internal.recordstorage.RecordStorageEngine;
import org.neo4j.internal.recordstorage.RecordStorageIndexingBehaviour;
import org.neo4j.internal.recordstorage.SchemaRuleAccess;
import org.neo4j.internal.schema.IndexDescriptor;
import org.neo4j.io.IOUtils;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.layout.recordstorage.RecordDatabaseLayout;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.io.pagecache.context.CursorContext;
import org.neo4j.io.pagecache.context.CursorContextFactory;
import org.neo4j.io.pagecache.tracing.PageCacheTracer;
import org.neo4j.kernel.api.index.IndexAccessor;
import org.neo4j.kernel.impl.api.index.IndexProviderMap;
import org.neo4j.kernel.impl.api.index.IndexSamplingConfig;
import org.neo4j.kernel.impl.index.schema.ConsistencyCheckable;
import org.neo4j.kernel.impl.store.NeoStores;
import org.neo4j.kernel.impl.store.cursor.CachedStoreCursors;
import org.neo4j.logging.InternalLog;
import org.neo4j.logging.NullLogProvider;
import org.neo4j.memory.MemoryTracker;
import org.neo4j.token.DelegatingTokenHolder;
import org.neo4j.token.ReadOnlyTokenCreator;
import org.neo4j.token.TokenHolders;
import org.neo4j.token.api.TokenHolder;

/**
 * A consistency checker for a {@link RecordStorageEngine}, focused on keeping abstractions to a minimum and having clean and understandable
 * algorithms. Revolves around the {@link CacheAccess} which uses up {@code 11 * NodeStore#getHighId()}. The checking is split up into a couple
 * of checkers, mostly focused on a single store, e.g. nodes or relationships, which are run one after the other. Each checker's algorithm is
 * designed to try to maximize both CPU and I/O to its full extent, or rather at least maxing out one of them.
 */
public class RecordStorageConsistencyChecker implements AutoCloseable {
    private static final String ID_GEN_CONSISTENCY_CHECKER_TAG = "idGeneratorConsistencyChecker";
    private static final String INDEX_STRUCTURE_CONSISTENCY_CHECKER_TAG = "indexStructureConsistencyChecker";
    private static final String COUNT_STORE_CONSISTENCY_CHECKER_TAG = "countStoreConsistencyChecker";
    private static final String REL_GROUP_STORE_CONSISTENCY_CHECKER_TAG = "relGroupStoreConsistencyChecker";
    private static final String SCHEMA_CONSISTENCY_CHECKER_TAG = "schemaConsistencyChecker";
    private static final String CONSISTENCY_CHECKER_TOKEN_LOADER_TAG = "consistencyCheckerTokenLoader";
    static final int[] DEFAULT_SLOT_SIZES = {
        CacheSlots.ID_SLOT_SIZE, CacheSlots.ID_SLOT_SIZE, 1, 1, 1, 1, 1, 1 /*2 bits unused*/
    };

    private final FileSystemAbstraction fileSystem;
    private final RecordDatabaseLayout databaseLayout;
    private final PageCache pageCache;
    private final NeoStores neoStores;
    private final IdGeneratorFactory idGeneratorFactory;
    private final ConsistencySummaryStatistics summary;
    private final ProgressMonitorFactory progressFactory;
    private final InternalLog log;
    private final ConsistencyFlags consistencyFlags;
    private final CursorContextFactory contextFactory;
    private final PageCacheTracer cacheTracer;
    private final CacheAccess cacheAccess;
    private final ConsistencyReporter reporter;
    private final CountsState observedCounts;
    private final EntityBasedMemoryLimiter limiter;
    private final CheckerContext context;
    private final ProgressMonitorFactory.MultiPartBuilder progress;
    private final IndexAccessors indexAccessors;
    private final InconsistencyReport report;
    private final ByteArray cacheAccessMemory;

    public RecordStorageConsistencyChecker(
            FileSystemAbstraction fileSystem,
            RecordDatabaseLayout databaseLayout,
            PageCache pageCache,
            NeoStores neoStores,
            IndexProviderMap indexProviders,
            IndexAccessorLookup accessorLookup,
            IdGeneratorFactory idGeneratorFactory,
            ConsistencySummaryStatistics summary,
            ProgressMonitorFactory progressFactory,
            Config config,
            int numberOfThreads,
            InternalLog log,
            boolean verbose,
            ConsistencyFlags consistencyFlags,
            EntityBasedMemoryLimiter.Factory memoryLimit,
            MemoryTracker memoryTracker,
            CursorContextFactory contextFactory,
            PageCacheTracer cacheTracer) {
        this.fileSystem = fileSystem;
        this.databaseLayout = databaseLayout;
        this.pageCache = pageCache;
        this.neoStores = neoStores;
        this.idGeneratorFactory = idGeneratorFactory;
        this.summary = summary;
        this.progressFactory = progressFactory;
        this.log = log;
        this.consistencyFlags = consistencyFlags;
        this.contextFactory = contextFactory;
        this.cacheTracer = cacheTracer;
        int stopCountThreshold = config.get(consistency_checker_fail_fast_threshold);
        AtomicInteger stopCount = new AtomicInteger(0);
        ConsistencyReporter.Monitor monitor = ConsistencyReporter.NO_MONITOR;
        if (stopCountThreshold > 0) {
            monitor = (ignoredArg1, ignoredArg2, ignoredArg3, isError) -> {
                if (isError && !isCancelled() && stopCount.incrementAndGet() >= stopCountThreshold) {
                    cancel("Observed " + stopCount.get() + " inconsistencies.");
                }
            };
        }
        TokenHolders tokenHolders = safeLoadTokens(neoStores, contextFactory);
        this.report = new InconsistencyReport(
                new InconsistencyMessageLogger(log, moreDescriptiveRecordToStrings(neoStores, tokenHolders)), summary);
        this.reporter = new ConsistencyReporter(report, monitor);
        ParallelExecution execution = new ParallelExecution(
                numberOfThreads,
                exception -> cancel("Unexpected exception"), // Exceptions should interrupt all threads to exit faster
                DEFAULT_IDS_PER_CHUNK);
        RecordLoading recordLoading = new RecordLoading(neoStores);
        this.limiter = instantiateMemoryLimiter(memoryLimit);
        this.cacheAccessMemory = DefaultCacheAccess.defaultByteArray(limiter.rangeSize(), memoryTracker);
        this.cacheAccess = new DefaultCacheAccess(cacheAccessMemory, Counts.NONE, numberOfThreads);
        this.observedCounts = new CountsState(neoStores, cacheAccess, memoryTracker);
        this.progress = progressFactory.multipleParts("Consistency check");
        this.indexAccessors = instantiateIndexAccessors(neoStores, indexProviders, tokenHolders, config);
        this.context = new CheckerContext(
                neoStores,
                indexAccessors,
                execution,
                reporter,
                cacheAccess,
                tokenHolders,
                recordLoading,
                observedCounts,
                limiter,
                progress,
                pageCache,
                memoryTracker,
                log,
                verbose,
                consistencyFlags,
                contextFactory);
    }

    private IndexAccessors instantiateIndexAccessors(
            NeoStores neoStores, IndexProviderMap indexProviders, TokenHolders tokenHolders, Config config) {
        SchemaRuleAccess schemaRuleAccess =
                SchemaRuleAccess.getSchemaRuleAccess(neoStores.getSchemaStore(), tokenHolders);
        return new IndexAccessors(
                indexProviders,
                new SchemaRulesDescriptors(neoStores, schemaRuleAccess),
                new IndexSamplingConfig(config),
                tokenHolders,
                contextFactory,
                neoStores.getOpenOptions(),
                new RecordStorageIndexingBehaviour());
    }

    public void check() throws ConsistencyCheckIncompleteException {
        if (consistencyFlags.checkPropertyOwners()
                && progressFactory != ProgressMonitorFactory.NONE
                && context.isVerbose()) {
            System.err.println("The consistency checker has been configured to check property ownership. "
                    + "This feature is currently unavailable for this database format. "
                    + "The check will continue as if it were disabled.");
        }

        assert !context.isCancelled();
        try {
            consistencyCheckIdGenerator();
            consistencyCheckIndexes();

            context.initialize();
            // Starting by loading all tokens from store into the TokenHolders, loaded in a safe way of course
            // Check schema - constraints and indexes, that sort of thing
            // This is done before instantiating the other checker instances because the schema checker will also
            // populate maps regarding mandatory properties which the node/relationship checkers uses
            SchemaChecker schemaChecker = new SchemaChecker(context);
            MutableIntObjectMap<MutableIntSet> mandatoryNodeProperties = new IntObjectHashMap<>();
            MutableIntObjectMap<MutableIntSet> mandatoryRelationshipProperties = new IntObjectHashMap<>();
            try (var cursorContext = contextFactory.create(SCHEMA_CONSISTENCY_CHECKER_TAG);
                    var storeCursors = new CachedStoreCursors(context.neoStores, cursorContext)) {
                schemaChecker.check(
                        mandatoryNodeProperties, mandatoryRelationshipProperties, cursorContext, storeCursors);
            }

            // Some pieces of check logic are extracted from this main class to reduce the size of this class.
            // Instantiate those here first
            NodeChecker nodeChecker = new NodeChecker(context, mandatoryNodeProperties);
            NodeIndexChecker indexChecker = new NodeIndexChecker(context);
            RelationshipIndexChecker relationshipIndexChecker = new RelationshipIndexChecker(context);
            RelationshipChecker relationshipChecker = new RelationshipChecker(context, mandatoryRelationshipProperties);
            RelationshipGroupChecker relationshipGroupChecker = new RelationshipGroupChecker(context);
            RelationshipChainChecker relationshipChainChecker = new RelationshipChainChecker(context);
            ProgressMonitorFactory.Completer progressCompleter = progress.build();

            int numberOfRanges = limiter.numberOfRanges();
            for (int i = 1; limiter.hasNext(); i++) {
                if (isCancelled()) {
                    break;
                }

                EntityBasedMemoryLimiter.CheckRange range = limiter.next();
                if (numberOfRanges > 1) {
                    context.debug("=== Checking range %d/%d (%s) ===", i, numberOfRanges, range);
                }
                context.initializeRange();

                // Tell the cache that the pivot node id is the low end of this range. This will make all interactions
                // with the cache
                // take that into consideration when working with offset arrays where the index is based on node ids.
                cacheAccess.setPivotId(range.from());

                if (range.applicableForRelationshipBasedChecks()) {
                    LongRange relationshipRange = range.getRelationshipRange();
                    context.runIfAllowed(relationshipIndexChecker, relationshipRange);
                    // We don't clear the cache here since it will be cleared before it is used again:
                    // either in NodeIndexChecker, explicitly a few rows down before NodeChecker, or in next range of
                    // RelationshipIndexChecker.
                }

                if (range.applicableForNodeBasedChecks()) {
                    LongRange nodeRange = range.getNodeRange();
                    // Go into a node-centric mode where the nodes themselves are checked and somewhat cached off-heap.
                    // Then while we have the nodes loaded in cache do all other checking that has anything to do with
                    // nodes
                    // so that the "other" store can be checked sequentially and the random node lookups will be cheap
                    context.runIfAllowed(indexChecker, nodeRange);
                    cacheAccess.setCacheSlotSizesAndClear(DEFAULT_SLOT_SIZES);
                    context.runIfAllowed(nodeChecker, nodeRange);
                    context.runIfAllowed(relationshipGroupChecker, nodeRange);
                    context.runIfAllowed(relationshipChecker, nodeRange);
                    context.runIfAllowed(relationshipChainChecker, nodeRange);
                }
            }

            if (!isCancelled()) {
                // All counts we've observed while doing other checking along the way we compare against the counts
                // store here
                checkCounts();
                checkRelationshipGroupDegressStore();
            }
            progressCompleter.close();
        } catch (Exception e) {
            cancel("ConsistencyChecker failed unexpectedly");
            throw new ConsistencyCheckIncompleteException(e);
        }
    }

    private void consistencyCheckIdGenerator() {
        if (!consistencyFlags.checkStructure()) {
            return;
        }

        List<IdGenerator> idGenerators = new ArrayList<>();
        idGeneratorFactory.visit(idGenerators::add);

        try (var cursorContext = contextFactory.create(ID_GEN_CONSISTENCY_CHECKER_TAG)) {
            ProgressListener progressListener =
                    progressFactory.singlePart("ID Generator consistency check", idGenerators.size());

            for (IdGenerator idGenerator : idGenerators) {
                consistencyCheckSingleCheckable(report, progressListener, idGenerator, RecordType.ID_STORE);
            }
        }
    }

    private void consistencyCheckIndexes() {
        if (!(consistencyFlags.checkIndexes() && consistencyFlags.checkStructure())) {
            return;
        }

        try (var cursorContext = contextFactory.create(INDEX_STRUCTURE_CONSISTENCY_CHECKER_TAG)) {
            ProgressListener progressListener = progressFactory.singlePart(
                    "Index structure consistency check",
                    indexAccessors.onlineRules().size()
                            + ((indexAccessors.nodeLabelIndex() != null) ? 1 : 0)
                            + ((indexAccessors.relationshipTypeIndex() != null) ? 1 : 0));

            if (indexAccessors.nodeLabelIndex() != null) {
                consistencyCheckSingleCheckable(
                        report, progressListener, indexAccessors.nodeLabelIndex(), RecordType.LABEL_SCAN_DOCUMENT);
            }
            if (indexAccessors.relationshipTypeIndex() != null) {
                consistencyCheckSingleCheckable(
                        report,
                        progressListener,
                        indexAccessors.relationshipTypeIndex(),
                        RecordType.RELATIONSHIP_TYPE_SCAN_DOCUMENT);
            }

            List<IndexDescriptor> rulesToRemove = new ArrayList<>();
            for (IndexDescriptor onlineRule : indexAccessors.onlineRules()) {
                ConsistencyReporter.FormattingDocumentedHandler handler =
                        ConsistencyReporter.formattingHandler(report, RecordType.INDEX);
                ReporterFactory reporterFactory = new ReporterFactory(handler);
                IndexAccessor accessor = indexAccessors.accessorFor(onlineRule);
                if (!accessor.consistencyCheck(
                        reporterFactory, contextFactory, context.execution.getNumberOfThreads())) {
                    rulesToRemove.add(onlineRule);
                }
                handler.updateSummary();
                progressListener.add(1);
            }
            for (IndexDescriptor toRemove : rulesToRemove) {
                indexAccessors.remove(toRemove);
            }
        }
    }

    private EntityBasedMemoryLimiter instantiateMemoryLimiter(EntityBasedMemoryLimiter.Factory memoryLimit) {
        // The checker makes use of a large memory array to hold data per node. For large stores there may not be enough
        // memory
        // to hold all node data and in that case the checking will happen iteratively where one part of the node store
        // is selected
        // and checked and all other stores related to any of those nodes too. When that part is done the next part of
        // the node store
        // is selected until all the nodes, e.g. all the data have been checked.

        long nodeCount = neoStores.getNodeStore().getHighId();
        long relationshipCount = neoStores.getRelationshipStore().getHighId();
        return memoryLimit.create(nodeCount, relationshipCount);
    }

    @Override
    public void close() {
        context.cancel();
        IOUtils.closeAllUnchecked(observedCounts, indexAccessors, cacheAccessMemory);
    }

    private void checkCounts() {
        if (!consistencyFlags.checkCounts()) {
            return;
        }

        try (var cursorContext = contextFactory.create(COUNT_STORE_CONSISTENCY_CHECKER_TAG);
                var countsStore = new GBPTreeCountsStore(
                        pageCache,
                        databaseLayout.countStore(),
                        fileSystem,
                        RecoveryCleanupWorkCollector.ignore(),
                        new CountsBuilder() {
                            @Override
                            public void initialize(
                                    CountsAccessor.Updater updater,
                                    CursorContext cursorContext,
                                    MemoryTracker memoryTracker) {
                                throw new UnsupportedOperationException(
                                        "Counts store needed rebuild, consistency checker will instead report broken or missing store");
                            }

                            @Override
                            public long lastCommittedTxId() {
                                return neoStores.getMetaDataStore().getLastCommittedTransactionId();
                            }
                        },
                        true,
                        GBPTreeCountsStore.NO_MONITOR,
                        databaseLayout.getDatabaseName(),
                        100,
                        NullLogProvider.getInstance(),
                        contextFactory,
                        cacheTracer,
                        neoStores.getOpenOptions());
                var checker = observedCounts.checker(reporter)) {
            if (consistencyFlags.checkStructure()) {
                consistencyCheckSingleCheckable(report, ProgressListener.NONE, countsStore, RecordType.COUNTS);
            }
            countsStore.accept(checker, cursorContext);
        } catch (Exception e) {
            log.error("Counts store is missing, broken or of an older format and will not be consistency checked", e);
            summary.genericError("Counts store is missing, broken or of an older format");
        }
    }

    private void checkRelationshipGroupDegressStore() {
        if (!consistencyFlags.checkCounts() || !consistencyFlags.checkStructure()) {
            return;
        }

        try (var relationshipGroupDegrees = new GBPTreeRelationshipGroupDegreesStore(
                pageCache,
                databaseLayout.relationshipGroupDegreesStore(),
                fileSystem,
                RecoveryCleanupWorkCollector.ignore(),
                new GBPTreeRelationshipGroupDegreesStore.DegreesRebuilder() {
                    @Override
                    public void rebuild(Updater updater, CursorContext cursorContext, MemoryTracker memoryTracker) {
                        throw new UnsupportedOperationException(
                                "Counts store needed rebuild, consistency checker will instead report broken or missing store");
                    }

                    @Override
                    public long lastCommittedTxId() {
                        return neoStores.getMetaDataStore().getLastCommittedTransactionId();
                    }
                },
                true,
                GBPTreeGenericCountsStore.NO_MONITOR,
                databaseLayout.getDatabaseName(),
                100,
                NullLogProvider.getInstance(),
                contextFactory,
                cacheTracer,
                neoStores.getOpenOptions())) {
            consistencyCheckSingleCheckable(
                    report, ProgressListener.NONE, relationshipGroupDegrees, RecordType.RELATIONSHIP_GROUP);
        } catch (Exception e) {
            log.error(
                    "Relationship group degrees is missing, broken or of an older format and will not be consistency checked",
                    e);
            summary.genericError("Relationship group degrees store is missing, broken or of an older format");
        }
    }

    private static TokenHolders safeLoadTokens(NeoStores neoStores, CursorContextFactory contextFactory) {
        TokenHolders tokenHolders = new TokenHolders(
                new DelegatingTokenHolder(new ReadOnlyTokenCreator(), TokenHolder.TYPE_PROPERTY_KEY),
                new DelegatingTokenHolder(new ReadOnlyTokenCreator(), TokenHolder.TYPE_LABEL),
                new DelegatingTokenHolder(new ReadOnlyTokenCreator(), TokenHolder.TYPE_RELATIONSHIP_TYPE));
        try (var cursorContext = contextFactory.create(CONSISTENCY_CHECKER_TOKEN_LOADER_TAG)) {
            tokenHolders
                    .relationshipTypeTokens()
                    .setInitialTokens(
                            RecordLoading.safeLoadTokens(neoStores.getRelationshipTypeTokenStore(), cursorContext));
            tokenHolders
                    .labelTokens()
                    .setInitialTokens(RecordLoading.safeLoadTokens(neoStores.getLabelTokenStore(), cursorContext));
            tokenHolders
                    .propertyKeyTokens()
                    .setInitialTokens(
                            RecordLoading.safeLoadTokens(neoStores.getPropertyKeyTokenStore(), cursorContext));
        }
        return tokenHolders;
    }

    private void cancel(String message) {
        if (!isCancelled()) {
            context.debug("Stopping: %s", message);
            context.cancel();
        }
    }

    private boolean isCancelled() {
        return context.isCancelled();
    }

    private void consistencyCheckSingleCheckable(
            InconsistencyReport report,
            ProgressListener listener,
            ConsistencyCheckable checkable,
            RecordType recordType) {
        ConsistencyReporter.FormattingDocumentedHandler handler =
                ConsistencyReporter.formattingHandler(report, recordType);
        ReporterFactory proxyFactory = new ReporterFactory(handler);

        checkable.consistencyCheck(proxyFactory, contextFactory, context.execution.getNumberOfThreads());
        handler.updateSummary();
        listener.add(1);
    }

    private static class SchemaRulesDescriptors implements IndexDescriptorProvider {
        private final NeoStores neoStores;
        private final SchemaRuleAccess schemaRuleAccess;

        SchemaRulesDescriptors(NeoStores neoStores, SchemaRuleAccess schemaRuleAccess) {
            this.neoStores = neoStores;
            this.schemaRuleAccess = schemaRuleAccess;
        }

        @Override
        public ResourceIterator<IndexDescriptor> indexDescriptors(CursorContext cursorContext) {
            var storeCursors = new CachedStoreCursors(neoStores, cursorContext);
            var descriptorIterator = schemaRuleAccess.indexesGetAllIgnoreMalformed(storeCursors);
            return resourceIterator(descriptorIterator, storeCursors::close);
        }
    }
}

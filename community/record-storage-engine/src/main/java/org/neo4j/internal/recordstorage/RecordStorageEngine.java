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
package org.neo4j.internal.recordstorage;

import static java.util.Collections.emptyList;
import static org.neo4j.configuration.GraphDatabaseInternalSettings.counts_store_max_cached_entries;
import static org.neo4j.function.ThrowingAction.executeAll;
import static org.neo4j.lock.LockService.NO_LOCK_SERVICE;
import static org.neo4j.storageengine.api.TransactionApplicationMode.RECOVERY;
import static org.neo4j.storageengine.api.TransactionApplicationMode.REVERSE_RECOVERY;
import static org.neo4j.util.Preconditions.checkState;

import java.io.IOException;
import java.nio.file.OpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.eclipse.collections.api.set.ImmutableSet;
import org.eclipse.collections.api.set.primitive.LongSet;
import org.neo4j.collection.trackable.HeapTrackingCollections;
import org.neo4j.configuration.Config;
import org.neo4j.configuration.GraphDatabaseInternalSettings;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.counts.CountsAccessor;
import org.neo4j.exceptions.KernelException;
import org.neo4j.exceptions.UnderlyingStorageException;
import org.neo4j.index.internal.gbptree.RecoveryCleanupWorkCollector;
import org.neo4j.internal.batchimport.Configuration;
import org.neo4j.internal.counts.CountsBuilder;
import org.neo4j.internal.counts.DegreesRebuildFromStore;
import org.neo4j.internal.counts.GBPTreeCountsStore;
import org.neo4j.internal.counts.GBPTreeGenericCountsStore;
import org.neo4j.internal.counts.GBPTreeRelationshipGroupDegreesStore;
import org.neo4j.internal.counts.RelationshipGroupDegreesStore;
import org.neo4j.internal.diagnostics.DiagnosticsLogger;
import org.neo4j.internal.diagnostics.DiagnosticsManager;
import org.neo4j.internal.id.IdGeneratorFactory;
import org.neo4j.internal.id.IdType;
import org.neo4j.internal.id.SchemaIdType;
import org.neo4j.internal.kernel.api.exceptions.TransactionApplyKernelException;
import org.neo4j.internal.kernel.api.exceptions.TransactionFailureException;
import org.neo4j.internal.kernel.api.exceptions.schema.ConstraintValidationException;
import org.neo4j.internal.kernel.api.exceptions.schema.CreateConstraintFailureException;
import org.neo4j.internal.recordstorage.NeoStoresDiagnostics.NeoStoreIdUsage;
import org.neo4j.internal.recordstorage.NeoStoresDiagnostics.NeoStoreRecords;
import org.neo4j.internal.schema.IndexConfigCompleter;
import org.neo4j.internal.schema.SchemaCache;
import org.neo4j.internal.schema.SchemaState;
import org.neo4j.internal.schema.StorageEngineIndexingBehaviour;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.layout.recordstorage.RecordDatabaseLayout;
import org.neo4j.io.pagecache.OutOfDiskSpaceException;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.io.pagecache.context.CursorContext;
import org.neo4j.io.pagecache.context.CursorContextFactory;
import org.neo4j.io.pagecache.tracing.DatabaseFlushEvent;
import org.neo4j.io.pagecache.tracing.PageCacheTracer;
import org.neo4j.kernel.KernelVersion;
import org.neo4j.kernel.KernelVersionRepository;
import org.neo4j.kernel.impl.store.CountsComputer;
import org.neo4j.kernel.impl.store.MetaDataStore;
import org.neo4j.kernel.impl.store.NeoStores;
import org.neo4j.kernel.impl.store.RecordStore;
import org.neo4j.kernel.impl.store.StoreFactory;
import org.neo4j.kernel.impl.store.StoreType;
import org.neo4j.kernel.impl.store.cursor.CachedStoreCursors;
import org.neo4j.kernel.impl.store.format.RecordFormat;
import org.neo4j.kernel.impl.store.record.AbstractBaseRecord;
import org.neo4j.kernel.impl.store.record.MetaDataRecord;
import org.neo4j.kernel.impl.store.stats.RecordDatabaseEntityCounters;
import org.neo4j.kernel.impl.store.stats.StoreEntityCounters;
import org.neo4j.kernel.impl.transaction.log.LogTailMetadata;
import org.neo4j.kernel.lifecycle.Lifecycle;
import org.neo4j.kernel.lifecycle.LifecycleAdapter;
import org.neo4j.lock.LockGroup;
import org.neo4j.lock.LockService;
import org.neo4j.lock.LockTracer;
import org.neo4j.lock.ResourceLocker;
import org.neo4j.logging.InternalLog;
import org.neo4j.logging.InternalLogProvider;
import org.neo4j.memory.MemoryTracker;
import org.neo4j.monitoring.DatabaseHealth;
import org.neo4j.storageengine.api.CommandBatchToApply;
import org.neo4j.storageengine.api.CommandCreationContext;
import org.neo4j.storageengine.api.CommandStream;
import org.neo4j.storageengine.api.ConstraintRuleAccessor;
import org.neo4j.storageengine.api.IndexUpdateListener;
import org.neo4j.storageengine.api.StorageCommand;
import org.neo4j.storageengine.api.StorageEngine;
import org.neo4j.storageengine.api.StorageLocks;
import org.neo4j.storageengine.api.StorageReader;
import org.neo4j.storageengine.api.StoreFileMetadata;
import org.neo4j.storageengine.api.StoreId;
import org.neo4j.storageengine.api.TransactionApplicationMode;
import org.neo4j.storageengine.api.TransactionIdStore;
import org.neo4j.storageengine.api.cursor.StoreCursors;
import org.neo4j.storageengine.api.txstate.LongDiffSets;
import org.neo4j.storageengine.api.txstate.ReadableTransactionState;
import org.neo4j.storageengine.api.txstate.TransactionCountingStateVisitor;
import org.neo4j.storageengine.api.txstate.TxStateVisitor;
import org.neo4j.storageengine.api.txstate.TxStateVisitor.Decorator;
import org.neo4j.storageengine.util.IdGeneratorUpdatesWorkSync;
import org.neo4j.storageengine.util.IdUpdateListener;
import org.neo4j.storageengine.util.IndexUpdatesWorkSync;
import org.neo4j.token.TokenHolders;
import org.neo4j.util.VisibleForTesting;

public class RecordStorageEngine implements StorageEngine, Lifecycle {
    private static final String STORAGE_ENGINE_START_TAG = "storageEngineStart";
    private static final String SCHEMA_CACHE_START_TAG = "schemaCacheStart";
    private static final String TOKENS_INIT_TAG = "tokensInitialisation";

    private final NeoStores neoStores;
    private final RecordDatabaseLayout databaseLayout;
    private final Config config;
    private final InternalLogProvider internalLogProvider;
    private final TokenHolders tokenHolders;
    private final DatabaseHealth databaseHealth;
    private final SchemaCache schemaCache;
    private final CacheAccessBackDoor cacheAccess;
    private final SchemaState schemaState;
    private final SchemaRuleAccess schemaRuleAccess;
    private final ConstraintRuleAccessor constraintSemantics;
    private final LockService lockService;
    private final boolean consistencyCheckApply;
    private final boolean parallelIndexUpdatesApply;
    private IndexUpdatesWorkSync indexUpdatesSync;
    private final IdGeneratorFactory idGeneratorFactory;
    private final CursorContextFactory contextFactory;
    private final MemoryTracker otherMemoryTracker;
    final KernelVersionRepository kernelVersionRepository;
    private final LockVerificationFactory lockVerificationFactory;
    private final GBPTreeCountsStore countsStore;
    private final RelationshipGroupDegreesStore groupDegreesStore;
    private final int denseNodeThreshold;
    private final IdGeneratorUpdatesWorkSync idGeneratorWorkSyncs = new IdGeneratorUpdatesWorkSync();
    private final Map<TransactionApplicationMode, TransactionApplierFactoryChain> applierChains =
            new EnumMap<>(TransactionApplicationMode.class);
    private final RecordDatabaseEntityCounters storeEntityCounters;
    private final RecordStorageIndexingBehaviour indexingBehaviour = new RecordStorageIndexingBehaviour();

    // installed later
    private IndexUpdateListener indexUpdateListener;
    private volatile boolean closed;

    public RecordStorageEngine(
            RecordDatabaseLayout databaseLayout,
            Config config,
            PageCache pageCache,
            FileSystemAbstraction fs,
            InternalLogProvider internalLogProvider,
            InternalLogProvider userLogProvider,
            TokenHolders tokenHolders,
            SchemaState schemaState,
            ConstraintRuleAccessor constraintSemantics,
            IndexConfigCompleter indexConfigCompleter,
            LockService lockService,
            DatabaseHealth databaseHealth,
            IdGeneratorFactory idGeneratorFactory,
            RecoveryCleanupWorkCollector recoveryCleanupWorkCollector,
            MemoryTracker otherMemoryTracker,
            LogTailMetadata logTailMetadata,
            KernelVersionRepository kernelVersionRepository,
            LockVerificationFactory lockVerificationFactory,
            CursorContextFactory contextFactory,
            PageCacheTracer pageCacheTracer) {
        this.databaseLayout = databaseLayout;
        this.config = config;
        this.internalLogProvider = internalLogProvider;
        this.tokenHolders = tokenHolders;
        this.schemaState = schemaState;
        this.lockService = lockService;
        this.databaseHealth = databaseHealth;
        this.constraintSemantics = constraintSemantics;
        this.idGeneratorFactory = idGeneratorFactory;
        this.contextFactory = contextFactory;
        this.otherMemoryTracker = otherMemoryTracker;
        this.kernelVersionRepository = kernelVersionRepository;
        this.lockVerificationFactory = lockVerificationFactory;

        StoreFactory factory = new StoreFactory(
                databaseLayout,
                config,
                idGeneratorFactory,
                pageCache,
                pageCacheTracer,
                fs,
                internalLogProvider,
                contextFactory,
                false,
                logTailMetadata);
        neoStores = factory.openAllNeoStores();
        Stream.of(RecordIdType.values()).forEach(idType -> idGeneratorWorkSyncs.add(idGeneratorFactory.get(idType)));
        Stream.of(SchemaIdType.values()).forEach(idType -> idGeneratorWorkSyncs.add(idGeneratorFactory.get(idType)));

        try {
            schemaRuleAccess = SchemaRuleAccess.getSchemaRuleAccess(neoStores.getSchemaStore(), tokenHolders);
            schemaCache = new SchemaCache(constraintSemantics, indexConfigCompleter, indexingBehaviour);

            cacheAccess = new BridgingCacheAccess(schemaCache, schemaState, tokenHolders);

            denseNodeThreshold = config.get(GraphDatabaseSettings.dense_node_threshold);

            countsStore = openCountsStore(
                    pageCache,
                    fs,
                    databaseLayout,
                    internalLogProvider,
                    userLogProvider,
                    recoveryCleanupWorkCollector,
                    config,
                    contextFactory,
                    pageCacheTracer);

            groupDegreesStore = openDegreesStore(
                    pageCache,
                    fs,
                    databaseLayout,
                    internalLogProvider,
                    userLogProvider,
                    recoveryCleanupWorkCollector,
                    config,
                    contextFactory,
                    pageCacheTracer);

            consistencyCheckApply = config.get(GraphDatabaseInternalSettings.consistency_check_on_apply);
            storeEntityCounters = new RecordDatabaseEntityCounters(idGeneratorFactory, countsStore);
            parallelIndexUpdatesApply = config.get(GraphDatabaseInternalSettings.parallel_index_updates_apply);
        } catch (Throwable failure) {
            neoStores.close();
            throw failure;
        }
    }

    private void buildApplierChains() {
        for (TransactionApplicationMode mode : TransactionApplicationMode.values()) {
            applierChains.put(mode, buildApplierFacadeChain(mode));
        }
    }

    private TransactionApplierFactoryChain buildApplierFacadeChain(TransactionApplicationMode mode) {
        Function<IdGeneratorUpdatesWorkSync, IdUpdateListener> idUpdateListenerFunction = mode == REVERSE_RECOVERY
                ? workSync -> IdUpdateListener.IGNORE
                : workSync -> workSync.newBatch(contextFactory);
        List<TransactionApplierFactory> appliers = new ArrayList<>();
        // Graph store application. The order of the decorated store appliers is irrelevant
        if (consistencyCheckApply && mode.needsAuxiliaryStores()) {
            appliers.add(new ConsistencyCheckingApplierFactory(neoStores));
        }
        appliers.add(new KernelVersionTransactionApplier.Factory(kernelVersionRepository));
        appliers.add(new NeoStoreTransactionApplierFactory(mode, neoStores, cacheAccess, lockService(mode)));
        if (mode.needsHighIdTracking()) {
            appliers.add(new HighIdTransactionApplierFactory(neoStores));
        }
        if (mode.needsCacheInvalidationOnUpdates()) {
            appliers.add(new CacheInvalidationTransactionApplierFactory(neoStores, cacheAccess));
        }
        if (mode.needsAuxiliaryStores()) {
            // Counts store application
            appliers.add(new CountsStoreTransactionApplierFactory(countsStore, groupDegreesStore));

            // Schema index application
            appliers.add(new IndexTransactionApplierFactory(indexUpdateListener));
        }
        return new TransactionApplierFactoryChain(
                idUpdateListenerFunction, appliers.toArray(new TransactionApplierFactory[0]));
    }

    private GBPTreeCountsStore openCountsStore(
            PageCache pageCache,
            FileSystemAbstraction fs,
            RecordDatabaseLayout layout,
            InternalLogProvider internalLogProvider,
            InternalLogProvider userLogProvider,
            RecoveryCleanupWorkCollector recoveryCleanupWorkCollector,
            Config config,
            CursorContextFactory contextFactory,
            PageCacheTracer pageCacheTracer) {
        try {
            return new GBPTreeCountsStore(
                    pageCache,
                    layout.countStore(),
                    fs,
                    recoveryCleanupWorkCollector,
                    new CountsBuilder() {
                        private final InternalLog log = internalLogProvider.getLog(MetaDataStore.class);

                        @Override
                        public void initialize(
                                CountsAccessor.Updater updater,
                                CursorContext cursorContext,
                                MemoryTracker memoryTracker) {
                            log.warn("Missing counts store, rebuilding it.");
                            new CountsComputer(neoStores, pageCache, contextFactory, layout, memoryTracker, log)
                                    .initialize(updater, cursorContext, memoryTracker);
                            log.warn("Counts store rebuild completed.");
                        }

                        @Override
                        public long lastCommittedTxId() {
                            TransactionIdStore txIdStore = metadataProvider();
                            return txIdStore.getLastCommittedTransactionId();
                        }
                    },
                    false,
                    GBPTreeGenericCountsStore.NO_MONITOR,
                    layout.getDatabaseName(),
                    config.get(counts_store_max_cached_entries),
                    userLogProvider,
                    contextFactory,
                    pageCacheTracer,
                    getOpenOptions());
        } catch (IOException e) {
            throw new UnderlyingStorageException(e);
        }
    }

    private RelationshipGroupDegreesStore openDegreesStore(
            PageCache pageCache,
            FileSystemAbstraction fs,
            RecordDatabaseLayout layout,
            InternalLogProvider internalLogProvider,
            InternalLogProvider userLogProvider,
            RecoveryCleanupWorkCollector recoveryCleanupWorkCollector,
            Config config,
            CursorContextFactory contextFactory,
            PageCacheTracer pageCacheTracer) {
        try {
            return new GBPTreeRelationshipGroupDegreesStore(
                    pageCache,
                    layout.relationshipGroupDegreesStore(),
                    fs,
                    recoveryCleanupWorkCollector,
                    new DegreesRebuildFromStore(
                            pageCache,
                            neoStores,
                            databaseLayout,
                            contextFactory,
                            internalLogProvider,
                            Configuration.DEFAULT),
                    false,
                    GBPTreeGenericCountsStore.NO_MONITOR,
                    layout.getDatabaseName(),
                    config.get(counts_store_max_cached_entries),
                    userLogProvider,
                    contextFactory,
                    pageCacheTracer,
                    getOpenOptions());
        } catch (IOException e) {
            throw new UnderlyingStorageException(e);
        }
    }

    @Override
    public RecordStorageReader newReader() {
        return new RecordStorageReader(tokenHolders, neoStores, countsStore, groupDegreesStore, schemaCache);
    }

    @Override
    public RecordStorageCommandCreationContext newCommandCreationContext() {
        return new RecordStorageCommandCreationContext(
                neoStores, tokenHolders, internalLogProvider, denseNodeThreshold, config);
    }

    @Override
    public StoreCursors createStorageCursors(CursorContext cursorContext) {
        return new CachedStoreCursors(neoStores, cursorContext);
    }

    @Override
    public StorageLocks createStorageLocks(ResourceLocker locker) {
        return new RecordStorageLocks(locker);
    }

    @Override
    public void addIndexUpdateListener(IndexUpdateListener listener) {
        checkState(
                this.indexUpdateListener == null,
                "Only supports a single listener. Tried to add " + listener + ", but " + this.indexUpdateListener
                        + " has already been added");
        this.indexUpdateListener = listener;
        this.indexUpdatesSync = new IndexUpdatesWorkSync(listener, parallelIndexUpdatesApply);
    }

    /**
     * @throws TransactionFailureException if command generation fails or some prerequisite of some command didn't validate,
     * for example if trying to delete a node that still has relationships.
     * @throws CreateConstraintFailureException if this transaction was set to create a constraint and that failed.
     * @throws ConstraintValidationException if this transaction was set to create a constraint and some data violates that constraint.
     */
    @Override
    public List<StorageCommand> createCommands(
            ReadableTransactionState txState,
            StorageReader storageReader,
            CommandCreationContext commandCreationContext,
            LockTracer lockTracer,
            Decorator additionalTxStateVisitor,
            CursorContext cursorContext,
            StoreCursors storeCursors,
            MemoryTracker memoryTracker)
            throws KernelException {
        if (txState == null) {
            return emptyList();
        }
        var commands = HeapTrackingCollections.<StorageCommand>newArrayList(memoryTracker);

        // We can make this cast here because we expected that the storageReader passed in here comes from
        // this storage engine itself, anything else is considered a bug. And we do know the inner workings
        // of the storage statements that we create.
        RecordStorageCommandCreationContext creationContext =
                (RecordStorageCommandCreationContext) commandCreationContext;
        LogCommandSerialization serialization =
                RecordStorageCommandReaderFactory.INSTANCE.get(commandCreationContext.kernelVersion());
        var locks = creationContext.getLocks();
        TransactionRecordState recordState = creationContext.createTransactionRecordState(
                locks,
                lockTracer,
                serialization,
                memoryTracker,
                lockVerificationFactory.createLockVerification(
                        locks, txState, neoStores, schemaRuleAccess, storeCursors));

        // Visit transaction state and populate these record state objects
        TxStateVisitor txStateVisitor = new TransactionToRecordStateVisitor(
                recordState, schemaState, schemaRuleAccess, constraintSemantics, cursorContext, storeCursors);
        CountsRecordState countsRecordState = new CountsRecordState(serialization);
        txStateVisitor = additionalTxStateVisitor.apply(txStateVisitor);
        txStateVisitor = new TransactionCountingStateVisitor(
                txStateVisitor, storageReader, txState, countsRecordState, cursorContext, storeCursors);
        try (TxStateVisitor visitor = txStateVisitor) {
            txState.accept(visitor);
        }
        // Convert record state into commands
        recordState.extractCommands(commands, memoryTracker);
        countsRecordState.extractCommands(commands, memoryTracker);

        // Verify sufficient locks
        CommandLockVerification commandLockVerification = lockVerificationFactory.createCommandVerification(
                locks, txState, neoStores, schemaRuleAccess, storeCursors);
        commandLockVerification.verifySufficientlyLocked(commands);

        unallocateIds(txState.addedAndRemovedNodes().getRemovedFromAdded(), RecordIdType.NODE, cursorContext);
        unallocateIds(
                txState.addedAndRemovedRelationships().getRemovedFromAdded(), RecordIdType.RELATIONSHIP, cursorContext);
        return commands;
    }

    @Override
    public List<StorageCommand> createUpgradeCommands(
            KernelVersion versionToUpgradeFrom, KernelVersion versionToUpgradeTo) {
        checkState(
                versionToUpgradeTo.isGreaterThan(versionToUpgradeFrom),
                "Can not downgrade from %s to %s",
                versionToUpgradeFrom,
                versionToUpgradeTo);

        MetaDataStore metaDataStore = metadataProvider();

        MetaDataRecord before = metaDataStore.newRecord();
        before.initialize(true, versionToUpgradeFrom.version());

        MetaDataRecord after = metaDataStore.newRecord();
        after.initialize(true, versionToUpgradeTo.version());

        // This command will be the first one in the "new" version, indicating the switch and writing it to the
        // KernelVersionRepository
        LogCommandSerialization serialization = RecordStorageCommandReaderFactory.INSTANCE.get(versionToUpgradeTo);

        return List.of(new Command.MetaDataCommand(serialization, before, after));
    }

    @Override
    public void lockRecoveryCommands(
            CommandStream commands, LockService lockService, LockGroup lockGroup, TransactionApplicationMode mode) {
        for (StorageCommand command : commands) {
            ((Command) command).lockForRecovery(lockService, lockGroup, mode);
        }
    }

    @Override
    public void apply(CommandBatchToApply batch, TransactionApplicationMode mode) throws Exception {
        TransactionApplierFactoryChain batchApplier = applierChain(mode);
        CommandBatchToApply initialBatch = batch;
        try (BatchContext context = createBatchContext(batchApplier, batch)) {
            while (batch != null) {
                try (TransactionApplier txApplier = batchApplier.startTx(batch, context)) {
                    batch.accept(txApplier);
                }
                batch = batch.next();
            }
        } catch (Throwable cause) {
            TransactionApplyKernelException kernelException = new TransactionApplyKernelException(
                    cause, "Failed to apply transaction: %s", batch == null ? initialBatch : batch);
            databaseHealth.panic(kernelException);
            throw kernelException;
        }
    }

    @Override
    public void rollback(ReadableTransactionState txState, CursorContext cursorContext) {
        // Extract allocated IDs from created nodes/relationships from txState
        // (optionally) flick through the commands to try and salvage other types of IDs, like property/dynamic record
        // IDs, but that's way less bang for your buck.
        unallocateIds(txState.addedAndRemovedNodes(), RecordIdType.NODE, cursorContext);
        unallocateIds(txState.addedAndRemovedRelationships(), RecordIdType.RELATIONSHIP, cursorContext);
    }

    private void unallocateIds(LongDiffSets ids, IdType idType, CursorContext cursorContext) {
        // Free those that were created
        unallocateIds(ids.getAdded(), idType, cursorContext);
        // Free those that were created and then deleted
        unallocateIds(ids.getRemovedFromAdded(), idType, cursorContext);
    }

    private void unallocateIds(LongSet ids, IdType idType, CursorContext cursorContext) {
        if (!ids.isEmpty()) {
            try (var marker = idGeneratorFactory.get(idType).marker(cursorContext)) {
                ids.forEach(marker::markUnallocated);
            }
        }
    }

    private BatchContext createBatchContext(
            TransactionApplierFactoryChain batchApplier, CommandBatchToApply initialBatch) {
        return new BatchContextImpl(
                indexUpdateListener,
                indexUpdatesSync,
                neoStores.getNodeStore(),
                neoStores.getPropertyStore(),
                this,
                schemaCache,
                initialBatch.cursorContext(),
                otherMemoryTracker,
                batchApplier.getIdUpdateListener(idGeneratorWorkSyncs),
                initialBatch.storeCursors());
    }

    /**
     * Provides a {@link TransactionApplierFactoryChain} that is to be used for all transactions
     * in a batch. Each transaction is handled by a {@link TransactionApplierFacade} which wraps the
     * individual {@link TransactionApplier}s returned by the wrapped {@link TransactionApplierFactory}s.
     */
    protected TransactionApplierFactoryChain applierChain(TransactionApplicationMode mode) {
        return applierChains.get(mode);
    }

    private LockService lockService(TransactionApplicationMode mode) {
        return mode == RECOVERY || mode == REVERSE_RECOVERY ? NO_LOCK_SERVICE : lockService;
    }

    @Override
    public void init() {
        buildApplierChains();
    }

    @Override
    public void start() throws Exception {
        try (var cursorContext = contextFactory.create(STORAGE_ENGINE_START_TAG);
                var storeCursors = new CachedStoreCursors(neoStores, cursorContext)) {
            neoStores.start(cursorContext);
            countsStore.start(cursorContext, storeCursors, otherMemoryTracker);
            groupDegreesStore.start(cursorContext, storeCursors, otherMemoryTracker);
        }
    }

    @VisibleForTesting
    public void loadSchemaCache() {
        try (var cursorContext = contextFactory.create(SCHEMA_CACHE_START_TAG);
                var storeCursors = new CachedStoreCursors(neoStores, cursorContext)) {
            schemaCache.load(schemaRuleAccess.getAll(storeCursors));
        }
    }

    @Override
    public void stop() throws Exception {}

    @Override
    public void shutdown() {
        if (!closed) {
            try {
                executeAll(countsStore::close, groupDegreesStore::close, neoStores::close);
            } catch (Throwable e) {
                throw new RuntimeException(e);
            } finally {
                closed = true;
            }
        }
    }

    @Override
    public void checkpoint(DatabaseFlushEvent flushEvent, CursorContext cursorContext) throws IOException {
        try (var fileFlushEvent = flushEvent.beginFileFlush()) {
            countsStore.checkpoint(fileFlushEvent, cursorContext);
        }
        try (var fileFlushEvent = flushEvent.beginFileFlush()) {
            groupDegreesStore.checkpoint(fileFlushEvent, cursorContext);
        }
        neoStores.checkpoint(flushEvent, cursorContext);
    }

    @Override
    public void dumpDiagnostics(InternalLog errorLog, DiagnosticsLogger diagnosticsLog) {
        DiagnosticsManager.dump(new NeoStoreIdUsage(neoStores), errorLog, diagnosticsLog);
        DiagnosticsManager.dump(new NeoStoreRecords(neoStores), errorLog, diagnosticsLog);
    }

    @Override
    public void listStorageFiles(Collection<StoreFileMetadata> atomic, Collection<StoreFileMetadata> replayable) {
        atomic.add(new StoreFileMetadata(databaseLayout.countStore(), RecordFormat.NO_RECORD_SIZE));
        atomic.add(new StoreFileMetadata(databaseLayout.relationshipGroupDegreesStore(), RecordFormat.NO_RECORD_SIZE));
        for (StoreType type : StoreType.values()) {
            final RecordStore<AbstractBaseRecord> recordStore = neoStores.getRecordStore(type);
            StoreFileMetadata metadata =
                    new StoreFileMetadata(recordStore.getStorageFile(), recordStore.getRecordSize());
            replayable.add(metadata);
        }
    }

    /**
     * @return the underlying {@link NeoStores} which should <strong>ONLY</strong> be accessed by tests
     * until all tests are properly converted to not rely on access to {@link NeoStores}. Currently, there
     * are important tests which asserts details about the neo stores that are very important to test,
     * but to convert all those tests might be a bigger piece of work.
     */
    @VisibleForTesting
    public NeoStores testAccessNeoStores() {
        return neoStores;
    }

    @VisibleForTesting
    public SchemaRuleAccess testAccessSchemaRules() {
        return schemaRuleAccess;
    }

    @Override
    public StoreId retrieveStoreId() {
        return metadataProvider().getStoreId();
    }

    @Override
    public Lifecycle schemaAndTokensLifecycle() {
        return new LifecycleAdapter() {
            @Override
            public void init() {
                try (var cursorContext = contextFactory.create(TOKENS_INIT_TAG);
                        var storeCursors = new CachedStoreCursors(neoStores, cursorContext)) {
                    tokenHolders.setInitialTokens(StoreTokens.allTokens(neoStores), storeCursors);
                }
                loadSchemaCache();
            }
        };
    }

    @Override
    public CountsAccessor countsAccessor() {
        return countsStore;
    }

    @VisibleForTesting
    public RelationshipGroupDegreesStore relationshipGroupDegreesStore() {
        return groupDegreesStore;
    }

    @Override
    public MetaDataStore metadataProvider() {
        return neoStores.getMetaDataStore();
    }

    @Override
    public StoreEntityCounters storeEntityCounters() {
        return storeEntityCounters;
    }

    @Override
    public ImmutableSet<OpenOption> getOpenOptions() {
        return neoStores.getOpenOptions();
    }

    @Override
    public StorageEngineIndexingBehaviour indexingBehaviour() {
        return indexingBehaviour;
    }

    @Override
    public void preAllocateStoreFilesForCommands(CommandBatchToApply batch, TransactionApplicationMode mode)
            throws IOException {
        if (!mode.equals(REVERSE_RECOVERY) && batch != null) {
            try (PreAllocationTransactionApplier txApplier = new PreAllocationTransactionApplier(neoStores)) {
                while (batch != null) {
                    batch.accept(txApplier);
                    batch = batch.next();
                }
            } catch (OutOfDiskSpaceException e) {
                databaseHealth.outOfDiskSpace(e);
                throw e;
            }
        }
    }
}

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
package org.neo4j.kernel.impl.api;

import static java.util.stream.Collectors.toSet;
import static org.neo4j.configuration.GraphDatabaseSettings.memory_transaction_database_max_size;
import static org.neo4j.io.pagecache.PageCacheOpenOptions.MULTI_VERSIONED;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import org.neo4j.collection.Dependencies;
import org.neo4j.collection.pool.LinkedQueuePool;
import org.neo4j.collection.pool.Pool;
import org.neo4j.configuration.Config;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.dbms.database.DbmsRuntimeRepository;
import org.neo4j.dbms.database.readonly.DatabaseReadOnlyChecker;
import org.neo4j.function.Factory;
import org.neo4j.graphdb.DatabaseShutdownException;
import org.neo4j.graphdb.TransactionFailureException;
import org.neo4j.internal.id.IdController;
import org.neo4j.internal.kernel.api.connectioninfo.ClientConnectionInfo;
import org.neo4j.internal.kernel.api.security.AbstractSecurityLog;
import org.neo4j.internal.kernel.api.security.LoginContext;
import org.neo4j.internal.kernel.api.security.SecurityContext;
import org.neo4j.internal.schema.SchemaState;
import org.neo4j.io.pagecache.context.CursorContextFactory;
import org.neo4j.kernel.KernelVersionProvider;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.api.KernelTransactionHandle;
import org.neo4j.kernel.api.exceptions.Status;
import org.neo4j.kernel.api.procedure.GlobalProcedures;
import org.neo4j.kernel.availability.AvailabilityGuard;
import org.neo4j.kernel.database.DatabaseTracers;
import org.neo4j.kernel.database.NamedDatabaseId;
import org.neo4j.kernel.impl.api.index.IndexingService;
import org.neo4j.kernel.impl.api.index.stats.IndexStatisticsStore;
import org.neo4j.kernel.impl.api.state.ConstraintIndexCreator;
import org.neo4j.kernel.impl.api.txid.TransactionIdGenerator;
import org.neo4j.kernel.impl.constraints.ConstraintSemantics;
import org.neo4j.kernel.impl.factory.AccessCapabilityFactory;
import org.neo4j.kernel.impl.locking.Locks;
import org.neo4j.kernel.impl.query.TransactionExecutionMonitor;
import org.neo4j.kernel.impl.transaction.TransactionMonitor;
import org.neo4j.kernel.impl.transaction.log.LogicalTransactionStore;
import org.neo4j.kernel.impl.transaction.log.TransactionCommitmentFactory;
import org.neo4j.kernel.impl.util.collection.CollectionsFactorySupplier;
import org.neo4j.kernel.internal.event.DatabaseTransactionEventListeners;
import org.neo4j.kernel.lifecycle.LifecycleAdapter;
import org.neo4j.logging.LogProvider;
import org.neo4j.memory.GlobalMemoryGroupTracker;
import org.neo4j.memory.ScopedMemoryPool;
import org.neo4j.monitoring.DatabaseHealth;
import org.neo4j.resources.CpuClock;
import org.neo4j.storageengine.api.StorageEngine;
import org.neo4j.storageengine.api.TransactionId;
import org.neo4j.storageengine.api.TransactionIdStore;
import org.neo4j.time.SystemNanoClock;
import org.neo4j.token.TokenHolders;
import org.neo4j.values.ElementIdMapper;

/**
 * Central source of transactions in the database.
 * <p>
 * This class maintains references to all transactions, a pool of passive kernel transactions, and provides
 * capabilities
 * for enumerating all running transactions. During normal operation, acquiring new transactions and enumerating live
 * ones requires no synchronization (although the live list is not guaranteed to be exact).
 */
public class KernelTransactions extends LifecycleAdapter
        implements TransactionRegistry, Supplier<IdController.TransactionSnapshot>, IdController.IdFreeCondition {
    public static final long SYSTEM_TRANSACTION_ID = 0;
    private final Locks locks;
    private final ConstraintIndexCreator constraintIndexCreator;
    private final TransactionCommitProcess transactionCommitProcess;
    private final DatabaseTransactionEventListeners eventListeners;
    private final TransactionMonitor transactionMonitor;
    private final GlobalMemoryGroupTracker transactionsMemoryPool;
    private final TransactionExecutionMonitor transactionExecutionMonitor;
    private final AvailabilityGuard databaseAvailabilityGuard;
    private final StorageEngine storageEngine;
    private final GlobalProcedures globalProcedures;
    private final DbmsRuntimeRepository dbmsRuntimeRepository;
    private final TransactionIdStore transactionIdStore;
    private final KernelVersionProvider kernelVersionProvider;
    private final LogicalTransactionStore transactionStore;
    private final AtomicReference<CpuClock> cpuClockRef;
    private final AccessCapabilityFactory accessCapabilityFactory;
    private final SystemNanoClock clock;
    private final CursorContextFactory contextFactory;
    private final ReentrantReadWriteLock newTransactionsLock = new ReentrantReadWriteLock();
    private final TransactionIdSequence transactionIdSequence;
    private final TokenHolders tokenHolders;
    private final ElementIdMapper elementIdMapper;
    private final DatabaseReadOnlyChecker readOnlyDatabaseChecker;
    private final IdController.IdFreeCondition externalIdReuseCondition;
    private final TransactionCommitmentFactory commitmentFactory;
    private final TransactionIdGenerator transactionIdGenerator;
    private final DatabaseHealth databaseHealth;
    private final LogProvider internalLogProvider;
    private final NamedDatabaseId namedDatabaseId;
    private final IndexingService indexingService;
    private final IndexStatisticsStore indexStatisticsStore;
    private final Dependencies databaseDependencies;
    private final Config config;
    private final CollectionsFactorySupplier collectionsFactorySupplier;
    private final SchemaState schemaState;
    private final LeaseService leaseService;

    /**
     * Used to enumerate all transactions in the system, active and idle ones.
     * <p>
     * This data structure is *only* updated when brand-new transactions are created, or when transactions are disposed
     * of. During normal operation (where all transactions come from and are returned to the pool), this will be left
     * in peace, working solely as a collection of references to all transaction objects (idle and active) in the
     * database.
     * <p>
     * As such, it provides a good mechanism for listing all transactions without requiring synchronization when
     * starting and committing transactions.
     */
    private final Set<KernelTransactionImplementation> allTransactions = ConcurrentHashMap.newKeySet();

    private final MonitoredTransactionPool txPool;
    private final ConstraintSemantics constraintSemantics;
    private final AtomicInteger activeTransactionCounter = new AtomicInteger();
    private final TokenHoldersIdLookup tokenHoldersIdLookup;
    private final AbstractSecurityLog securityLog;
    private final boolean multiVersioned;
    private ScopedMemoryPool transactionMemoryPool;

    /**
     * Kernel transactions component status. True when stopped, false when started.
     * Will not allow to start new transaction by stopped instance of kernel transactions.
     * Should simplify tracking of stopped component usage by up the stack components.
     */
    private volatile boolean stopped = true;

    public KernelTransactions(
            Config config,
            Locks locks,
            ConstraintIndexCreator constraintIndexCreator,
            TransactionCommitProcess transactionCommitProcess,
            DatabaseTransactionEventListeners eventListeners,
            TransactionMonitor transactionMonitor,
            AvailabilityGuard databaseAvailabilityGuard,
            StorageEngine storageEngine,
            GlobalProcedures globalProcedures,
            DbmsRuntimeRepository dbmsRuntimeRepository,
            TransactionIdStore transactionIdStore,
            KernelVersionProvider kernelVersionProvider,
            SystemNanoClock clock,
            AtomicReference<CpuClock> cpuClockRef,
            AccessCapabilityFactory accessCapabilityFactory,
            CursorContextFactory contextFactory,
            CollectionsFactorySupplier collectionsFactorySupplier,
            ConstraintSemantics constraintSemantics,
            SchemaState schemaState,
            TokenHolders tokenHolders,
            ElementIdMapper elementIdMapper,
            NamedDatabaseId namedDatabaseId,
            IndexingService indexingService,
            IndexStatisticsStore indexStatisticsStore,
            Dependencies databaseDependencies,
            DatabaseTracers tracers,
            LeaseService leaseService,
            GlobalMemoryGroupTracker transactionsMemoryPool,
            DatabaseReadOnlyChecker readOnlyDatabaseChecker,
            TransactionExecutionMonitor transactionExecutionMonitor,
            IdController.IdFreeCondition externalIdReuseCondition,
            TransactionCommitmentFactory commitmentFactory,
            TransactionIdSequence transactionIdSequence,
            TransactionIdGenerator transactionIdGenerator,
            DatabaseHealth databaseHealth,
            LogicalTransactionStore transactionStore,
            LogProvider internalLogProvider) {
        this.config = config;
        this.locks = locks;
        this.constraintIndexCreator = constraintIndexCreator;
        this.transactionCommitProcess = transactionCommitProcess;
        this.eventListeners = eventListeners;
        this.transactionMonitor = transactionMonitor;
        this.transactionsMemoryPool = transactionsMemoryPool;
        this.transactionExecutionMonitor = transactionExecutionMonitor;
        this.databaseAvailabilityGuard = databaseAvailabilityGuard;
        this.storageEngine = storageEngine;
        this.globalProcedures = globalProcedures;
        this.dbmsRuntimeRepository = dbmsRuntimeRepository;
        this.transactionIdStore = transactionIdStore;
        this.kernelVersionProvider = kernelVersionProvider;
        this.cpuClockRef = cpuClockRef;
        this.accessCapabilityFactory = accessCapabilityFactory;
        this.tokenHolders = tokenHolders;
        this.elementIdMapper = elementIdMapper;
        this.readOnlyDatabaseChecker = readOnlyDatabaseChecker;
        this.externalIdReuseCondition = externalIdReuseCondition;
        this.commitmentFactory = commitmentFactory;
        this.transactionIdGenerator = transactionIdGenerator;
        this.databaseHealth = databaseHealth;
        this.internalLogProvider = internalLogProvider;
        this.tokenHoldersIdLookup = new TokenHoldersIdLookup(tokenHolders, globalProcedures);
        this.namedDatabaseId = namedDatabaseId;
        this.indexingService = indexingService;
        this.indexStatisticsStore = indexStatisticsStore;
        this.databaseDependencies = databaseDependencies;
        this.contextFactory = contextFactory;
        this.clock = clock;
        this.collectionsFactorySupplier = collectionsFactorySupplier;
        this.constraintSemantics = constraintSemantics;
        this.schemaState = schemaState;
        this.leaseService = leaseService;
        this.transactionIdSequence = transactionIdSequence;
        this.transactionStore = transactionStore;
        this.multiVersioned = storageEngine.getOpenOptions().contains(MULTI_VERSIONED);
        this.txPool = new MonitoredTransactionPool(
                new GlobalKernelTransactionPool(
                        allTransactions, new KernelTransactionImplementationFactory(allTransactions, tracers)),
                activeTransactionCounter,
                config);
        this.securityLog = this.databaseDependencies.resolveDependency(AbstractSecurityLog.class);
        doBlockNewTransactions();
    }

    public KernelTransaction newInstance(
            KernelTransaction.Type type, LoginContext loginContext, ClientConnectionInfo clientInfo, long timeout) {
        assertCurrentThreadIsNotBlockingNewTransactions();
        SecurityContext securityContext =
                loginContext.authorize(tokenHoldersIdLookup, namedDatabaseId.name(), securityLog);
        try {
            while (!newTransactionsLock.readLock().tryLock(1, TimeUnit.SECONDS)) {
                assertRunning();
            }
            try {
                assertRunning();
                TransactionId lastCommittedTransaction = transactionIdStore.getLastCommittedTransaction();
                KernelTransactionImplementation tx = txPool.acquire();
                tx.initialize(
                        lastCommittedTransaction.transactionId(),
                        type,
                        securityContext,
                        timeout,
                        transactionIdSequence.next(),
                        clientInfo);
                return tx;
            } finally {
                newTransactionsLock.readLock().unlock();
            }
        } catch (InterruptedException ie) {
            Thread.interrupted();
            throw new TransactionFailureException("Fail to start new transaction.", ie);
        }
    }

    /**
     * Give an approximate set of all transactions currently running.
     * This is not guaranteed to be exact, as transactions may stop and start while this set is gathered.
     *
     * @return the (approximate) set of open transactions.
     */
    @Override
    public Set<KernelTransactionHandle> activeTransactions() {
        return allTransactions.stream()
                .map(this::createHandle)
                .filter(KernelTransactionHandle::isOpen)
                .collect(toSet());
    }

    public long oldestVisibleTransactionNumber() {
        long oldestVisibleTransactionNumber = Long.MAX_VALUE;
        for (KernelTransactionImplementation transaction : allTransactions) {
            if (transaction.isOpen() && !transaction.isTerminated()) {
                oldestVisibleTransactionNumber = Math.min(
                        oldestVisibleTransactionNumber,
                        transaction.cursorContext().getVersionContext().lastClosedTransactionId());
            }
        }
        return oldestVisibleTransactionNumber;
    }

    public long oldestActiveTransactionSequenceNumber() {
        long oldestTransactionSequenceNumber = Long.MAX_VALUE;
        for (KernelTransactionImplementation transaction : allTransactions) {
            if (transaction.isOpen() && !transaction.isTerminated()) {
                oldestTransactionSequenceNumber =
                        Math.min(oldestTransactionSequenceNumber, transaction.getTransactionSequenceNumber());
            }
        }
        return oldestTransactionSequenceNumber;
    }

    public long startTimeOfOldestActiveTransaction() {
        long startTime = Long.MAX_VALUE;
        for (KernelTransactionImplementation transaction : allTransactions) {
            if (transaction.isOpen() && !transaction.isTerminated()) {
                startTime = Math.min(startTime, transaction.startTime());
            }
        }
        return startTime;
    }

    /**
     * Give an approximate set of all transactions currently executing. In contrast to {@link #activeTransactions}, this also includes transactions in the
     * closing state, e.g. committing or rolling back. This is not guaranteed to be exact, as transactions may stop and start while this set is gathered.
     *
     * @return the (approximate) set of executing transactions.
     */
    @Override
    public Set<KernelTransactionHandle> executingTransactions() {
        return allTransactions.stream()
                .map(this::createHandle)
                .filter(h -> h.isOpen() || h.isClosing())
                .collect(toSet());
    }

    /**
     * Dispose of all pooled transactions. This is done on shutdown.
     */
    public void disposeAll() {
        terminateTransactions();
        txPool.close();
    }

    @Override
    public void terminateTransactions() {
        markAllTransactionsAsTerminated();
    }

    private void markAllTransactionsAsTerminated() {
        // we mark all transactions for termination since we want to make sure these transactions
        // won't be reused, ever. Each transaction has, among other things, a Locks.Client and we
        // certainly want to keep that from being reused from this point.
        allTransactions.forEach(tx -> tx.markForTermination(Status.General.DatabaseUnavailable));
    }

    @Override
    public boolean haveClosingTransaction() {
        return allTransactions.stream().anyMatch(KernelTransactionImplementation::isClosing);
    }

    @Override
    public void init() throws Exception {
        this.transactionMemoryPool = transactionsMemoryPool.newDatabasePool(
                namedDatabaseId.name(),
                config.get(memory_transaction_database_max_size),
                memory_transaction_database_max_size.name());
        config.addListener(
                memory_transaction_database_max_size, (before, after) -> transactionMemoryPool.setSize(after));
    }

    @Override
    public void start() {
        stopped = false;
        unblockNewTransactions();
    }

    @Override
    public void stop() {
        blockNewTransactions();
        stopped = true;
    }

    @Override
    public void shutdown() {
        transactionMemoryPool.close();
        disposeAll();
        unblockNewTransactions(); // Release the lock before we discard this object
    }

    @Override
    public IdController.TransactionSnapshot get() {
        return new IdController.TransactionSnapshot(
                transactionIdSequence.currentValue(),
                clock.millis(),
                transactionIdStore.getLastCommittedTransactionId());
    }

    @Override
    public boolean eligibleForFreeing(IdController.TransactionSnapshot snapshot) {
        return externalIdReuseCondition.eligibleForFreeing(snapshot)
                && (snapshot.currentSequenceNumber() < oldestActiveTransactionSequenceNumber());
    }

    /**
     * Do not allow new transactions to start until {@link #unblockNewTransactions()} is called. Current thread have
     * responsibility of doing so.
     * <p>
     * Blocking call.
     */
    public void blockNewTransactions() {
        doBlockNewTransactions();
    }

    /**
     * This is private since it's called from the constructor.
     */
    private void doBlockNewTransactions() {
        newTransactionsLock.writeLock().lock();
    }

    /**
     * Allow new transactions to be started again if current thread is the one who called
     * {@link #blockNewTransactions()}.
     *
     * @throws IllegalStateException if current thread is not the one that called {@link #blockNewTransactions()}.
     */
    public void unblockNewTransactions() {
        if (!newTransactionsLock.writeLock().isHeldByCurrentThread()) {
            throw new IllegalStateException("This thread did not block transactions previously");
        }
        newTransactionsLock.writeLock().unlock();
    }

    public int getNumberOfActiveTransactions() {
        return activeTransactionCounter.get();
    }

    /**
     * Create new handle for the given transaction.
     * <p>
     * <b>Note:</b> this method is package-private for testing <b>only</b>.
     *
     * @param tx transaction to wrap.
     * @return transaction handle.
     */
    KernelTransactionHandle createHandle(KernelTransactionImplementation tx) {
        return new KernelTransactionImplementationHandle(tx, clock);
    }

    private void assertRunning() {
        if (databaseAvailabilityGuard.isShutdown()) {
            throw new DatabaseShutdownException();
        }
        if (stopped) {
            throw new IllegalStateException("Can't start new transaction with stopped " + getClass());
        }
    }

    private void assertCurrentThreadIsNotBlockingNewTransactions() {
        if (newTransactionsLock.isWriteLockedByCurrentThread()) {
            throw new IllegalStateException(
                    "Thread that is blocking new transactions from starting can't start new transaction");
        }
    }

    private class KernelTransactionImplementationFactory implements Factory<KernelTransactionImplementation> {
        private final Set<KernelTransactionImplementation> transactions;
        private final DatabaseTracers tracers;

        KernelTransactionImplementationFactory(
                Set<KernelTransactionImplementation> transactions, DatabaseTracers tracers) {
            this.transactions = transactions;
            this.tracers = tracers;
        }

        @Override
        public KernelTransactionImplementation newInstance() {
            KernelTransactionImplementation tx = new KernelTransactionImplementation(
                    config,
                    eventListeners,
                    constraintIndexCreator,
                    globalProcedures,
                    transactionCommitProcess,
                    transactionMonitor,
                    txPool,
                    clock,
                    cpuClockRef,
                    tracers,
                    storageEngine,
                    accessCapabilityFactory,
                    contextFactory,
                    collectionsFactorySupplier,
                    constraintSemantics,
                    schemaState,
                    tokenHolders,
                    elementIdMapper,
                    indexingService,
                    indexStatisticsStore,
                    databaseDependencies,
                    namedDatabaseId,
                    leaseService,
                    transactionMemoryPool,
                    readOnlyDatabaseChecker,
                    transactionExecutionMonitor,
                    securityLog,
                    locks,
                    commitmentFactory,
                    KernelTransactions.this,
                    transactionIdGenerator,
                    dbmsRuntimeRepository,
                    kernelVersionProvider,
                    transactionStore,
                    databaseHealth,
                    internalLogProvider,
                    multiVersioned);
            this.transactions.add(tx);
            return tx;
        }
    }

    private static class GlobalKernelTransactionPool extends LinkedQueuePool<KernelTransactionImplementation> {
        private final Set<KernelTransactionImplementation> transactions;

        GlobalKernelTransactionPool(
                Set<KernelTransactionImplementation> transactions, Factory<KernelTransactionImplementation> factory) {
            super(8, factory);
            this.transactions = transactions;
        }

        @Override
        public void dispose(KernelTransactionImplementation tx) {
            transactions.remove(tx);
            tx.dispose();
            super.dispose(tx);
        }
    }

    static class MonitoredTransactionPool implements Pool<KernelTransactionImplementation> {
        private final AtomicInteger activeTransactionCounter;
        private final GlobalKernelTransactionPool delegate;
        private volatile int maxNumberOfTransaction;

        MonitoredTransactionPool(
                GlobalKernelTransactionPool delegate, AtomicInteger activeTransactionCounter, Config config) {
            this.delegate = delegate;
            this.activeTransactionCounter = activeTransactionCounter;
            this.maxNumberOfTransaction = config.get(GraphDatabaseSettings.max_concurrent_transactions);
            config.addListener(
                    GraphDatabaseSettings.max_concurrent_transactions,
                    (oldValue, newValue) -> maxNumberOfTransaction = newValue);
        }

        @Override
        public KernelTransactionImplementation acquire() {
            verifyTransactionsLimit();
            return delegate.acquire();
        }

        @Override
        public void release(KernelTransactionImplementation txn) {
            activeTransactionCounter.decrementAndGet();
            delegate.release(txn);
        }

        @Override
        public void dispose(KernelTransactionImplementation txn) {
            activeTransactionCounter.decrementAndGet();
            delegate.dispose(txn);
        }

        @Override
        public void close() {
            delegate.close();
        }

        private void verifyTransactionsLimit() {
            int activeTransactions;
            do {
                activeTransactions = activeTransactionCounter.get();
                int localTransactionMaximum = maxNumberOfTransaction;
                if (localTransactionMaximum != 0 && activeTransactions >= localTransactionMaximum) {
                    throw new MaximumTransactionLimitExceededException();
                }
            } while (!activeTransactionCounter.weakCompareAndSetAcquire(activeTransactions, activeTransactions + 1));
        }
    }
}

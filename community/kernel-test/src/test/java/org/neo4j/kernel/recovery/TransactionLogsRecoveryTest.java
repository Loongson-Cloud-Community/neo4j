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
package org.neo4j.kernel.recovery;

import static java.lang.Math.toIntExact;
import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.neo4j.io.ByteUnit.KibiByte;
import static org.neo4j.io.pagecache.tracing.PageCacheTracer.NULL;
import static org.neo4j.kernel.database.DatabaseIdFactory.from;
import static org.neo4j.kernel.impl.transaction.log.LogIndexEncoding.encodeLogIndex;
import static org.neo4j.kernel.impl.transaction.log.entry.LogFormat.CURRENT_FORMAT_LOG_HEADER_SIZE;
import static org.neo4j.kernel.impl.transaction.log.entry.LogFormat.CURRENT_LOG_FORMAT_VERSION;
import static org.neo4j.kernel.impl.transaction.log.entry.LogHeaderWriter.writeLogHeader;
import static org.neo4j.kernel.impl.transaction.log.entry.LogSegments.UNKNOWN_LOG_SEGMENT_SIZE;
import static org.neo4j.kernel.impl.transaction.log.entry.v56.DetachedCheckpointLogEntryWriterV5_6.RECORD_LENGTH_BYTES;
import static org.neo4j.kernel.impl.transaction.log.files.ChannelNativeAccessor.EMPTY_ACCESSOR;
import static org.neo4j.kernel.recovery.RecoveryStartInformation.NO_RECOVERY_REQUIRED;
import static org.neo4j.kernel.recovery.RecoveryStartInformationProvider.NO_MONITOR;
import static org.neo4j.kernel.recovery.RecoveryStartupChecker.EMPTY_CHECKER;
import static org.neo4j.memory.EmptyMemoryTracker.INSTANCE;
import static org.neo4j.storageengine.api.TransactionIdStore.BASE_TX_CHECKSUM;
import static org.neo4j.storageengine.api.TransactionIdStore.BASE_TX_COMMIT_TIMESTAMP;
import static org.neo4j.storageengine.api.TransactionIdStore.UNKNOWN_CONSENSUS_INDEX;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.lang3.ArrayUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.common.ProgressReporter;
import org.neo4j.configuration.Config;
import org.neo4j.configuration.GraphDatabaseInternalSettings;
import org.neo4j.dbms.database.DatabaseStartAbortedException;
import org.neo4j.internal.helpers.collection.Visitor;
import org.neo4j.internal.nativeimpl.NativeAccessProvider;
import org.neo4j.io.ByteUnit;
import org.neo4j.io.fs.DefaultFileSystemAbstraction;
import org.neo4j.io.layout.DatabaseLayout;
import org.neo4j.io.memory.HeapScopedBuffer;
import org.neo4j.io.pagecache.context.CursorContextFactory;
import org.neo4j.io.pagecache.context.EmptyVersionContextSupplier;
import org.neo4j.kernel.database.DatabaseStartupController;
import org.neo4j.kernel.impl.api.TestCommandReaderFactory;
import org.neo4j.kernel.impl.transaction.CommittedCommandBatch;
import org.neo4j.kernel.impl.transaction.CommittedTransactionRepresentation;
import org.neo4j.kernel.impl.transaction.SimpleLogVersionRepository;
import org.neo4j.kernel.impl.transaction.SimpleTransactionIdStore;
import org.neo4j.kernel.impl.transaction.log.LogPosition;
import org.neo4j.kernel.impl.transaction.log.LogPositionMarker;
import org.neo4j.kernel.impl.transaction.log.LogicalTransactionStore;
import org.neo4j.kernel.impl.transaction.log.PhysicalLogVersionedStoreChannel;
import org.neo4j.kernel.impl.transaction.log.PhysicalLogicalTransactionStore;
import org.neo4j.kernel.impl.transaction.log.PositionAwareChannel;
import org.neo4j.kernel.impl.transaction.log.PositionAwarePhysicalFlushableChecksumChannel;
import org.neo4j.kernel.impl.transaction.log.TransactionMetadataCache;
import org.neo4j.kernel.impl.transaction.log.checkpoint.DetachedCheckpointAppender;
import org.neo4j.kernel.impl.transaction.log.entry.LogEntry;
import org.neo4j.kernel.impl.transaction.log.entry.LogEntryCommit;
import org.neo4j.kernel.impl.transaction.log.entry.LogEntryStart;
import org.neo4j.kernel.impl.transaction.log.entry.LogEntryWriter;
import org.neo4j.kernel.impl.transaction.log.entry.LogHeader;
import org.neo4j.kernel.impl.transaction.log.files.LogFile;
import org.neo4j.kernel.impl.transaction.log.files.LogFiles;
import org.neo4j.kernel.impl.transaction.log.files.LogFilesBuilder;
import org.neo4j.kernel.impl.transaction.tracing.DatabaseTracer;
import org.neo4j.kernel.impl.transaction.tracing.LogCheckPointEvent;
import org.neo4j.kernel.lifecycle.LifeSupport;
import org.neo4j.kernel.lifecycle.Lifecycle;
import org.neo4j.kernel.lifecycle.LifecycleAdapter;
import org.neo4j.logging.InternalLog;
import org.neo4j.monitoring.Monitors;
import org.neo4j.storageengine.api.LogVersionRepository;
import org.neo4j.storageengine.api.StorageEngine;
import org.neo4j.storageengine.api.StoreId;
import org.neo4j.storageengine.api.TransactionApplicationMode;
import org.neo4j.storageengine.api.TransactionId;
import org.neo4j.storageengine.api.TransactionIdStore;
import org.neo4j.storageengine.api.cursor.StoreCursors;
import org.neo4j.test.LatestVersions;
import org.neo4j.test.extension.Inject;
import org.neo4j.test.extension.Neo4jLayoutExtension;
import org.neo4j.test.utils.TestDirectory;
import org.neo4j.time.Clocks;

@Neo4jLayoutExtension
class TransactionLogsRecoveryTest {
    @Inject
    private DefaultFileSystemAbstraction fileSystem;

    @Inject
    private DatabaseLayout databaseLayout;

    @Inject
    private TestDirectory testDirectory;

    private final LogVersionRepository logVersionRepository = new SimpleLogVersionRepository();
    private final StoreId storeId = new StoreId(1, 2, "engine-1", "format-1", 3, 4);
    private final TransactionIdStore transactionIdStore =
            new SimpleTransactionIdStore(5L, 0, BASE_TX_COMMIT_TIMESTAMP, UNKNOWN_CONSENSUS_INDEX, 0, 0);
    private final int logVersion = 0;

    private LogEntry lastCommittedTxStartEntry;
    private LogEntry lastCommittedTxCommitEntry;
    private LogEntry expectedStartEntry;
    private LogEntry expectedCommitEntry;
    private final Monitors monitors = new Monitors();
    private final SimpleLogVersionRepository versionRepository = new SimpleLogVersionRepository();
    private LogFiles logFiles;
    private Path storeDir;
    private Lifecycle schemaLife;
    private LifeSupport life;

    @BeforeEach
    void setUp() throws Exception {
        storeDir = testDirectory.homePath();
        logFiles = buildLogFiles();
        life = new LifeSupport();
        life.add(logFiles);
        life.start();
        schemaLife = new LifecycleAdapter();
    }

    @AfterEach
    void tearDown() {
        life.shutdown();
    }

    @Test
    void shouldRecoverExistingData() throws Exception {
        var contextFactory = new CursorContextFactory(NULL, EmptyVersionContextSupplier.EMPTY);
        LogFile logFile = logFiles.getLogFile();
        Path file = logFile.getLogFileForVersion(logVersion);

        writeSomeData(file, dataWriters -> {
            LogEntryWriter<?> writer = dataWriters.writer();
            PositionAwareChannel channel = dataWriters.channel();
            LogPositionMarker marker = new LogPositionMarker();

            // last committed tx
            int previousChecksum = BASE_TX_CHECKSUM;
            channel.getCurrentPosition(marker);
            LogPosition lastCommittedTxPosition = marker.newPosition();
            byte version = LatestVersions.LATEST_KERNEL_VERSION.version();
            byte[] headerData = encodeLogIndex(1);
            writer.writeStartEntry(version, 2L, 3L, previousChecksum, headerData);
            lastCommittedTxStartEntry = new LogEntryStart(
                    LatestVersions.LATEST_KERNEL_VERSION,
                    2L,
                    3L,
                    previousChecksum,
                    headerData,
                    lastCommittedTxPosition);
            previousChecksum = writer.writeCommitEntry(version, 4L, 5L);
            lastCommittedTxCommitEntry = new LogEntryCommit(4L, 5L, previousChecksum);

            // check point pointing to the previously committed transaction
            var checkpointFile = logFiles.getCheckpointFile();
            var checkpointAppender = checkpointFile.getCheckpointAppender();
            checkpointAppender.checkPoint(
                    LogCheckPointEvent.NULL,
                    new TransactionId(4L, 2, 5L, 6L),
                    LatestVersions.LATEST_KERNEL_VERSION,
                    lastCommittedTxPosition,
                    Instant.now(),
                    "test");

            // tx committed after checkpoint
            channel.getCurrentPosition(marker);
            writer.writeStartEntry(version, 6L, 4L, previousChecksum, headerData);
            expectedStartEntry = new LogEntryStart(
                    LatestVersions.LATEST_KERNEL_VERSION, 6L, 4L, previousChecksum, headerData, marker.newPosition());

            previousChecksum = writer.writeCommitEntry(version, 5L, 7L);
            expectedCommitEntry = new LogEntryCommit(5L, 7L, previousChecksum);

            return true;
        });

        LifeSupport life = new LifeSupport();
        LogsRecoveryMonitor monitor = new LogsRecoveryMonitor();
        try {
            var recoveryLogFiles = buildLogFiles();
            life.add(recoveryLogFiles);
            StorageEngine storageEngine = mock(StorageEngine.class);
            when(storageEngine.createStorageCursors(any())).thenReturn(mock(StoreCursors.class));
            Config config = Config.defaults();

            TransactionMetadataCache metadataCache = new TransactionMetadataCache();
            LogicalTransactionStore txStore = new PhysicalLogicalTransactionStore(
                    recoveryLogFiles, metadataCache, new TestCommandReaderFactory(), monitors, false, config);
            CorruptedLogsTruncator logPruner =
                    new CorruptedLogsTruncator(storeDir, recoveryLogFiles, fileSystem, INSTANCE);
            monitors.addMonitorListener(monitor);
            life.add(new TransactionLogsRecovery(
                    new DefaultRecoveryService(
                            storageEngine,
                            transactionIdStore,
                            txStore,
                            versionRepository,
                            recoveryLogFiles,
                            LatestVersions.LATEST_KERNEL_VERSION_PROVIDER,
                            NO_MONITOR,
                            mock(InternalLog.class),
                            Clocks.systemClock(),
                            false) {
                        private int nr;

                        @Override
                        public RecoveryApplier getRecoveryApplier(
                                TransactionApplicationMode mode,
                                CursorContextFactory contextFactory,
                                String tracerTag) {
                            RecoveryApplier actual = super.getRecoveryApplier(mode, contextFactory, tracerTag);
                            if (mode == TransactionApplicationMode.REVERSE_RECOVERY) {
                                return actual;
                            }

                            return new RecoveryApplier() {
                                @Override
                                public void close() throws Exception {
                                    actual.close();
                                }

                                @Override
                                public boolean visit(CommittedCommandBatch commandBatch) throws Exception {
                                    actual.visit(commandBatch);
                                    if (commandBatch instanceof CommittedTransactionRepresentation tx) {
                                        switch (nr++) {
                                            case 0 -> {
                                                assertEquals(lastCommittedTxStartEntry, tx.startEntry());
                                                assertEquals(lastCommittedTxCommitEntry, tx.commitEntry());
                                            }
                                            case 1 -> {
                                                assertEquals(expectedStartEntry, tx.startEntry());
                                                assertEquals(expectedCommitEntry, tx.commitEntry());
                                            }
                                            default -> fail("Too many recovered transactions");
                                        }
                                    }
                                    return false;
                                }
                            };
                        }
                    },
                    logPruner,
                    schemaLife,
                    monitor,
                    ProgressReporter.SILENT,
                    false,
                    EMPTY_CHECKER,
                    RecoveryPredicate.ALL,
                    contextFactory));

            life.start();

            assertTrue(monitor.isRecoveryRequired());
            assertEquals(2, monitor.recoveredBatches());
        } finally {
            life.shutdown();
        }
    }

    @Test
    void shouldSeeThatACleanDatabaseShouldNotRequireRecovery() throws Exception {
        Path file = logFiles.getLogFile().getLogFileForVersion(logVersion);
        var contextFactory = new CursorContextFactory(NULL, EmptyVersionContextSupplier.EMPTY);

        LogPositionMarker marker = new LogPositionMarker();
        writeSomeDataWithVersion(file, dataWriters -> {
            LogEntryWriter<?> writer = dataWriters.writer();
            PositionAwareChannel channel = dataWriters.channel();
            byte version = LatestVersions.LATEST_KERNEL_VERSION.version();
            TransactionId transactionId = new TransactionId(4L, BASE_TX_CHECKSUM, 5L, 6L);

            // last committed tx
            channel.getCurrentPosition(marker);
            writer.writeStartEntry(version, 2L, 3L, BASE_TX_CHECKSUM, new byte[0]);
            writer.writeCommitEntry(version, 4L, 5L);

            // check point
            channel.getCurrentPosition(marker);
            var checkpointFile = logFiles.getCheckpointFile();
            var checkpointAppender = checkpointFile.getCheckpointAppender();
            checkpointAppender.checkPoint(
                    LogCheckPointEvent.NULL,
                    transactionId,
                    LatestVersions.LATEST_KERNEL_VERSION,
                    marker.newPosition(),
                    Instant.now(),
                    "test");
            return true;
        });

        LifeSupport life = new LifeSupport();
        RecoveryMonitor monitor = mock(RecoveryMonitor.class);
        try {
            StorageEngine storageEngine = mock(StorageEngine.class);
            Config config = Config.defaults();

            TransactionMetadataCache metadataCache = new TransactionMetadataCache();
            LogicalTransactionStore txStore = new PhysicalLogicalTransactionStore(
                    logFiles, metadataCache, new TestCommandReaderFactory(), monitors, false, config);
            CorruptedLogsTruncator logPruner = new CorruptedLogsTruncator(storeDir, logFiles, fileSystem, INSTANCE);
            monitors.addMonitorListener(new RecoveryMonitor() {
                @Override
                public void recoveryRequired(LogPosition recoveryPosition) {
                    fail("Recovery should not be required");
                }
            });
            life.add(new TransactionLogsRecovery(
                    new DefaultRecoveryService(
                            storageEngine,
                            transactionIdStore,
                            txStore,
                            versionRepository,
                            logFiles,
                            LatestVersions.LATEST_KERNEL_VERSION_PROVIDER,
                            NO_MONITOR,
                            mock(InternalLog.class),
                            Clocks.systemClock(),
                            false),
                    logPruner,
                    schemaLife,
                    monitor,
                    ProgressReporter.SILENT,
                    false,
                    EMPTY_CHECKER,
                    RecoveryPredicate.ALL,
                    contextFactory));

            life.start();

            verifyNoInteractions(monitor);
        } finally {
            life.shutdown();
        }
    }

    @Test
    void shouldTruncateLogAfterSinglePartialTransaction() throws Exception {
        // GIVEN
        Path file = logFiles.getLogFile().getLogFileForVersion(logVersion);
        final LogPositionMarker marker = new LogPositionMarker();

        writeSomeData(file, dataWriters -> {
            byte version = LatestVersions.LATEST_KERNEL_VERSION.version();
            LogEntryWriter<?> writer = dataWriters.writer();
            PositionAwareChannel channel = dataWriters.channel();

            // incomplete tx
            channel.getCurrentPosition(marker); // <-- marker has the last good position
            writer.writeStartEntry(version, 5L, 4L, 0, new byte[0]);

            return true;
        });

        // WHEN
        boolean recoveryRequired = recovery(storeDir);

        // THEN
        assertTrue(recoveryRequired);
        assertEquals(marker.getByteOffset(), Files.size(file));
    }

    @Test
    void doNotTruncateCheckpointsAfterLastTransaction() throws IOException {
        Path file = logFiles.getLogFile().getLogFileForVersion(logVersion);
        LogPositionMarker marker = new LogPositionMarker();
        writeSomeData(file, dataWriters -> {
            LogEntryWriter<?> writer = dataWriters.writer();
            PositionAwareChannel channel = dataWriters.channel();
            byte version = LatestVersions.LATEST_KERNEL_VERSION.version();
            writer.writeStartEntry(version, 1L, 1L, BASE_TX_CHECKSUM, ArrayUtils.EMPTY_BYTE_ARRAY);
            TransactionId transactionId = new TransactionId(1L, BASE_TX_CHECKSUM, 2L, 4L);

            writer.writeCommitEntry(version, 1L, 2L);
            channel.getCurrentPosition(marker);
            var checkpointFile = logFiles.getCheckpointFile();
            var checkpointAppender = checkpointFile.getCheckpointAppender();
            checkpointAppender.checkPoint(
                    LogCheckPointEvent.NULL,
                    transactionId,
                    LatestVersions.LATEST_KERNEL_VERSION,
                    marker.newPosition(),
                    Instant.now(),
                    "test");

            // write incomplete tx to trigger recovery
            writer.writeStartEntry(version, 5L, 4L, 0, new byte[0]);
            return true;
        });
        assertTrue(recovery(storeDir));

        assertEquals(marker.getByteOffset(), Files.size(file));
        assertEquals(
                CURRENT_FORMAT_LOG_HEADER_SIZE + RECORD_LENGTH_BYTES /* one checkpoint */,
                ((DetachedCheckpointAppender) logFiles.getCheckpointFile().getCheckpointAppender())
                        .getCurrentPosition());

        if (NativeAccessProvider.getNativeAccess().isAvailable()) {
            assertEquals(
                    ByteUnit.mebiBytes(1),
                    Files.size(logFiles.getCheckpointFile().getCurrentFile()));
        } else {
            assertEquals(
                    CURRENT_FORMAT_LOG_HEADER_SIZE + RECORD_LENGTH_BYTES /* one checkpoint */,
                    Files.size(logFiles.getCheckpointFile().getCurrentFile()));
        }
    }

    @Test
    void shouldTruncateInvalidCheckpointAndAllCorruptTransactions() throws IOException {
        Path file = logFiles.getLogFile().getLogFileForVersion(logVersion);
        LogPositionMarker marker = new LogPositionMarker();
        writeSomeData(file, dataWriters -> {
            LogEntryWriter<?> writer = dataWriters.writer();
            PositionAwareChannel channel = dataWriters.channel();
            byte version = LatestVersions.LATEST_KERNEL_VERSION.version();
            writer.writeStartEntry(version, 1L, 1L, BASE_TX_CHECKSUM, ArrayUtils.EMPTY_BYTE_ARRAY);
            writer.writeCommitEntry(version, 1L, 2L);
            TransactionId transactionId = new TransactionId(1L, BASE_TX_CHECKSUM, 2L, 3L);

            channel.getCurrentPosition(marker);
            var checkpointFile = logFiles.getCheckpointFile();
            var checkpointAppender = checkpointFile.getCheckpointAppender();
            checkpointAppender.checkPoint(
                    LogCheckPointEvent.NULL,
                    transactionId,
                    LatestVersions.LATEST_KERNEL_VERSION,
                    marker.newPosition(),
                    Instant.now(),
                    "valid checkpoint");
            checkpointAppender.checkPoint(
                    LogCheckPointEvent.NULL,
                    transactionId,
                    LatestVersions.LATEST_KERNEL_VERSION,
                    new LogPosition(marker.getLogVersion() + 1, marker.getByteOffset()),
                    Instant.now(),
                    "invalid checkpoint");

            // incomplete tx
            writer.writeStartEntry(version, 5L, 4L, 0, new byte[0]);
            return true;
        });
        assertTrue(recovery(storeDir));

        assertEquals(marker.getByteOffset(), Files.size(file));
        assertEquals(
                CURRENT_FORMAT_LOG_HEADER_SIZE + RECORD_LENGTH_BYTES /* one checkpoint */,
                Files.size(logFiles.getCheckpointFile().getCurrentFile()));
    }

    @Test
    void shouldTruncateLogAfterLastCompleteTransactionAfterSuccessfulRecovery() throws Exception {
        // GIVEN
        Path file = logFiles.getLogFile().getLogFileForVersion(logVersion);
        final LogPositionMarker marker = new LogPositionMarker();

        writeSomeData(file, dataWriters -> {
            LogEntryWriter<?> writer = dataWriters.writer();
            PositionAwareChannel channel = dataWriters.channel();
            byte version = LatestVersions.LATEST_KERNEL_VERSION.version();

            // last committed tx
            int previousChecksum = BASE_TX_CHECKSUM;
            writer.writeStartEntry(version, 2L, 3L, previousChecksum, new byte[0]);
            previousChecksum = writer.writeCommitEntry(version, 4L, 5L);

            // incomplete tx
            channel.getCurrentPosition(marker); // <-- marker has the last good position
            writer.writeStartEntry(version, 5L, 4L, previousChecksum, new byte[0]);

            return true;
        });

        // WHEN
        boolean recoveryRequired = recovery(storeDir);

        // THEN
        assertTrue(recoveryRequired);
        assertEquals(marker.getByteOffset(), Files.size(file));
    }

    @Test
    void shouldTellTransactionIdStoreAfterSuccessfulRecovery() throws Exception {
        // GIVEN
        Path file = logFiles.getLogFile().getLogFileForVersion(logVersion);
        final LogPositionMarker marker = new LogPositionMarker();

        final byte[] additionalHeaderData = new byte[0];
        final long transactionId = 4;
        final long commitTimestamp = 5;
        writeSomeData(file, dataWriters -> {
            LogEntryWriter<?> writer = dataWriters.writer();
            PositionAwareChannel channel = dataWriters.channel();
            byte version = LatestVersions.LATEST_KERNEL_VERSION.version();

            // last committed tx
            writer.writeStartEntry(version, 2L, 3L, BASE_TX_CHECKSUM, additionalHeaderData);
            writer.writeCommitEntry(version, transactionId, commitTimestamp);
            channel.getCurrentPosition(marker);

            return true;
        });

        // WHEN
        boolean recoveryRequired = recovery(storeDir);

        // THEN
        assertTrue(recoveryRequired);
        var lastClosedTransaction = transactionIdStore.getLastClosedTransaction();
        LogPosition logPosition = lastClosedTransaction.logPosition();
        assertEquals(transactionId, lastClosedTransaction.transactionId());
        assertEquals(
                commitTimestamp,
                transactionIdStore.getLastCommittedTransaction().commitTimestamp());
        assertEquals(logVersion, logPosition.getLogVersion());
        assertEquals(marker.getByteOffset(), logPosition.getByteOffset());
    }

    @Test
    void shouldInitSchemaLifeWhenRecoveryNotRequired() throws Exception {
        Lifecycle schemaLife = mock(Lifecycle.class);
        var contextFactory = new CursorContextFactory(NULL, EmptyVersionContextSupplier.EMPTY);

        RecoveryService recoveryService = mock(RecoveryService.class);
        when(recoveryService.getRecoveryStartInformation()).thenReturn(NO_RECOVERY_REQUIRED);

        CorruptedLogsTruncator logPruner = new CorruptedLogsTruncator(storeDir, logFiles, fileSystem, INSTANCE);
        RecoveryMonitor monitor = mock(RecoveryMonitor.class);

        TransactionLogsRecovery logsRecovery = new TransactionLogsRecovery(
                recoveryService,
                logPruner,
                schemaLife,
                monitor,
                ProgressReporter.SILENT,
                true,
                EMPTY_CHECKER,
                RecoveryPredicate.ALL,
                contextFactory);

        logsRecovery.init();

        verify(monitor, never()).recoveryRequired(any());
        verify(schemaLife).init();
    }

    @Test
    void shouldFailRecoveryWhenCanceled() throws Exception {
        Path file = logFiles.getLogFile().getLogFileForVersion(logVersion);
        final LogPositionMarker marker = new LogPositionMarker();

        final byte[] additionalHeaderData = new byte[0];
        final long transactionId = 4;
        final long commitTimestamp = 5;
        writeSomeData(file, writers -> {
            LogEntryWriter<?> writer = writers.writer();
            PositionAwareChannel channel = writers.channel();
            byte version = LatestVersions.LATEST_KERNEL_VERSION.version();

            // last committed tx
            writer.writeStartEntry(version, 2L, 3L, BASE_TX_CHECKSUM, additionalHeaderData);
            writer.writeCommitEntry(version, transactionId, commitTimestamp);
            channel.getCurrentPosition(marker);

            return true;
        });

        RecoveryMonitor monitor = mock(RecoveryMonitor.class);
        var startupController = mock(DatabaseStartupController.class);
        var databaseId = from("db", randomUUID());
        when(startupController.shouldAbortStartup()).thenReturn(false, true);
        var recoveryStartupChecker = new RecoveryStartupChecker(startupController, databaseId);
        var logsTruncator = mock(CorruptedLogsTruncator.class);

        assertThatThrownBy(() -> recovery(storeDir, recoveryStartupChecker))
                .rootCause()
                .isInstanceOf(DatabaseStartAbortedException.class);

        verify(logsTruncator, never()).truncate(any());
        verify(monitor, never()).recoveryCompleted(anyLong());
    }

    private boolean recovery(Path storeDir) throws IOException {
        return recovery(storeDir, EMPTY_CHECKER);
    }

    private boolean recovery(Path storeDir, RecoveryStartupChecker startupChecker) throws IOException {
        LifeSupport life = new LifeSupport();
        var contextFactory = new CursorContextFactory(NULL, EmptyVersionContextSupplier.EMPTY);

        final AtomicBoolean recoveryRequired = new AtomicBoolean();
        RecoveryMonitor monitor = new RecoveryMonitor() {
            @Override
            public void recoveryRequired(LogPosition recoveryPosition) {
                recoveryRequired.set(true);
            }
        };
        try {
            var logFiles = buildLogFiles();
            life.add(logFiles);
            StorageEngine storageEngine = mock(StorageEngine.class);
            when(storageEngine.createStorageCursors(any())).thenReturn(mock(StoreCursors.class));
            Config config = Config.defaults();

            TransactionMetadataCache metadataCache = new TransactionMetadataCache();
            LogicalTransactionStore txStore = new PhysicalLogicalTransactionStore(
                    logFiles, metadataCache, new TestCommandReaderFactory(), monitors, false, config);
            CorruptedLogsTruncator logPruner = new CorruptedLogsTruncator(storeDir, logFiles, fileSystem, INSTANCE);
            monitors.addMonitorListener(monitor);
            life.add(new TransactionLogsRecovery(
                    new DefaultRecoveryService(
                            storageEngine,
                            transactionIdStore,
                            txStore,
                            versionRepository,
                            logFiles,
                            LatestVersions.LATEST_KERNEL_VERSION_PROVIDER,
                            NO_MONITOR,
                            mock(InternalLog.class),
                            Clocks.systemClock(),
                            false),
                    logPruner,
                    schemaLife,
                    monitor,
                    ProgressReporter.SILENT,
                    false,
                    startupChecker,
                    RecoveryPredicate.ALL,
                    contextFactory));

            life.start();
        } finally {
            life.shutdown();
        }
        return recoveryRequired.get();
    }

    private void writeSomeData(Path file, Visitor<DataWriters, IOException> visitor) throws IOException {
        writeSomeDataWithVersion(file, visitor);
    }

    private void writeSomeDataWithVersion(Path file, Visitor<DataWriters, IOException> visitor) throws IOException {
        try (var versionedStoreChannel = new PhysicalLogVersionedStoreChannel(
                        fileSystem.write(file),
                        logVersion,
                        CURRENT_LOG_FORMAT_VERSION,
                        file,
                        EMPTY_ACCESSOR,
                        DatabaseTracer.NULL);
                var writableLogChannel = new PositionAwarePhysicalFlushableChecksumChannel(
                        versionedStoreChannel,
                        new HeapScopedBuffer(toIntExact(KibiByte.toBytes(1)), ByteOrder.LITTLE_ENDIAN, INSTANCE))) {
            writeLogHeader(
                    versionedStoreChannel,
                    new LogHeader(
                            CURRENT_LOG_FORMAT_VERSION,
                            new LogPosition(logVersion, CURRENT_FORMAT_LOG_HEADER_SIZE),
                            2L,
                            storeId,
                            UNKNOWN_LOG_SEGMENT_SIZE,
                            BASE_TX_CHECKSUM),
                    INSTANCE);
            writableLogChannel.beginChecksum();
            LogEntryWriter<?> first = new LogEntryWriter<>(writableLogChannel);
            visitor.visit(new DataWriters(first, writableLogChannel));
        }
    }

    private record DataWriters(LogEntryWriter<?> writer, PositionAwareChannel channel) {}

    private LogFiles buildLogFiles() throws IOException {
        return LogFilesBuilder.builder(databaseLayout, fileSystem, LatestVersions.LATEST_KERNEL_VERSION_PROVIDER)
                .withLogVersionRepository(logVersionRepository)
                .withTransactionIdStore(transactionIdStore)
                .withCommandReaderFactory(new TestCommandReaderFactory())
                .withStoreId(storeId)
                .withConfig(Config.newBuilder()
                        .set(GraphDatabaseInternalSettings.fail_on_corrupted_log_files, false)
                        .build())
                .build();
    }

    private static final class LogsRecoveryMonitor implements RecoveryMonitor {
        private int batchCounter;
        private boolean recoveryRequired;

        @Override
        public void batchRecovered(CommittedCommandBatch committedBatch) {
            batchCounter++;
        }

        @Override
        public void recoveryRequired(LogPosition recoveryPosition) {
            recoveryRequired = true;
        }

        public boolean isRecoveryRequired() {
            return recoveryRequired;
        }

        public int recoveredBatches() {
            return batchCounter;
        }
    }
}

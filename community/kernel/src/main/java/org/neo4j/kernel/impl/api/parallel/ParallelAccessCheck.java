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
package org.neo4j.kernel.impl.api.parallel;

import static org.neo4j.scheduler.Group.CYPHER_WORKER;
import static org.neo4j.util.FeatureToggles.flag;

import java.util.stream.Stream;
import org.neo4j.configuration.Config;
import org.neo4j.kernel.impl.api.LeaseClient;
import org.neo4j.kernel.impl.locking.Locks;
import org.neo4j.lock.ActiveLock;
import org.neo4j.lock.LockTracer;
import org.neo4j.lock.LockType;
import org.neo4j.lock.ResourceType;
import org.neo4j.memory.MemoryTracker;

public class ParallelAccessCheck {

    public static final boolean CHECK_PARALLEL_ACCESS = flag(ParallelAccessCheck.class, "CHECK_PARALLEL_ACCESS", false);

    public static boolean shouldPerformCheck() {
        return CHECK_PARALLEL_ACCESS;
    }

    public static void checkNotCypherWorkerThread() {
        if (Thread.currentThread().getThreadGroup().getName().equals(CYPHER_WORKER.groupName())) {
            throw new IllegalStateException(
                    "A resource that does not support parallel access is being accessed by a Cypher worker thread");
        }
    }

    public static Locks.Client maybeWrapLockClient(Locks.Client wrappedLockClient) {
        return new Locks.Client() {

            @Override
            public boolean tryExclusiveLock(ResourceType resourceType, long resourceId) {
                checkNotCypherWorkerThread();
                return wrappedLockClient.tryExclusiveLock(resourceType, resourceId);
            }

            @Override
            public void acquireExclusive(LockTracer tracer, ResourceType resourceType, long... resourceIds) {
                checkNotCypherWorkerThread();
                wrappedLockClient.acquireExclusive(tracer, resourceType, resourceIds);
            }

            @Override
            public void releaseExclusive(ResourceType resourceType, long... resourceIds) {
                checkNotCypherWorkerThread();
                wrappedLockClient.releaseExclusive(resourceType, resourceIds);
            }

            @Override
            public void acquireShared(LockTracer tracer, ResourceType resourceType, long... resourceIds) {
                checkNotCypherWorkerThread();
                wrappedLockClient.acquireShared(tracer, resourceType, resourceIds);
            }

            @Override
            public void releaseShared(ResourceType resourceType, long... resourceIds) {
                checkNotCypherWorkerThread();
                wrappedLockClient.releaseShared(resourceType, resourceIds);
            }

            @Override
            public Stream<ActiveLock> activeLocks() {
                checkNotCypherWorkerThread();
                return wrappedLockClient.activeLocks();
            }

            @Override
            public boolean holdsLock(long id, ResourceType resource, LockType lockType) {
                checkNotCypherWorkerThread();
                return wrappedLockClient.holdsLock(id, resource, lockType);
            }

            @Override
            public void initialize(
                    LeaseClient leaseClient, long transactionId, MemoryTracker memoryTracker, Config config) {
                checkNotCypherWorkerThread();
                wrappedLockClient.initialize(leaseClient, transactionId, memoryTracker, config);
            }

            @Override
            public boolean trySharedLock(ResourceType resourceType, long resourceId) {
                checkNotCypherWorkerThread();
                return wrappedLockClient.trySharedLock(resourceType, resourceId);
            }

            @Override
            public void prepareForCommit() {
                checkNotCypherWorkerThread();
                wrappedLockClient.prepareForCommit();
            }

            @Override
            public void stop() {
                checkNotCypherWorkerThread();
                wrappedLockClient.stop();
            }

            @Override
            public void close() {
                checkNotCypherWorkerThread();
                wrappedLockClient.close();
            }

            @Override
            public long getTransactionId() {
                checkNotCypherWorkerThread();
                return wrappedLockClient.getTransactionId();
            }

            @Override
            public long activeLockCount() {
                checkNotCypherWorkerThread();
                return wrappedLockClient.activeLockCount();
            }
        };
    }
}

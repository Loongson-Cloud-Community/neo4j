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
package org.neo4j.index.internal.gbptree;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.neo4j.test.Race.throwing;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.Test;
import org.neo4j.test.Race;

class TreeNodeLatchServiceTest extends LatchTestBase {
    @Test
    void shouldReturnSameLatchInstanceForAcquiredRead() {
        // given
        TreeNodeLatchService service = new TreeNodeLatchService();
        long treeNodeId = 456L;
        LongSpinLatch latch = service.acquireRead(treeNodeId);

        // when
        LongSpinLatch again = service.acquireRead(treeNodeId);

        // then
        assertSame(again, latch);
        latch.releaseRead();
        latch.releaseRead();
    }

    @Test
    void shouldReturnNewLatchInstanceForAcquireReadAfterPreviousReleased() {
        // given
        TreeNodeLatchService service = new TreeNodeLatchService();
        long treeNodeId = 123L;
        LongSpinLatch first = service.acquireRead(treeNodeId);
        first.releaseRead();

        // when
        LongSpinLatch second = service.acquireRead(treeNodeId);

        // then
        assertNotSame(second, first);
        second.releaseRead();
    }

    @Test
    void shouldReturnNewLatchInstanceForAcquireWriteAfterPreviousReleased() {
        // given
        TreeNodeLatchService service = new TreeNodeLatchService();
        long treeNodeId = 123L;
        LongSpinLatch first = service.acquireWrite(treeNodeId);
        first.releaseWrite();

        // when
        LongSpinLatch second = service.acquireWrite(treeNodeId);

        // then
        assertNotSame(second, first);
        second.releaseWrite();
    }

    @Test
    void shouldAcquireReadStressfully() throws Throwable {
        // given
        TreeNodeLatchService service = new TreeNodeLatchService();
        Race race = new Race().withMaxDuration(500, TimeUnit.MILLISECONDS);
        long treeNodeId = 5;
        LongAdder count = new LongAdder();
        race.addContestants(Runtime.getRuntime().availableProcessors(), () -> {
            LongSpinLatch latch = service.acquireRead(treeNodeId);
            latch.releaseRead();
            count.add(1);
        });

        // when
        race.go();

        // then
        assertTrue(count.sum() > 0);
        assertEquals(0, service.size());
    }

    @Test
    void shouldAcquireWriteStressfully() throws Throwable {
        // given
        TreeNodeLatchService service = new TreeNodeLatchService();
        Race race = new Race().withMaxDuration(500, TimeUnit.MILLISECONDS);
        long treeNodeId = 5;
        LongAdder count = new LongAdder();
        race.addContestants(Runtime.getRuntime().availableProcessors(), () -> {
            LongSpinLatch latch = service.acquireWrite(treeNodeId);
            latch.releaseWrite();
            count.add(1);
        });

        // when
        race.go();

        // then
        assertTrue(count.sum() > 0);
        assertEquals(0, service.size());
    }

    @Test
    void shouldAcquireAndReleaseReadsAndWritesStressfully() throws Throwable {
        // given
        TreeNodeLatchService service = new TreeNodeLatchService();
        Race race = new Race().withMaxDuration(500, TimeUnit.MILLISECONDS);
        AtomicLong reads = new AtomicLong();
        AtomicLong writes = new AtomicLong();
        race.addContestants(2, new Runnable() {
            private final ThreadLocalRandom random = ThreadLocalRandom.current();

            @Override
            public void run() {
                LongSpinLatch latch = service.acquireRead(random.nextLong(1, 100));
                latch.releaseRead();
                reads.incrementAndGet();
            }
        });
        race.addContestants(2, new Runnable() {
            private final ThreadLocalRandom random = ThreadLocalRandom.current();

            @Override
            public void run() {
                LongSpinLatch latch = service.acquireWrite(random.nextLong(1, 100));
                latch.releaseWrite();
                writes.incrementAndGet();
            }
        });

        // when
        race.go();

        // then
        assertEquals(0, service.size());
        assertTrue(reads.get() > 0);
        assertTrue(writes.get() > 0);
    }

    @Test
    void shouldAcquireSameWriteLatchConcurrently() {
        // given
        TreeNodeLatchService service = new TreeNodeLatchService();
        long id = 999;
        Race race = new Race().withEndCondition(() -> false);
        AtomicInteger concurrent = new AtomicInteger();
        race.addContestants(
                4,
                throwing(() -> {
                    LongSpinLatch latch = service.acquireWrite(id);
                    assertThat(concurrent.incrementAndGet()).isOne();
                    concurrent.decrementAndGet();
                    latch.releaseWrite();
                }),
                1_000);
        race.goUnchecked();
    }

    @Test
    void shouldStressRandomAcquisitionsAndReleases() {
        // given
        var service = new TreeNodeLatchService();
        var treeNodeId = 99;
        var race = new Race().withMaxDuration(1, TimeUnit.SECONDS);
        // READ
        var reads = new AtomicInteger();
        var upgrades = new AtomicInteger();
        var writes = new AtomicInteger();
        var numCurrentWriteOwners = new AtomicInteger();
        var numCurrentReadOwners = new AtomicInteger();
        race.addContestants(2, () -> {
            var latch = service.acquireRead(treeNodeId);
            assertThat(numCurrentReadOwners.incrementAndGet()).isGreaterThanOrEqualTo(1);
            assertThat(numCurrentWriteOwners.get()).isZero();
            assertThat(numCurrentReadOwners.decrementAndGet()).isGreaterThanOrEqualTo(0);
            latch.releaseRead();
            reads.incrementAndGet();
        });
        // UPGRADE
        race.addContestant(() -> {
            var latch = service.acquireRead(treeNodeId);
            assertThat(numCurrentReadOwners.incrementAndGet()).isGreaterThanOrEqualTo(1);
            assertThat(numCurrentWriteOwners.get()).isZero();
            if (latch.tryUpgradeToWrite()) {
                assertThat(numCurrentWriteOwners.incrementAndGet()).isOne();
                assertThat(numCurrentReadOwners.decrementAndGet()).isZero();
                assertThat(numCurrentWriteOwners.decrementAndGet()).isZero();
                latch.releaseWrite();
                upgrades.incrementAndGet();
            } else {
                assertThat(numCurrentReadOwners.decrementAndGet()).isGreaterThanOrEqualTo(0);
                latch.releaseRead();
            }
        });
        // WRITE
        race.addContestant(() -> {
            var latch = service.acquireWrite(treeNodeId);
            assertThat(numCurrentWriteOwners.incrementAndGet()).isOne();
            assertThat(numCurrentWriteOwners.decrementAndGet()).isZero();
            latch.releaseWrite();
            writes.incrementAndGet();
        });

        // when
        race.goUnchecked();

        // then
        assertTrue(reads.get() > 0);
        assertTrue(upgrades.get() > 0);
        assertTrue(writes.get() > 0);
    }
}

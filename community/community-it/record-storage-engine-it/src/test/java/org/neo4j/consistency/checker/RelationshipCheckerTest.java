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

import static org.mockito.ArgumentMatchers.any;
import static org.neo4j.internal.recordstorage.RecordCursorTypes.RELATIONSHIP_CURSOR;

import org.junit.jupiter.api.Test;
import org.neo4j.consistency.report.ConsistencyReport.NodeConsistencyReport;
import org.neo4j.consistency.report.ConsistencyReport.RelationshipConsistencyReport;
import org.neo4j.exceptions.KernelException;
import org.neo4j.internal.helpers.collection.LongRange;
import org.neo4j.io.pagecache.context.CursorContext;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.impl.store.record.RelationshipRecord;

class RelationshipCheckerTest extends CheckerTestBase {
    private int type;

    @Override
    void initialData(KernelTransaction tx) throws KernelException {
        type = tx.tokenWrite().relationshipTypeGetOrCreateForName("A");
    }

    @Test
    void shouldReportSourceNodeNotInUse() throws Exception {
        // given
        try (AutoCloseable ignored = tx()) {
            long relationship = relationshipStore.nextId(CursorContext.NULL_CONTEXT);
            long node = nodePlusCached(nodeStore.nextId(CursorContext.NULL_CONTEXT), NULL, relationship);
            relationship(
                    relationship,
                    nodeStore.nextId(CursorContext.NULL_CONTEXT),
                    node,
                    type,
                    NULL,
                    NULL,
                    NULL,
                    NULL,
                    true,
                    true);
        }

        // when
        check();

        // then
        expect(RelationshipConsistencyReport.class, report -> report.sourceNodeNotInUse(any()));
    }

    @Test
    void shouldReportTargetNodeNotInUse() throws Exception {
        // given
        try (AutoCloseable ignored = tx()) {
            long relationship = relationshipStore.nextId(CursorContext.NULL_CONTEXT);
            long node = nodePlusCached(nodeStore.nextId(CursorContext.NULL_CONTEXT), NULL, relationship);
            relationship(
                    relationship,
                    node,
                    nodeStore.nextId(CursorContext.NULL_CONTEXT),
                    type,
                    NULL,
                    NULL,
                    NULL,
                    NULL,
                    true,
                    true);
        }

        // when
        check();

        // then
        expect(RelationshipConsistencyReport.class, report -> report.targetNodeNotInUse(any()));
    }

    @Test
    void shouldReportSourceNodeNotInUseWhenAboveHighId() throws Exception {
        // given
        try (AutoCloseable ignored = tx()) {
            long relationship = relationshipStore.nextId(CursorContext.NULL_CONTEXT);
            long node = nodePlusCached(nodeStore.nextId(CursorContext.NULL_CONTEXT), NULL, relationship);
            relationship(relationship, node + 10, node, type, NULL, NULL, NULL, NULL, true, true);
        }

        // when
        check();

        // then
        expect(RelationshipConsistencyReport.class, report -> report.sourceNodeNotInUse(any()));
    }

    @Test
    void shouldReportTargetNodeNotInUseWhenAboveHighId() throws Exception {
        // given
        try (AutoCloseable ignored = tx()) {
            long relationship = relationshipStore.nextId(CursorContext.NULL_CONTEXT);
            long node = nodePlusCached(nodeStore.nextId(CursorContext.NULL_CONTEXT), NULL, relationship);
            relationship(relationship, node, node + 10, type, NULL, NULL, NULL, NULL, true, true);
        }

        // when
        check();

        // then
        expect(RelationshipConsistencyReport.class, report -> report.targetNodeNotInUse(any()));
    }

    @Test
    void shouldReportSourceNodeDoesNotReferenceBack() throws Exception {
        // given
        try (AutoCloseable ignored = tx()) {
            long relationship = relationshipStore.nextId(CursorContext.NULL_CONTEXT);
            long node1 = nodePlusCached(
                    nodeStore.nextId(CursorContext.NULL_CONTEXT),
                    NULL,
                    relationshipStore.nextId(CursorContext.NULL_CONTEXT));
            long node2 = nodePlusCached(nodeStore.nextId(CursorContext.NULL_CONTEXT), NULL, relationship);
            relationship(relationship, node1, node2, type, NULL, NULL, NULL, NULL, true, true);
        }

        // when
        check();

        // then
        expect(RelationshipConsistencyReport.class, report -> report.sourceNodeDoesNotReferenceBack(any()));
    }

    @Test
    void shouldReportTargetNodeDoesNotReferenceBack() throws Exception {
        // given
        try (AutoCloseable ignored = tx()) {
            long relationship = relationshipStore.nextId(CursorContext.NULL_CONTEXT);
            long node1 = nodePlusCached(nodeStore.nextId(CursorContext.NULL_CONTEXT), NULL, relationship);
            long node2 = nodePlusCached(
                    nodeStore.nextId(CursorContext.NULL_CONTEXT),
                    NULL,
                    relationshipStore.nextId(CursorContext.NULL_CONTEXT));
            relationship(relationship, node1, node2, type, NULL, NULL, NULL, NULL, true, true);
        }

        // when
        check();

        // then
        expect(RelationshipConsistencyReport.class, report -> report.targetNodeDoesNotReferenceBack(any()));
    }

    @Test
    void shouldReportRelationshipNotFirstInSourceChain() throws Exception {
        // given
        try (AutoCloseable ignored = tx()) {
            long relationship = relationshipStore.nextId(CursorContext.NULL_CONTEXT);
            long node1 = nodePlusCached(nodeStore.nextId(CursorContext.NULL_CONTEXT), NULL, relationship);
            long node2 = nodePlusCached(nodeStore.nextId(CursorContext.NULL_CONTEXT), NULL, relationship);
            relationship(relationship, node1, node2, type, NULL, NULL, NULL, NULL, false, true);
        }

        // when
        check();

        // then
        expect(NodeConsistencyReport.class, report -> report.relationshipNotFirstInSourceChain(any()));
    }

    @Test
    void shouldReportRelationshipNotFirstInTargetChain() throws Exception {
        // given
        try (AutoCloseable ignored = tx()) {
            long relationship = relationshipStore.nextId(CursorContext.NULL_CONTEXT);
            long node1 = nodePlusCached(nodeStore.nextId(CursorContext.NULL_CONTEXT), NULL, relationship);
            long node2 = nodePlusCached(nodeStore.nextId(CursorContext.NULL_CONTEXT), NULL, relationship);
            relationship(relationship, node1, node2, type, NULL, NULL, NULL, NULL, true, false);
        }

        // when
        check();

        // then
        expect(NodeConsistencyReport.class, report -> report.relationshipNotFirstInTargetChain(any()));
    }

    @Test
    void shouldReportSourceNodeHasNoRelationships() throws Exception {
        // given
        try (AutoCloseable ignored = tx()) {
            long relationship = relationshipStore.nextId(CursorContext.NULL_CONTEXT);
            long node1 = nodePlusCached(nodeStore.nextId(CursorContext.NULL_CONTEXT), NULL, NULL);
            long node2 = nodePlusCached(nodeStore.nextId(CursorContext.NULL_CONTEXT), NULL, relationship);
            relationship(relationship, node1, node2, type, NULL, NULL, NULL, NULL, true, true);
        }

        // when
        check();

        // then
        expect(RelationshipConsistencyReport.class, report -> report.sourceNodeHasNoRelationships(any()));
    }

    @Test
    void shouldReportTargetNodeHasNoRelationships() throws Exception {
        // given
        try (AutoCloseable ignored = tx()) {
            long relationship = relationshipStore.nextId(CursorContext.NULL_CONTEXT);
            long node1 = nodePlusCached(nodeStore.nextId(CursorContext.NULL_CONTEXT), NULL, relationship);
            long node2 = nodePlusCached(nodeStore.nextId(CursorContext.NULL_CONTEXT), NULL, NULL);
            relationship(relationship, node1, node2, type, NULL, NULL, NULL, NULL, true, true);
        }

        // when
        check();

        // then
        expect(RelationshipConsistencyReport.class, report -> report.targetNodeHasNoRelationships(any()));
    }

    @Test
    void shouldReportRelationshipTypeNotInUse() throws Exception {
        // given
        try (AutoCloseable ignored = tx()) {
            long relationship = relationshipStore.nextId(CursorContext.NULL_CONTEXT);
            long node1 = nodePlusCached(nodeStore.nextId(CursorContext.NULL_CONTEXT), NULL, relationship);
            long node2 = nodePlusCached(nodeStore.nextId(CursorContext.NULL_CONTEXT), NULL, relationship);
            relationship(relationship, node1, node2, type + 1, NULL, NULL, NULL, NULL, true, true);
        }

        // when
        check();

        // then
        expect(RelationshipConsistencyReport.class, report -> report.relationshipTypeNotInUse(any()));
    }

    @Test
    void shouldReportRelationshipWithIdForReuse() throws Exception {
        // Given
        long relId;
        try (AutoCloseable ignored = tx()) {
            long relationship = relationshipStore.nextId(CursorContext.NULL_CONTEXT);
            long node1 = nodePlusCached(nodeStore.nextId(CursorContext.NULL_CONTEXT), NULL, relationship);
            long node2 = nodePlusCached(nodeStore.nextId(CursorContext.NULL_CONTEXT), NULL, relationship);
            relId = relationship(relationship, node1, node2, type, NULL, NULL, NULL, NULL, true, true);
        }

        markAsDeletedId(relationshipStore, relId);

        // when
        check();

        // then
        expect(RelationshipConsistencyReport.class, RelationshipConsistencyReport::idIsFreed);
    }

    @Test
    void shouldReportDeletedRelationshipWithIdNotForReuse() throws Exception {
        // Given
        long relId;
        try (AutoCloseable ignored = tx()) {
            long relationship = relationshipStore.nextId(CursorContext.NULL_CONTEXT);
            long node1 = nodePlusCached(nodeStore.nextId(CursorContext.NULL_CONTEXT), NULL, relationship);
            long node2 = nodePlusCached(nodeStore.nextId(CursorContext.NULL_CONTEXT), NULL, relationship);
            relId = relationship(relationship, node1, node2, type, NULL, NULL, NULL, NULL, true, true);
        }
        try (AutoCloseable ignored = tx()) {
            try (var storeCursor = storeCursors.writeCursor(RELATIONSHIP_CURSOR)) {
                relationshipStore.updateRecord(
                        new RelationshipRecord(relId), storeCursor, CursorContext.NULL_CONTEXT, storeCursors);
            }
        }

        markAsUsedId(relationshipStore, relId);

        // when
        check();

        // then
        expect(RelationshipConsistencyReport.class, RelationshipConsistencyReport::idIsNotFreed);
    }

    private void check() throws Exception {
        check(context());
    }

    private void check(CheckerContext checkerContext) throws Exception {
        new RelationshipChecker(checkerContext, noMandatoryProperties)
                .check(LongRange.range(0, nodeStore.getHighId()), true, true);
    }
}

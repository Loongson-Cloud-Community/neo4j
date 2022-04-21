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
package org.neo4j.internal.batchimport.input;

import static org.neo4j.internal.recordstorage.RecordCursorTypes.PROPERTY_CURSOR;
import static org.neo4j.io.IOUtils.closeAllUnchecked;

import java.io.IOException;
import org.eclipse.collections.api.set.primitive.MutableLongSet;
import org.eclipse.collections.impl.factory.primitive.LongSets;
import org.neo4j.common.EntityType;
import org.neo4j.internal.batchimport.ReadBehaviour;
import org.neo4j.io.pagecache.PageCursor;
import org.neo4j.io.pagecache.context.CursorContext;
import org.neo4j.io.pagecache.context.CursorContextFactory;
import org.neo4j.kernel.impl.store.PropertyStore;
import org.neo4j.kernel.impl.store.record.PrimitiveRecord;
import org.neo4j.kernel.impl.store.record.PropertyBlock;
import org.neo4j.kernel.impl.store.record.PropertyRecord;
import org.neo4j.kernel.impl.store.record.Record;
import org.neo4j.kernel.impl.store.record.RecordLoad;
import org.neo4j.storageengine.api.cursor.StoreCursors;
import org.neo4j.token.TokenHolders;
import org.neo4j.values.storable.Value;

public abstract class LenientStoreInputChunk implements InputChunk {
    private static final String COPY_STORE_READER_TAG = "copyStoreReader";
    private final PropertyStore propertyStore;
    private final StoreCursors storeCursors;
    private long id;
    private long endId;

    protected final ReadBehaviour readBehaviour;
    protected final TokenHolders tokenHolders;
    protected final PageCursor cursor;
    private final CursorContext cursorContext;
    private final MutableLongSet seenPropertyRecordIds = LongSets.mutable.empty();
    private final PageCursor propertyCursor;
    private final PropertyRecord propertyRecord;

    LenientStoreInputChunk(
            ReadBehaviour readBehaviour,
            PropertyStore propertyStore,
            TokenHolders tokenHolders,
            CursorContextFactory contextFactory,
            StoreCursors storeCursors,
            PageCursor cursor) {
        this.readBehaviour = readBehaviour;
        this.propertyStore = propertyStore;
        this.tokenHolders = tokenHolders;
        this.cursorContext = contextFactory.create(COPY_STORE_READER_TAG);
        this.storeCursors = storeCursors;
        this.cursor = cursor;
        this.propertyCursor = storeCursors.readCursor(PROPERTY_CURSOR);
        this.propertyRecord = propertyStore.newRecord();
    }

    void setChunkRange(long startId, long endId) {
        this.id = startId;
        this.endId = endId;
    }

    @Override
    public boolean next(InputEntityVisitor visitor) {
        if (id < endId) {
            try {
                readAndVisit(id, visitor, storeCursors);
            } catch (Exception e) {
                readBehaviour.removed();
                readBehaviour.error(e, "%s(%d): Ignoring broken record.", recordType(), id);
            }
            id++;
            return true;
        }

        return false;
    }

    @Override
    public void close() {
        closeAllUnchecked(storeCursors, cursorContext);
    }

    abstract void readAndVisit(long id, InputEntityVisitor visitor, StoreCursors storeCursors) throws IOException;

    abstract String recordType();

    abstract boolean shouldIncludeProperty(ReadBehaviour readBehaviour, String key, String[] owningEntityTokens);

    /**
     * Do to the way the visitor work it's important that this method never throws.
     */
    void visitPropertyChainNoThrow(
            InputEntityVisitor visitor,
            PrimitiveRecord record,
            EntityType owningEntityType,
            String[] owningEntityTokens) {
        try {
            if (record.getNextProp() == Record.NO_NEXT_PROPERTY.intValue()) {
                return;
            }

            // We're detecting property record chain loops in this method, so prepare the set by clearing it
            seenPropertyRecordIds.clear();

            long nextProp = record.getNextProp();
            while (!Record.NO_NEXT_PROPERTY.is(nextProp)) {
                if (!seenPropertyRecordIds.add(nextProp)) {
                    readBehaviour.error(
                            "%s(%d): Ignoring circular property chain %s.",
                            owningEntityType, record.getId(), propertyRecord);
                    return;
                }

                propertyStore.getRecordByCursor(nextProp, propertyRecord, RecordLoad.NORMAL, propertyCursor);
                for (PropertyBlock propBlock : propertyRecord) {
                    propertyStore.ensureHeavy(propBlock, storeCursors);
                    String key = LenientStoreInput.getTokenByIdSafe(
                                    tokenHolders.propertyKeyTokens(), propBlock.getKeyIndexId())
                            .name();
                    if (shouldIncludeProperty(readBehaviour, key, owningEntityTokens)) {
                        Value propertyValue = propBlock.newPropertyValue(propertyStore, storeCursors);
                        visitor.property(key, propertyValue.asObject());
                    }
                }
                nextProp = propertyRecord.getNextProp();
            }
        } catch (Exception e) {
            readBehaviour.error(e, "%s(%d): Ignoring broken property chain.", owningEntityType, record.getId());
        }
    }
}

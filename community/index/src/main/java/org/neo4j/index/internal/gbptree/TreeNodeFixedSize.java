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

import static java.lang.String.format;
import static org.neo4j.index.internal.gbptree.Layout.FIXED_SIZE_KEY;
import static org.neo4j.index.internal.gbptree.Layout.FIXED_SIZE_VALUE;
import static org.neo4j.index.internal.gbptree.TreeNode.Type.INTERNAL;
import static org.neo4j.index.internal.gbptree.TreeNode.Type.LEAF;

import java.io.IOException;
import java.io.UncheckedIOException;
import org.neo4j.io.pagecache.PageCursor;
import org.neo4j.io.pagecache.context.CursorContext;

/**
 * <p>
 * DESIGN
 * <p>
 * Using Separate design the internal nodes should look like
 * <pre>
 * # = empty space
 *
 * [                                   HEADER   82B                           ]|[   KEYS   ]|[     CHILDREN      ]
 * [NODETYPE][TYPE][GENERATION][KEYCOUNT][RIGHTSIBLING][LEFTSIBLING][SUCCESSOR]|[[KEY]...##]|[[CHILD][CHILD]...##]
 *  0         1     2           6         10            34           58          82
 * </pre>
 * Calc offset for key i (starting from 0)
 * HEADER_LENGTH + i * SIZE_KEY
 * <p>
 * Calc offset for child i
 * HEADER_LENGTH + SIZE_KEY * MAX_KEY_COUNT_INTERNAL + i * SIZE_CHILD
 * <p>
 * Using Separate design the leaf nodes should look like
 *
 * <pre>
 * [                                   HEADER   82B                           ]|[    KEYS  ]|[   VALUES   ]
 * [NODETYPE][TYPE][GENERATION][KEYCOUNT][RIGHTSIBLING][LEFTSIBLING][SUCCESSOR]|[[KEY]...##]|[[VALUE]...##]
 *  0         1     2           6         10            34           58          82
 * </pre>
 *
 * Calc offset for key i (starting from 0)
 * HEADER_LENGTH + i * SIZE_KEY
 * <p>
 * Calc offset for value i
 * HEADER_LENGTH + SIZE_KEY * MAX_KEY_COUNT_LEAF + i * SIZE_VALUE
 *
 * @param <KEY> type of key
 * @param <VALUE> type of value
 */
class TreeNodeFixedSize<KEY, VALUE> extends TreeNode<KEY, VALUE> {
    static final byte FORMAT_IDENTIFIER = 2;
    static final byte FORMAT_VERSION = 0;

    private final int internalMaxKeyCount;
    private final int leafMaxKeyCount;
    private final int keySize;
    protected final int valueSize;
    private final int maxKeyCount;
    private final int halfLeafSpace;

    TreeNodeFixedSize(int pageSize, Layout<KEY, VALUE> layout) {
        this(pageSize, layout, 0);
    }

    /**
     * @param pageSize - page size
     * @param layout - layout
     * @param valuePadding - extra bytes allocated for each value, can be used by descendants to store additional data
     */
    TreeNodeFixedSize(int pageSize, Layout<KEY, VALUE> layout, int valuePadding) {
        super(pageSize, layout);
        this.keySize = layout.keySize(null);
        this.valueSize = layout.valueSize(null) + valuePadding;
        this.internalMaxKeyCount =
                Math.floorDiv(pageSize - (BASE_HEADER_LENGTH + SIZE_PAGE_REFERENCE), keySize + SIZE_PAGE_REFERENCE);
        this.leafMaxKeyCount = Math.floorDiv(pageSize - BASE_HEADER_LENGTH, keySize + valueSize);
        this.maxKeyCount = Math.max(internalMaxKeyCount, leafMaxKeyCount);
        int halfLeafKeyCount = (leafMaxKeyCount + 1) / 2;
        this.halfLeafSpace = halfLeafKeyCount * (keySize + valueSize);

        if (internalMaxKeyCount < 2) {
            throw new MetadataMismatchException(format(
                    "For layout %s a page size of %d would only fit %d internal keys, minimum is 2",
                    layout, pageSize, internalMaxKeyCount));
        }
        if (leafMaxKeyCount < 2) {
            throw new MetadataMismatchException(format(
                    "A page size of %d would only fit %d leaf keys (keySize:%d, valueSize:%d), minimum is 2",
                    pageSize, leafMaxKeyCount, keySize, valueSize));
        }
    }

    @Override
    void writeAdditionalHeader(PageCursor cursor) { // no-op
    }

    @Override
    long offloadIdAt(PageCursor cursor, int pos, Type type) {
        return NO_OFFLOAD_ID;
    }

    private static int childSize() {
        return SIZE_PAGE_REFERENCE;
    }

    @Override
    KEY keyAt(PageCursor cursor, KEY into, int pos, Type type, CursorContext cursorContext) {
        cursor.setOffset(keyOffset(pos));
        layout.readKey(cursor, into, FIXED_SIZE_KEY);
        return into;
    }

    @Override
    void keyValueAt(PageCursor cursor, KEY intoKey, ValueHolder<VALUE> intoValue, int pos, CursorContext cursorContext)
            throws IOException {
        keyAt(cursor, intoKey, pos, LEAF, cursorContext);
        valueAt(cursor, intoValue, pos, cursorContext);
    }

    @Override
    void insertKeyAndRightChildAt(
            PageCursor cursor,
            KEY key,
            long child,
            int pos,
            int keyCount,
            long stableGeneration,
            long unstableGeneration,
            CursorContext cursorContext) {
        insertKeyAt(cursor, key, pos, keyCount);
        insertChildAt(cursor, child, pos + 1, keyCount, stableGeneration, unstableGeneration);
    }

    @Override
    void insertKeyValueAt(
            PageCursor cursor,
            KEY key,
            VALUE value,
            int pos,
            int keyCount,
            long stableGeneration,
            long unstableGeneration,
            CursorContext cursorContext)
            throws IOException {
        insertKeyAt(cursor, key, pos, keyCount);
        insertValueAt(cursor, value, pos, keyCount, cursorContext, stableGeneration, unstableGeneration);
    }

    @Override
    int removeKeyValueAt(
            PageCursor cursor,
            int pos,
            int keyCount,
            long stableGeneration,
            long unstableGeneration,
            CursorContext cursorContext)
            throws IOException {
        removeKeyAt(cursor, pos, keyCount);
        removeValueAt(cursor, pos, keyCount);
        return keyCount - 1;
    }

    @Override
    void removeKeyAndLeftChildAt(
            PageCursor cursor,
            int keyPos,
            int keyCount,
            long stableGeneration,
            long unstableGeneration,
            CursorContext cursorContext) {
        removeKeyAt(cursor, keyPos, keyCount);
        removeChildAt(cursor, keyPos, keyCount);
    }

    @Override
    void removeKeyAndRightChildAt(
            PageCursor cursor,
            int keyPos,
            int keyCount,
            long stableGeneration,
            long unstableGeneration,
            CursorContext cursorContext) {
        removeKeyAt(cursor, keyPos, keyCount);
        removeChildAt(cursor, keyPos + 1, keyCount);
    }

    @Override
    boolean setKeyAtInternal(PageCursor cursor, KEY key, int pos) {
        cursor.setOffset(keyOffset(pos));
        layout.writeKey(cursor, key);
        return true;
    }

    @Override
    ValueHolder<VALUE> valueAt(PageCursor cursor, ValueHolder<VALUE> value, int pos, CursorContext cursorContext)
            throws IOException {
        cursor.setOffset(valueOffset(pos));
        layout.readValue(cursor, value.value, FIXED_SIZE_VALUE);
        value.defined = true;
        return value;
    }

    @Override
    boolean setValueAt(
            PageCursor cursor,
            VALUE value,
            int pos,
            CursorContext cursorContext,
            long stableGeneration,
            long unstableGeneration)
            throws IOException {
        cursor.setOffset(valueOffset(pos));
        layout.writeValue(cursor, value);
        return true;
    }

    @Override
    void setChildAt(PageCursor cursor, long child, int pos, long stableGeneration, long unstableGeneration) {
        int childOffset = childOffset(pos);
        cursor.setOffset(childOffset);
        writeChild(cursor, child, stableGeneration, unstableGeneration, pos, childOffset);
    }

    @Override
    int keyValueSizeCap() {
        return NO_KEY_VALUE_SIZE_CAP;
    }

    @Override
    int inlineKeyValueSizeCap() {
        return keyValueSizeCap();
    }

    @Override
    void validateKeyValueSize(KEY key, VALUE value) { // no-op for fixed size
    }

    @Override
    boolean reasonableKeyCount(int keyCount) {
        return keyCount >= 0 && keyCount <= maxKeyCount;
    }

    @Override
    boolean reasonableChildCount(int childCount) {
        return childCount >= 0 && childCount <= internalMaxKeyCount();
    }

    @Override
    int childOffset(int pos) {
        assert pos >= 0 : pos;
        return BASE_HEADER_LENGTH + internalMaxKeyCount * keySize + pos * SIZE_PAGE_REFERENCE;
    }

    int internalMaxKeyCount() {
        return internalMaxKeyCount;
    }

    private void insertKeyAt(PageCursor cursor, KEY key, int pos, int keyCount) {
        insertKeySlotsAt(cursor, pos, 1, keyCount);
        cursor.setOffset(keyOffset(pos));
        layout.writeKey(cursor, key);
    }

    protected int leafMaxKeyCount() {
        return leafMaxKeyCount;
    }

    private void removeKeyAt(PageCursor cursor, int pos, int keyCount) {
        removeSlotAt(cursor, pos, keyCount, keyOffset(0), keySize);
    }

    private void insertChildAt(
            PageCursor cursor, long child, int pos, int keyCount, long stableGeneration, long unstableGeneration) {
        insertChildSlot(cursor, pos, keyCount);
        setChildAt(cursor, child, pos, stableGeneration, unstableGeneration);
    }

    private void removeChildAt(PageCursor cursor, int pos, int keyCount) {
        removeSlotAt(cursor, pos, keyCount + 1, childOffset(0), childSize());
    }

    private void insertKeyValueSlots(PageCursor cursor, int numberOfSlots, int keyCount) {
        insertKeySlotsAt(cursor, 0, numberOfSlots, keyCount);
        insertValueSlotsAt(cursor, 0, numberOfSlots, keyCount);
    }

    // Always insert together with key. Use insertKeyValueAt
    protected void insertValueAt(
            PageCursor cursor,
            VALUE value,
            int pos,
            int keyCount,
            CursorContext cursorContext,
            long stableGeneration,
            long unstableGeneration)
            throws IOException {
        insertValueSlotsAt(cursor, pos, 1, keyCount);
        setValueAt(cursor, value, pos, cursorContext, stableGeneration, unstableGeneration);
    }

    // Always insert together with key. Use removeKeyValueAt
    private void removeValueAt(PageCursor cursor, int pos, int keyCount) {
        removeSlotAt(cursor, pos, keyCount, valueOffset(0), valueSize);
    }

    private void insertKeySlotsAt(PageCursor cursor, int pos, int numberOfSlots, int keyCount) {
        insertSlotsAt(cursor, pos, numberOfSlots, keyCount, keyOffset(0), keySize);
    }

    protected void insertValueSlotsAt(PageCursor cursor, int pos, int numberOfSlots, int keyCount) {
        insertSlotsAt(cursor, pos, numberOfSlots, keyCount, valueOffset(0), valueSize);
    }

    private void insertChildSlot(PageCursor cursor, int pos, int keyCount) {
        insertSlotsAt(cursor, pos, 1, keyCount + 1, childOffset(0), childSize());
    }

    private int keyOffset(int pos) {
        return BASE_HEADER_LENGTH + pos * keySize;
    }

    protected int valueOffset(int pos) {
        return BASE_HEADER_LENGTH + leafMaxKeyCount * keySize + pos * valueSize;
    }

    private int keySize() {
        return keySize;
    }

    private int valueSize() {
        return valueSize;
    }

    /* SPLIT, MERGE and REBALANCE*/

    @Override
    Overflow internalOverflow(PageCursor cursor, int currentKeyCount, KEY newKey) {
        return currentKeyCount + 1 > internalMaxKeyCount() ? Overflow.YES : Overflow.NO;
    }

    @Override
    Overflow leafOverflow(PageCursor cursor, int currentKeyCount, KEY newKey, VALUE newValue) {
        return currentKeyCount + 1 > leafMaxKeyCount() ? Overflow.YES : Overflow.NO;
    }

    @Override
    int availableSpace(PageCursor cursor, int currentKeyCount) {
        boolean isInternal = isInternal(cursor);
        return isInternal
                ? internalMaxKeyCount - currentKeyCount * (keySize + childSize())
                : leafAvailableSpace(currentKeyCount);
    }

    protected int leafAvailableSpace(int currentKeyCount) {
        return (leafMaxKeyCount() - currentKeyCount) * (keySize + valueSize);
    }

    @Override
    int totalSpaceOfKeyValue(KEY key, VALUE value) {
        return keySize + valueSize;
    }

    @Override
    int totalSpaceOfKeyChild(KEY key) {
        return keySize + childSize();
    }

    @Override
    int leafUnderflowThreshold() {
        return halfLeafSpace;
    }

    @Override
    void defragmentLeaf(PageCursor cursor) { // no-op
    }

    @Override
    void defragmentInternal(PageCursor cursor) { // no-op
    }

    @Override
    boolean leafUnderflow(PageCursor cursor, int keyCount) {
        return leafAvailableSpace(keyCount) > halfLeafSpace;
    }

    @Override
    int canRebalanceLeaves(PageCursor leftCursor, int leftKeyCount, PageCursor rightCursor, int rightKeyCount) {
        if (leftKeyCount + rightKeyCount >= leafMaxKeyCount()) {
            int totalKeyCount = rightKeyCount + leftKeyCount;
            int moveFromPosition = totalKeyCount / 2;
            return leftKeyCount - moveFromPosition;
        }
        return -1;
    }

    @Override
    boolean canMergeLeaves(PageCursor leftCursor, int leftKeyCount, PageCursor rightCursor, int rightKeyCount) {
        return leftKeyCount + rightKeyCount <= leafMaxKeyCount();
    }

    @Override
    int findSplitter(
            PageCursor cursor,
            int keyCount,
            KEY newKey,
            VALUE newValue,
            int insertPos,
            KEY newSplitter,
            double ratioToKeepInLeftOnSplit,
            CursorContext cursorContext) {
        int keyCountAfterInsert = keyCount + 1;
        int splitPos = splitPos(keyCountAfterInsert, ratioToKeepInLeftOnSplit);

        if (splitPos == insertPos) {
            layout.copyKey(newKey, newSplitter);
        } else {
            keyAt(cursor, newSplitter, insertPos < splitPos ? splitPos - 1 : splitPos, LEAF, cursorContext);
        }
        return splitPos;
    }

    @Override
    void doSplitLeaf(
            PageCursor leftCursor,
            int leftKeyCount,
            PageCursor rightCursor,
            int insertPos,
            KEY newKey,
            VALUE newValue,
            KEY newSplitter,
            int splitPos,
            double ratioToKeepInLeftOnSplit,
            long stableGeneration,
            long unstableGeneration,
            CursorContext cursorContext)
            throws IOException {
        int keyCountAfterInsert = leftKeyCount + 1;
        int rightKeyCount = keyCountAfterInsert - splitPos;

        if (insertPos < splitPos) {
            //                v---------v       copy
            // before _,_,_,_,_,_,_,_,_,_
            // insert _,_,_,X,_,_,_,_,_,_,_
            // split            ^
            copyKeysAndValues(leftCursor, splitPos - 1, rightCursor, 0, rightKeyCount);
            insertKeyValueAt(
                    leftCursor,
                    newKey,
                    newValue,
                    insertPos,
                    splitPos - 1,
                    stableGeneration,
                    unstableGeneration,
                    cursorContext);
        } else {
            //                  v---v           first copy
            //                        v-v       second copy
            // before _,_,_,_,_,_,_,_,_,_
            // insert _,_,_,_,_,_,_,_,X,_,_
            // split            ^
            int countBeforePos = insertPos - splitPos;
            if (countBeforePos > 0) {
                // first copy
                copyKeysAndValues(leftCursor, splitPos, rightCursor, 0, countBeforePos);
            }
            insertKeyValueAt(
                    rightCursor,
                    newKey,
                    newValue,
                    countBeforePos,
                    countBeforePos,
                    stableGeneration,
                    unstableGeneration,
                    cursorContext);
            int countAfterPos = leftKeyCount - insertPos;
            if (countAfterPos > 0) {
                // second copy
                copyKeysAndValues(leftCursor, insertPos, rightCursor, countBeforePos + 1, countAfterPos);
            }
        }
        TreeNode.setKeyCount(leftCursor, splitPos);
        TreeNode.setKeyCount(rightCursor, rightKeyCount);
    }

    /**
     * Given a range with keyCount number of fixed size keys,
     * then splitPos point to the first key that should be moved to right node.
     * Everything before splitPos will be kept in left node.
     *
     * Middle split
     *       0,1,2,3,4
     * split     ^
     * left  0,1
     * right 2,3,4
     *
     * Min split
     *       0,1,2,3,4
     * split   ^
     * left  0
     * right 1,2,3,4
     *
     * Max split
     *       0,1,2,3,4
     * split         ^
     * left  0,1,2,3
     * right 4
     *
     * Note that splitPos can not point past last position (keyCount - 1) or before pos 1.
     * This is because we need to split the range somewhere.
     *
     * @param keyCount number of keys in range.
     * @param ratioToKeepInLeftOnSplit How large ratio of key range to try and keep in left node.
     * @return position of first key to move to right node.
     */
    private static int splitPos(int keyCount, double ratioToKeepInLeftOnSplit) {
        // Key
        int minSplitPos = 1;
        int maxSplitPos = keyCount - 1;
        return Math.max(minSplitPos, Math.min(maxSplitPos, (int) (ratioToKeepInLeftOnSplit * keyCount)));
    }

    @Override
    void doSplitInternal(
            PageCursor leftCursor,
            int leftKeyCount,
            PageCursor rightCursor,
            int insertPos,
            KEY newKey,
            long newRightChild,
            long stableGeneration,
            long unstableGeneration,
            KEY newSplitter,
            double ratioToKeepInLeftOnSplit,
            CursorContext cursorContext) {
        int keyCountAfterInsert = leftKeyCount + 1;
        int splitPos = splitPos(keyCountAfterInsert, ratioToKeepInLeftOnSplit);

        if (splitPos == insertPos) {
            layout.copyKey(newKey, newSplitter);
        } else {
            keyAt(leftCursor, newSplitter, insertPos < splitPos ? splitPos - 1 : splitPos, INTERNAL, cursorContext);
        }
        int rightKeyCount = keyCountAfterInsert - splitPos - 1; // -1 because don't keep prim key in internal

        if (insertPos < splitPos) {
            //                         v-------v       copy
            // before key    _,_,_,_,_,_,_,_,_,_
            // before child -,-,-,-,-,-,-,-,-,-,-
            // insert key    _,_,X,_,_,_,_,_,_,_,_
            // insert child -,-,-,x,-,-,-,-,-,-,-,-
            // split key               ^

            leftCursor.copyTo(keyOffset(splitPos), rightCursor, keyOffset(0), rightKeyCount * keySize());
            leftCursor.copyTo(childOffset(splitPos), rightCursor, childOffset(0), (rightKeyCount + 1) * childSize());
            insertKeyAt(leftCursor, newKey, insertPos, splitPos - 1);
            insertChildAt(leftCursor, newRightChild, insertPos + 1, splitPos - 1, stableGeneration, unstableGeneration);
        } else {
            // pos > splitPos
            //                         v-v          first copy
            //                             v-v-v    second copy
            // before key    _,_,_,_,_,_,_,_,_,_
            // before child -,-,-,-,-,-,-,-,-,-,-
            // insert key    _,_,_,_,_,_,_,X,_,_,_
            // insert child -,-,-,-,-,-,-,-,x,-,-,-
            // split key               ^

            // pos == splitPos
            //                                      first copy
            //                         v-v-v-v-v    second copy
            // before key    _,_,_,_,_,_,_,_,_,_
            // before child -,-,-,-,-,-,-,-,-,-,-
            // insert key    _,_,_,_,_,X,_,_,_,_,_
            // insert child -,-,-,-,-,-,x,-,-,-,-,-
            // split key               ^

            // Keys
            int countBeforePos = insertPos - (splitPos + 1);
            // ... first copy
            if (countBeforePos > 0) {
                leftCursor.copyTo(keyOffset(splitPos + 1), rightCursor, keyOffset(0), countBeforePos * keySize());
            }
            // ... insert
            if (countBeforePos >= 0) {
                insertKeyAt(rightCursor, newKey, countBeforePos, countBeforePos);
            }
            // ... second copy
            int countAfterPos = leftKeyCount - insertPos;
            if (countAfterPos > 0) {
                leftCursor.copyTo(
                        keyOffset(insertPos), rightCursor, keyOffset(countBeforePos + 1), countAfterPos * keySize());
            }

            // Children
            countBeforePos = insertPos - splitPos;
            // ... first copy
            if (countBeforePos > 0) {
                // first copy
                leftCursor.copyTo(childOffset(splitPos + 1), rightCursor, childOffset(0), countBeforePos * childSize());
            }
            // ... insert
            insertChildAt(
                    rightCursor, newRightChild, countBeforePos, countBeforePos, stableGeneration, unstableGeneration);
            // ... second copy
            if (countAfterPos > 0) {
                leftCursor.copyTo(
                        childOffset(insertPos + 1),
                        rightCursor,
                        childOffset(countBeforePos + 1),
                        countAfterPos * childSize());
            }
        }
        TreeNode.setKeyCount(leftCursor, splitPos);
        TreeNode.setKeyCount(rightCursor, rightKeyCount);
    }

    @Override
    void moveKeyValuesFromLeftToRight(
            PageCursor leftCursor, int leftKeyCount, PageCursor rightCursor, int rightKeyCount, int fromPosInLeftNode) {
        int numberOfKeysToMove = leftKeyCount - fromPosInLeftNode;

        // Push keys and values in right sibling to the right
        insertKeyValueSlots(rightCursor, numberOfKeysToMove, rightKeyCount);

        // Move keys and values from left sibling to right sibling
        copyKeysAndValues(leftCursor, fromPosInLeftNode, rightCursor, 0, numberOfKeysToMove);

        setKeyCount(leftCursor, leftKeyCount - numberOfKeysToMove);
        setKeyCount(rightCursor, rightKeyCount + numberOfKeysToMove);
    }

    @Override
    void copyKeyValuesFromLeftToRight(
            PageCursor leftCursor, int leftKeyCount, PageCursor rightCursor, int rightKeyCount) {
        // Push keys and values in right sibling to the right
        insertKeyValueSlots(rightCursor, leftKeyCount, rightKeyCount);

        // Move keys and values from left sibling to right sibling
        copyKeysAndValues(leftCursor, 0, rightCursor, 0, leftKeyCount);

        // KeyCount
        setKeyCount(rightCursor, rightKeyCount + leftKeyCount);
    }

    @Override
    void printNode(
            PageCursor cursor,
            boolean includeValue,
            boolean includeAllocSpace,
            long stableGeneration,
            long unstableGeneration,
            CursorContext cursorContext) {
        try {
            boolean isDataNode = layerType(cursor) == DATA_LAYER_FLAG;
            if (isDataNode) {
                new GBPTreeStructure<>(null, null, this, layout, stableGeneration, unstableGeneration)
                        .visitTreeNode(cursor, new PrintingGBPTreeVisitor<>(PrintConfig.defaults()), cursorContext);
            } else {
                new GBPTreeStructure(this, layout, null, null, stableGeneration, unstableGeneration)
                        .visitTreeNode(cursor, new PrintingGBPTreeVisitor<>(PrintConfig.defaults()), cursorContext);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void copyKeysAndValues(PageCursor fromCursor, int fromPos, PageCursor toCursor, int toPos, int count) {
        fromCursor.copyTo(keyOffset(fromPos), toCursor, keyOffset(toPos), count * keySize());
        int valueLength = count * valueSize();
        if (valueLength > 0) {
            fromCursor.copyTo(valueOffset(fromPos), toCursor, valueOffset(toPos), valueLength);
        }
    }

    @Override
    String checkMetaConsistency(PageCursor cursor, int keyCount, Type type, GBPTreeConsistencyCheckVisitor visitor) {
        return "";
    }

    @Override
    public String toString() {
        return "TreeNodeFixedSize[pageSize:" + pageSize + ", internalMax:" + internalMaxKeyCount() + ", leafMax:"
                + leafMaxKeyCount() + ", " + "keySize:" + keySize() + ", valueSize:" + valueSize + "]";
    }
}

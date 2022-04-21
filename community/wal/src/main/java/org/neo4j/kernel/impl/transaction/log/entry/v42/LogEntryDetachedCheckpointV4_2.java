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
package org.neo4j.kernel.impl.transaction.log.entry.v42;

import static org.neo4j.kernel.impl.transaction.log.entry.LogEntryTypeCodes.DETACHED_CHECK_POINT;

import java.util.Objects;
import org.neo4j.kernel.KernelVersion;
import org.neo4j.kernel.impl.transaction.log.LogPosition;
import org.neo4j.kernel.impl.transaction.log.entry.AbstractLogEntry;
import org.neo4j.storageengine.api.LegacyStoreId;

public class LogEntryDetachedCheckpointV4_2 extends AbstractLogEntry {
    private final LogPosition logPosition;
    private final long checkpointTime;
    private final LegacyStoreId storeId;
    private final String reason;

    public LogEntryDetachedCheckpointV4_2(
            KernelVersion version,
            LogPosition logPosition,
            long checkpointMillis,
            LegacyStoreId storeId,
            String reason) {
        super(version, DETACHED_CHECK_POINT);
        this.logPosition = logPosition;
        this.checkpointTime = checkpointMillis;
        this.storeId = storeId;
        this.reason = reason;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        LogEntryDetachedCheckpointV4_2 that = (LogEntryDetachedCheckpointV4_2) o;
        return Objects.equals(logPosition, that.logPosition)
                && checkpointTime == that.checkpointTime
                && Objects.equals(storeId, that.storeId)
                && Objects.equals(reason, that.reason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(logPosition, checkpointTime, storeId, reason);
    }

    public LegacyStoreId getStoreId() {
        return storeId;
    }

    public LogPosition getLogPosition() {
        return logPosition;
    }

    public String getReason() {
        return reason;
    }

    @Override
    public String toString() {
        return "LogEntryDetachedCheckpoint{" + "logPosition=" + logPosition + ", checkpointTime=" + checkpointTime
                + ", storeId=" + storeId + ", reason='" + reason + '\'' + '}';
    }
}

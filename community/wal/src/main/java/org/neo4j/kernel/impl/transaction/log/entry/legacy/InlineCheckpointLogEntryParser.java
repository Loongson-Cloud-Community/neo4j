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
package org.neo4j.kernel.impl.transaction.log.entry.legacy;

import java.io.IOException;
import org.neo4j.io.fs.ReadableChecksumChannel;
import org.neo4j.kernel.KernelVersion;
import org.neo4j.kernel.impl.transaction.log.LogPosition;
import org.neo4j.kernel.impl.transaction.log.LogPositionMarker;
import org.neo4j.kernel.impl.transaction.log.entry.LogEntry;
import org.neo4j.kernel.impl.transaction.log.entry.LogEntryParser;
import org.neo4j.kernel.impl.transaction.log.entry.LogEntryTypeCodes;
import org.neo4j.storageengine.api.CommandReaderFactory;

public class InlineCheckpointLogEntryParser extends LogEntryParser {

    private final boolean checksum;

    public InlineCheckpointLogEntryParser(boolean checksum) {
        super(LogEntryTypeCodes.LEGACY_CHECK_POINT);
        this.checksum = checksum;
    }

    @Override
    public LogEntry parse(
            KernelVersion version,
            ReadableChecksumChannel channel,
            LogPositionMarker marker,
            CommandReaderFactory commandReaderFactory)
            throws IOException {
        long logVersion = channel.getLong();
        long byteOffset = channel.getLong();
        if (checksum) {
            // checksums present in some of old versions. We do not need them here, so we ignore them.
            channel.getInt();
        }
        return new LegacyInlinedCheckPoint(new LogPosition(logVersion, byteOffset));
    }
}

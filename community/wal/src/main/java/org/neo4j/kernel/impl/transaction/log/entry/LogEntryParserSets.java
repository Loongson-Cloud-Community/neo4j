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
package org.neo4j.kernel.impl.transaction.log.entry;

import static java.lang.String.format;

import java.util.EnumMap;
import org.neo4j.kernel.KernelVersion;

public class LogEntryParserSets {
    private static final EnumMap<KernelVersion, LogEntryParserSet> PARSER_SETS = new EnumMap<>(KernelVersion.class);

    static {
        PARSER_SETS.put(KernelVersion.V2_3, new LegacyLogEntryParserSetV2_3());
        PARSER_SETS.put(KernelVersion.V4_0, new LegacyLogEntryParserSetV4_0());
        PARSER_SETS.put(KernelVersion.V4_2, new LogEntryParserSetV4_2());
        PARSER_SETS.put(KernelVersion.V4_3_D4, new LogEntryParserSetV4_3());
        PARSER_SETS.put(KernelVersion.V4_4, new LogEntryParserSetV4_4());
        PARSER_SETS.put(KernelVersion.V5_0, new LogEntryParserSetV5_0());
        PARSER_SETS.put(KernelVersion.V5_6, new LogEntryParserSetV5_6());
    }

    /**
     * @param version the {@link KernelVersion} to get the {@link LogEntryParserSet} for. The returned parser is capable of reading
     * all types of log entries.
     * @return LogEntryParserSet for the given {@code version}.
     */
    public static LogEntryParserSet parserSet(KernelVersion version, KernelVersion latestRecognizedKernelVersion) {
        LogEntryParserSet parserSet = PARSER_SETS.get(version);
        if (parserSet == null) {
            if (version == KernelVersion.GLORIOUS_FUTURE && latestRecognizedKernelVersion.isAtLeast(version)) {
                return new LogEntryParserSetVGloriousFuture();
            }
            throw new IllegalStateException(format("No log entries version matching %s", version));
        }
        return parserSet;
    }
}

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
package org.neo4j.kernel.impl.coreapi;

import java.util.stream.Stream;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Procedure;

public class ProcedureWithException {
    @Description("List of numbers with erroneous value")
    @Procedure(name = "exception.stream.generate")
    public Stream<Result> streamErrors() {
        return Stream.of(new Result(1L), new Result(2L)).onClose(() -> {
            throw new RuntimeException("Injected error");
        });
    }

    public class Result {
        public Long value;

        public Result(Long value) {
            this.value = value;
        }
    }
}

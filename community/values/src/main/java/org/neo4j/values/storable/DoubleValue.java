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
package org.neo4j.values.storable;

import static java.lang.String.format;
import static org.neo4j.memory.HeapEstimator.shallowSizeOfInstance;

import org.neo4j.values.ValueMapper;

public final class DoubleValue extends FloatingPointValue {
    private static final long SHALLOW_SIZE = shallowSizeOfInstance(DoubleValue.class);

    private final double value;

    DoubleValue(double value) {
        this.value = value;
    }

    public double value() {
        return value;
    }

    @Override
    public double doubleValue() {
        return value;
    }

    @Override
    public <E extends Exception> void writeTo(ValueWriter<E> writer) throws E {
        writer.writeFloatingPoint(value);
    }

    @Override
    public Double asObjectCopy() {
        return value;
    }

    @Override
    public String prettyPrint() {
        return Double.toString(value);
    }

    @Override
    public String toString() {
        return format("%s(%e)", getTypeName(), value);
    }

    @Override
    public <T> T map(ValueMapper<T> mapper) {
        return mapper.mapDouble(this);
    }

    @Override
    public String getTypeName() {
        return "Double";
    }

    @Override
    public long estimatedHeapUsage() {
        return SHALLOW_SIZE;
    }

    @Override
    public ValueRepresentation valueRepresentation() {
        return ValueRepresentation.FLOAT64;
    }
}

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
package org.neo4j.bolt.protocol.v41.message.request;

import java.util.Map;
import java.util.Objects;

/**
 * Client configured routing information
 */
public class RoutingContext {

    private final boolean serverRoutingEnabled;
    private final Map<String, String> parameters;

    public RoutingContext(boolean serverRoutingEnabled, Map<String, String> parameters) {
        this.serverRoutingEnabled = serverRoutingEnabled;
        this.parameters = parameters;
    }

    public boolean isServerRoutingEnabled() {
        return serverRoutingEnabled;
    }

    public Map<String, String> getParameters() {
        return parameters;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        RoutingContext that = (RoutingContext) other;
        return serverRoutingEnabled == that.serverRoutingEnabled && parameters.equals(that.parameters);
    }

    @Override
    public int hashCode() {
        return Objects.hash(serverRoutingEnabled, parameters);
    }

    public boolean serverRoutingEnabled() {
        return serverRoutingEnabled;
    }

    public Map<String, String> parameters() {
        return parameters;
    }

    @Override
    public String toString() {
        return "RoutingContext[" + "serverRoutingEnabled="
                + serverRoutingEnabled + ", " + "parameters="
                + parameters + ']';
    }
}

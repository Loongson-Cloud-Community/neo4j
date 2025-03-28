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

package org.neo4j.bolt.protocol.v51.message.request;

import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.neo4j.bolt.protocol.common.connector.connection.Feature;
import org.neo4j.bolt.protocol.common.message.request.RequestMessage;
import org.neo4j.bolt.protocol.v41.message.request.RoutingContext;

public class HelloMessage implements RequestMessage {
    public static final byte SIGNATURE = 0x01;

    private static final String USER_AGENT = "user_agent";
    private static final String FEATURES = "patch_bolt";

    private final Map<String, Object> meta;

    private final RoutingContext routingContext;

    public HelloMessage(Map<String, Object> meta, RoutingContext routingContext) {
        this.routingContext = routingContext;
        this.meta = requireNonNull(meta);
    }

    public RoutingContext routingContext() {
        return routingContext;
    }

    public String userAgent() {
        return requireNonNull((String) meta.get(USER_AGENT));
    }

    @Override
    public boolean safeToProcessInAnyState() {
        return false;
    }

    @SuppressWarnings({"unchecked", "rawTypes"})
    public List<Feature> features() {
        var param = meta.get(FEATURES);
        if (!(param instanceof List<?>)) {
            return Collections.emptyList();
        }

        // Since this is an optional protocol feature which was introduced after the original spec was written, we're
        // not going to strictly validate the list or its contents
        return (List<Feature>) ((List) param)
                .stream()
                        .filter(it -> it instanceof String)
                        .map(id -> Feature.findFeatureById((String) id))
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
    }

    public Map<String, Object> meta() {
        return meta;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        org.neo4j.bolt.protocol.v51.message.request.HelloMessage that =
                (org.neo4j.bolt.protocol.v51.message.request.HelloMessage) o;
        return Objects.equals(meta, that.meta);
    }

    @Override
    public int hashCode() {
        return Objects.hash(meta);
    }

    @Override
    public String toString() {
        return "HELLO " + meta;
    }
}

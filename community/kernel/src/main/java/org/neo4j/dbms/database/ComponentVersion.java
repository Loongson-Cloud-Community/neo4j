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
package org.neo4j.dbms.database;

import org.neo4j.configuration.Config;

/**
 * Describes the version scheme of those system components that needs versioning.
 * Also keeps track of the current versions and for which versions runtime and migration are supported.
 */
public interface ComponentVersion {
    SystemGraphComponent.Name SECURITY_USER_COMPONENT = new SystemGraphComponent.Name("security-users");
    SystemGraphComponent.Name SECURITY_PRIVILEGE_COMPONENT = new SystemGraphComponent.Name("security-privileges");
    SystemGraphComponent.Name DBMS_RUNTIME_COMPONENT = new SystemGraphComponent.Name("dbms-runtime");
    SystemGraphComponent.Name TOPOLOGY_GRAPH_COMPONENT = new SystemGraphComponent.Name("topology-graph");
    SystemGraphComponent.Name COMMUNITY_TOPOLOGY_GRAPH_COMPONENT =
            new SystemGraphComponent.Name("community-topology-graph");
    SystemGraphComponent.Name FABRIC_DATABASE_COMPONENT = new SystemGraphComponent.Name("fabric-database");
    SystemGraphComponent.Name MULTI_DATABASE_COMPONENT = new SystemGraphComponent.Name("multi-database");
    SystemGraphComponent.Name SYSTEM_GRAPH_COMPONENT = new SystemGraphComponent.Name("system-graph");

    /**
     * Get the version of the component. Component versions are expected to be ordered and the oldest version is 0.
     *
     * @return int representation of the component.
     */
    int getVersion();

    /**
     * @return Name of the component, will be used as a property on the Version node.
     */
    SystemGraphComponent.Name getComponentName();

    String getDescription();

    boolean isCurrent(Config config);

    boolean migrationSupported();

    boolean runtimeSupported();

    default boolean isGreaterThan(ComponentVersion other) {
        return this.getVersion() > other.getVersion();
    }

    class Neo4jVersions {
        public static final String VERSION_40 = "Neo4j 4.0";
        public static final String VERSION_41D1 = "Neo4j 4.1.0-Drop01";
        public static final String VERSION_41 = "Neo4j 4.1";
        public static final String VERSION_42D4 = "Neo4j 4.2.0-Drop04";
        public static final String VERSION_42D6 = "Neo4j 4.2.0-Drop06";
        public static final String VERSION_42D7 = "Neo4j 4.2.0-Drop07";
        public static final String VERSION_42 = "Neo4j 4.2.0";
        public static final String VERSION_42P1 = "Neo4j 4.2.1";
        public static final String VERSION_43D1 = "Neo4j 4.3.0-Drop01";
        public static final String VERSION_43D2 = "Neo4j 4.3.0-Drop02";
        public static final String VERSION_43D3 = "Neo4j 4.3.0-Drop03";
        public static final String VERSION_43D4 = "Neo4j 4.3.0-Drop04";
        public static final String VERSION_44 = "Neo4j 4.4";
        public static final String VERSION_44P7 = "Neo4j 4.4.7";
        public static final String VERSION_50D6 = "Neo4j 5.0.0-Drop06";
        public static final String VERSION_50 = "Neo4j 5.0";
        public static final String VERSION_56 = "Neo4j 5.6";
    }
}

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
package org.neo4j.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.neo4j.configuration.GraphDatabaseSettings.SYSTEM_DATABASE_NAME;
import static org.neo4j.server.security.auth.SecurityTestUtils.credentialFor;
import static org.neo4j.server.security.auth.SecurityTestUtils.password;

import java.util.Collections;
import java.util.Optional;
import java.util.SortedMap;
import org.neo4j.common.DependencyResolver;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.dbms.database.DatabaseManager;
import org.neo4j.dbms.database.StandaloneDatabaseContext;
import org.neo4j.kernel.database.Database;
import org.neo4j.kernel.database.DatabaseIdRepository;
import org.neo4j.kernel.database.NamedDatabaseId;
import org.neo4j.kernel.database.TestDatabaseIdRepository;
import org.neo4j.kernel.impl.factory.GraphDatabaseFacade;
import org.neo4j.kernel.impl.security.User;
import org.neo4j.kernel.lifecycle.LifecycleAdapter;
import org.neo4j.server.security.systemgraph.SystemGraphRealmHelper;
import org.neo4j.test.TestDatabaseManagementServiceBuilder;
import org.neo4j.test.utils.TestDirectory;

public class BasicSystemGraphRealmTestHelper {
    public static class TestDatabaseManager extends LifecycleAdapter
            implements DatabaseManager<StandaloneDatabaseContext> {
        protected GraphDatabaseFacade testSystemDb;
        protected final DatabaseManagementService managementService;
        private final DatabaseIdRepository.Caching databaseIdRepository = new TestDatabaseIdRepository.Caching();

        protected TestDatabaseManager(TestDirectory testDir) {
            managementService = createManagementService(testDir);
            testSystemDb = (GraphDatabaseFacade) managementService.database(SYSTEM_DATABASE_NAME);
        }

        protected DatabaseManagementService createManagementService(TestDirectory testDir) {
            return new TestDatabaseManagementServiceBuilder(testDir.homePath())
                    .impermanent()
                    .noOpSystemGraphInitializer()
                    .setConfig(GraphDatabaseSettings.auth_enabled, false)
                    .build();
        }

        public DatabaseManagementService getManagementService() {
            return managementService;
        }

        @Override
        public Optional<StandaloneDatabaseContext> getDatabaseContext(NamedDatabaseId namedDatabaseId) {
            if (namedDatabaseId.isSystemDatabase()) {
                DependencyResolver dependencyResolver = testSystemDb.getDependencyResolver();
                Database database = dependencyResolver.resolveDependency(Database.class);
                return Optional.of(new StandaloneDatabaseContext(database));
            }
            return Optional.empty();
        }

        @Override
        public StandaloneDatabaseContext createDatabase(NamedDatabaseId namedDatabaseId) {
            throw new UnsupportedOperationException("Call to createDatabase not expected");
        }

        @Override
        public void dropDatabase(NamedDatabaseId namedDatabaseId) {}

        @Override
        public void stopDatabase(NamedDatabaseId namedDatabaseId) {}

        @Override
        public void startDatabase(NamedDatabaseId namedDatabaseId) {}

        @Override
        public DatabaseIdRepository.Caching databaseIdRepository() {
            return databaseIdRepository;
        }

        @Override
        public SortedMap<NamedDatabaseId, StandaloneDatabaseContext> registeredDatabases() {
            return Collections.emptySortedMap();
        }
    }

    public static void assertAuthenticationSucceeds(
            SystemGraphRealmHelper realmHelper, String username, String password) throws Exception {
        assertAuthenticationSucceeds(realmHelper, username, password, false);
    }

    public static void assertAuthenticationSucceeds(
            SystemGraphRealmHelper realmHelper, String username, String password, boolean changeRequired)
            throws Exception {
        var user = realmHelper.getUser(username);
        assertTrue(user.credentials().matchesPassword(password(password)));
        assertThat(user.passwordChangeRequired())
                .withFailMessage(
                        "Expected change required to be %s, but was %s", changeRequired, user.passwordChangeRequired())
                .isEqualTo(changeRequired);
    }

    public static void assertAuthenticationFails(SystemGraphRealmHelper realmHelper, String username, String password)
            throws Exception {
        var user = realmHelper.getUser(username);
        assertFalse(user.credentials().matchesPassword(password(password)));
    }

    public static User createUser(String userName, String password, boolean pwdChangeRequired) {
        return new User.Builder(userName, credentialFor(password))
                .withRequiredPasswordChange(pwdChangeRequired)
                .build();
    }
}

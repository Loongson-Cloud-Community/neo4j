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
package org.neo4j.bolt.authentication;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.extension.ExtendWith;
import org.neo4j.bolt.test.annotation.BoltTestExtension;
import org.neo4j.bolt.test.annotation.connection.initializer.Negotiated;
import org.neo4j.bolt.test.annotation.test.ProtocolTest;
import org.neo4j.bolt.testing.assertions.BoltConnectionAssertions;
import org.neo4j.bolt.testing.client.TransportConnection;
import org.neo4j.bolt.testing.messages.AbstractBoltWire;
import org.neo4j.bolt.testing.messages.BoltWire;
import org.neo4j.bolt.transport.Neo4jWithSocketExtension;
import org.neo4j.kernel.api.exceptions.Status;
import org.neo4j.packstream.io.PackstreamBuf;
import org.neo4j.packstream.struct.StructHeader;
import org.neo4j.test.extension.OtherThreadExtension;
import org.neo4j.test.extension.testdirectory.EphemeralTestDirectoryExtension;

@EphemeralTestDirectoryExtension
@Neo4jWithSocketExtension
@BoltTestExtension
@ExtendWith(OtherThreadExtension.class)
public class UserAgentIT {

    @ProtocolTest
    void shouldFailWhenUserAgentIsOmitted(@Negotiated TransportConnection connection) throws IOException {
        connection.send(PackstreamBuf.allocUnpooled()
                .writeStructHeader(new StructHeader(1, AbstractBoltWire.MESSAGE_TAG_HELLO))
                .writeMap(Map.of("scheme", "none"))
                .getTarget());

        BoltConnectionAssertions.assertThat(connection)
                .receivesFailure(
                        Status.Request.Invalid,
                        "Illegal value for field \"user_agent\": Expected \"user_agent\" to be non-null");
    }

    @ProtocolTest
    void shouldFailWhenInvalidUserAgentIsGiven(BoltWire wire, @Negotiated TransportConnection connection)
            throws IOException {
        connection.send(wire.hello(Map.of("scheme", "none", "user_agent", 42L)));

        BoltConnectionAssertions.assertThat(connection)
                .receivesFailure(
                        Status.Request.Invalid,
                        "Illegal value for field \"user_agent\": Expected value to be a string");
    }
}

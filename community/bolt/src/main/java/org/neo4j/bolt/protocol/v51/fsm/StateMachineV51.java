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

package org.neo4j.bolt.protocol.v51.fsm;

import java.time.Clock;
import org.neo4j.bolt.protocol.common.connector.connection.Connection;
import org.neo4j.bolt.protocol.common.fsm.AbstractStateMachine;
import org.neo4j.bolt.protocol.common.fsm.StateMachineSPI;
import org.neo4j.bolt.protocol.common.routing.ProcedureRoutingTableGetter;
import org.neo4j.bolt.protocol.v40.fsm.AutoCommitState;
import org.neo4j.bolt.protocol.v40.fsm.InterruptedState;
import org.neo4j.bolt.protocol.v43.fsm.FailedState;
import org.neo4j.bolt.protocol.v44.fsm.InTransactionState;
import org.neo4j.memory.HeapEstimator;
import org.neo4j.memory.MemoryTracker;

public class StateMachineV51 extends AbstractStateMachine {
    public static final long SHALLOW_SIZE = HeapEstimator.shallowSizeOfInstance(StateMachineV51.class);

    public StateMachineV51(StateMachineSPI spi, Connection connection, Clock clock) {
        super(spi, connection, clock);
    }

    @Override
    protected States buildStates(MemoryTracker memoryTracker) {
        memoryTracker.allocateHeap(NegotiationState.SHALLOW_SIZE
                + ReadyState.SHALLOW_SIZE
                + AuthenticationState.SHALLOW_SIZE
                + AutoCommitState.SHALLOW_SIZE
                + InTransactionState.SHALLOW_SIZE
                + FailedState.SHALLOW_SIZE
                + InterruptedState.SHALLOW_SIZE);

        var negotiation = new NegotiationState(); // v5.1
        var authentication = new AuthenticationState(); // v5.1
        var autoCommitState = new AutoCommitState(); // v4
        var inTransaction = new InTransactionState(); // v4.4
        var failed = new FailedState(); // v4.3
        var ready = new ReadyState(new ProcedureRoutingTableGetter()); // v5.1
        var interrupted = new InterruptedState(); // v3

        negotiation.setAuthenticationState(authentication);

        authentication.setReadyState(ready);
        authentication.setFailedState(failed);

        ready.setTransactionReadyState(inTransaction);
        ready.setStreamingState(autoCommitState);
        ready.setFailedState(failed);
        ready.setAuthenticationState(authentication);

        autoCommitState.setReadyState(ready);
        autoCommitState.setFailedState(failed);

        inTransaction.setReadyState(ready);
        inTransaction.setFailedState(failed);

        interrupted.setReadyState(ready);

        return new AbstractStateMachine.States(negotiation, failed, interrupted);
    }
}

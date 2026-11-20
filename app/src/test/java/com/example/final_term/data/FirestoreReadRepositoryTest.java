package com.example.final_term.data;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FirestoreReadRepositoryTest {
    @Test
    public void offlineGateRejectsMutationsAndKeepsReadsAllowed() {
        ConnectivityGate gate = new ConnectivityGate(() -> false);

        assertFalse(gate.isOnline());
        assertTrue(gate.canReadCachedData());
        assertEquals(ConnectivityGate.MutationStatus.OFFLINE, gate.requireOnlineForMutation());
    }

    @Test
    public void onlineGateAllowsAuthoritativeMutations() {
        ConnectivityGate gate = new ConnectivityGate(() -> true);

        assertTrue(gate.isOnline());
        assertEquals(ConnectivityGate.MutationStatus.ALLOWED, gate.requireOnlineForMutation());
    }
}

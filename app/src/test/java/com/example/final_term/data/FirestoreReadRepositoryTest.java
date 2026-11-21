package com.example.final_term.data;

import com.google.firebase.Timestamp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class FirestoreReadRepositoryTest {
    @Test
    public void readsLegacyTimestampAndBackendEpochMillis() {
        Timestamp legacy = new Timestamp(1_700_000_000L, 123_000_000);
        assertSame(legacy, FirestoreReadRepository.readTimestamp(legacy, null));
        assertEquals(legacy, FirestoreReadRepository.readTimestamp(1_700_000_000_123L, null));
    }

    @Test
    public void fallsBackToCommittedAtAndIgnoresInvalidDates() {
        assertEquals(new Timestamp(1_700_000_000L, 0),
                FirestoreReadRepository.readTimestamp(null, 1_700_000_000_000L));
        assertNull(FirestoreReadRepository.readTimestamp("invalid", Long.MAX_VALUE));
    }

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

package com.example.final_term.data;

import java.util.function.BooleanSupplier;

public final class ConnectivityGate {
    public enum MutationStatus { ALLOWED, OFFLINE }

    private final BooleanSupplier onlineSupplier;

    public ConnectivityGate(BooleanSupplier onlineSupplier) {
        this.onlineSupplier = onlineSupplier;
    }

    public boolean isOnline() { return onlineSupplier.getAsBoolean(); }
    public boolean canReadCachedData() { return true; }
    public MutationStatus requireOnlineForMutation() {
        return isOnline() ? MutationStatus.ALLOWED : MutationStatus.OFFLINE;
    }
}

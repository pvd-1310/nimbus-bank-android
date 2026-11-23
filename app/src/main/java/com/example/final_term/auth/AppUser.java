package com.example.final_term.auth;

import androidx.annotation.NonNull;

public final class AppUser {
    private final String uid;
    private final String email;
    private final String fullName;
    private final UserRole role;
    private final boolean active;
    private final String status;

    public AppUser(@NonNull String uid, @NonNull String email, @NonNull UserRole role, boolean active) {
        this(uid, email, "", role, active, "ACTIVE");
    }

    public AppUser(@NonNull String uid, @NonNull String email, @NonNull String fullName,
                   @NonNull UserRole role, boolean active) {
        this(uid, email, fullName, role, active, "ACTIVE");
    }

    public AppUser(@NonNull String uid, @NonNull String email, @NonNull String fullName,
                   @NonNull UserRole role, boolean active, @NonNull String status) {
        this.uid = uid;
        this.email = email;
        this.fullName = fullName;
        this.role = role;
        this.active = active;
        this.status = status;
    }

    public String getUid() { return uid; }
    public String getEmail() { return email; }
    public String getFullName() { return fullName; }
    public UserRole getRole() { return role; }
    public boolean isActive() { return active; }
    @NonNull public String getStatus() { return status; }
    public boolean isOnboarding() { return "ONBOARDING".equals(status); }
}

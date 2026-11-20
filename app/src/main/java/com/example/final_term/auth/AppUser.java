package com.example.final_term.auth;

import androidx.annotation.NonNull;

public final class AppUser {
    private final String uid;
    private final String email;
    private final String fullName;
    private final UserRole role;
    private final boolean active;

    public AppUser(@NonNull String uid, @NonNull String email, @NonNull UserRole role, boolean active) {
        this(uid, email, "", role, active);
    }

    public AppUser(@NonNull String uid, @NonNull String email, @NonNull String fullName,
                   @NonNull UserRole role, boolean active) {
        this.uid = uid;
        this.email = email;
        this.fullName = fullName;
        this.role = role;
        this.active = active;
    }

    public String getUid() { return uid; }
    public String getEmail() { return email; }
    public String getFullName() { return fullName; }
    public UserRole getRole() { return role; }
    public boolean isActive() { return active; }
}

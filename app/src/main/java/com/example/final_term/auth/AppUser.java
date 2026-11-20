package com.example.final_term.auth;

import androidx.annotation.NonNull;

public final class AppUser {
    private final String uid;
    private final String email;
    private final UserRole role;
    private final boolean active;

    public AppUser(@NonNull String uid, @NonNull String email, @NonNull UserRole role, boolean active) {
        this.uid = uid;
        this.email = email;
        this.role = role;
        this.active = active;
    }

    public String getUid() { return uid; }
    public String getEmail() { return email; }
    public UserRole getRole() { return role; }
    public boolean isActive() { return active; }
}

package com.example.final_term.auth;

import androidx.annotation.Nullable;

public final class AuthState {
    public enum Status { SIGNED_OUT, LOADING, SIGNED_IN, ERROR, SETUP_REQUIRED }

    private final Status status;
    private final AppUser user;
    private final String message;

    private AuthState(Status status, @Nullable AppUser user, @Nullable String message) {
        this.status = status;
        this.user = user;
        this.message = message;
    }

    public static AuthState signedOut() { return new AuthState(Status.SIGNED_OUT, null, null); }
    public static AuthState loading() { return new AuthState(Status.LOADING, null, null); }
    public static AuthState signedIn(AppUser user) { return new AuthState(Status.SIGNED_IN, user, null); }
    public static AuthState error(String message) { return new AuthState(Status.ERROR, null, message); }
    public static AuthState setupRequired(String message) { return new AuthState(Status.SETUP_REQUIRED, null, message); }

    public Status getStatus() { return status; }
    @Nullable public AppUser getUser() { return user; }
    @Nullable public String getMessage() { return message; }
}

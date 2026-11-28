package com.example.final_term.auth;

import androidx.lifecycle.LiveData;

import com.google.android.gms.tasks.Task;

public interface AuthRepository {
    LiveData<AuthState> observeAuthState();
    Task<AppUser> signIn(String email, String password);
    Task<Void> createRegistrationIdentity(String email, String password);
    Task<Void> sendRegistrationEmail();
    Task<Boolean> refreshRegistrationEmail();
    Task<AppUser> refreshRegisteredProfile();
    String currentEmail();
    Task<Void> sendPasswordResetEmail(String email);
    void signOut();
}

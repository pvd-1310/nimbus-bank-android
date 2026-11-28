package com.example.final_term.auth;

import androidx.lifecycle.LiveData;

import com.google.android.gms.tasks.Task;

public interface AuthRepository {
    LiveData<AuthState> observeAuthState();
    Task<AppUser> signIn(String email, String password);
    Task<Void> createRegistrationIdentity(String email, String password);
    Task<Void> sendRegistrationEmail();
    Task<Void> sendPasswordRecoveryEmail(String email);
    Task<Void> applyEmailVerification(String code);
    Task<String> verifyPasswordResetCode(String code);
    Task<Void> confirmPasswordReset(String code, String password);
    Task<Boolean> refreshRegistrationEmail();
    Task<AppUser> refreshRegisteredProfile();
    String currentEmail();
    Task<Void> sendPasswordResetEmail(String email);
    void signOut();
}

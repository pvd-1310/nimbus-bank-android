package com.example.final_term.core;

import android.content.Context;

import androidx.annotation.NonNull;

import com.example.final_term.auth.AuthRepository;
import com.example.final_term.auth.FirebaseAuthRepository;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;

/**
 * Composition root for the app. Firebase is optional at process start so a missing
 * google-services.json becomes a recoverable setup state instead of a crash.
 */
public final class AppContainer {
    private final boolean firebaseConfigured;
    private final AuthRepository authRepository;

    public AppContainer(@NonNull Context context) {
        FirebaseApp firebaseApp;
        try {
            firebaseApp = FirebaseApp.initializeApp(context.getApplicationContext());
        } catch (RuntimeException ignored) {
            firebaseApp = null;
        }
        firebaseConfigured = firebaseApp != null;

        if (firebaseConfigured) {
            authRepository = new FirebaseAuthRepository(
                    FirebaseAuth.getInstance(firebaseApp),
                    FirebaseFirestore.getInstance(firebaseApp));
        } else {
            authRepository = new FirebaseAuthRepository(null, null);
        }
    }

    public boolean isFirebaseConfigured() {
        return firebaseConfigured;
    }

    public AuthRepository getAuthRepository() {
        return authRepository;
    }
}

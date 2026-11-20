package com.example.final_term.core;

import android.content.Context;

import androidx.annotation.NonNull;

import com.example.final_term.auth.AuthRepository;
import com.example.final_term.auth.FirebaseAuthRepository;
import com.example.final_term.data.FirestoreReadRepository;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;

public final class AppContainer {
    private final boolean firebaseConfigured;
    private final AuthRepository authRepository;
    private final FirestoreReadRepository readRepository;

    public AppContainer(@NonNull Context context) {
        FirebaseApp firebaseApp;
        try {
            firebaseApp = FirebaseApp.initializeApp(context.getApplicationContext());
        } catch (RuntimeException ignored) {
            firebaseApp = null;
        }
        firebaseConfigured = firebaseApp != null;

        if (firebaseConfigured) {
            FirebaseFirestore firestore = FirebaseFirestore.getInstance(firebaseApp);
            authRepository = new FirebaseAuthRepository(
                    FirebaseAuth.getInstance(firebaseApp),
                    firestore);
            readRepository = new FirestoreReadRepository(firestore);
        } else {
            authRepository = new FirebaseAuthRepository(null, null);
            readRepository = new FirestoreReadRepository(null);
        }
    }

    public boolean isFirebaseConfigured() {
        return firebaseConfigured;
    }

    public AuthRepository getAuthRepository() {
        return authRepository;
    }

    public FirestoreReadRepository getReadRepository() {
        return readRepository;
    }
}

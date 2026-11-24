package com.example.final_term.core;

import android.content.Context;

import androidx.annotation.NonNull;

import com.example.final_term.BuildConfig;
import com.example.final_term.auth.AuthRepository;
import com.example.final_term.auth.FirebaseAuthRepository;
import com.example.final_term.data.FirestoreReadRepository;
import com.example.final_term.data.BackendApiClient;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;

public final class AppContainer {
    private static final String DEMO_APP = "local-banking-demo";
    private static final String DEMO_HOST = "10.0.2.2";
    private final boolean firebaseConfigured;
    private final AuthRepository authRepository;
    private final FirestoreReadRepository readRepository;
    private final BackendApiClient backendApiClient;

    public AppContainer(@NonNull Context context) {
        FirebaseApp firebaseApp;
        try {
            firebaseApp = BuildConfig.DEBUG && BuildConfig.FIREBASE_DEMO
                    ? localDemoApp(context.getApplicationContext())
                    : FirebaseApp.initializeApp(context.getApplicationContext());
        } catch (RuntimeException ignored) {
            firebaseApp = null;
        }
        firebaseConfigured = firebaseApp != null;

        if (firebaseConfigured) {
            FirebaseFirestore firestore = FirebaseFirestore.getInstance(firebaseApp);
            FirebaseAuth auth = FirebaseAuth.getInstance(firebaseApp);
            authRepository = new FirebaseAuthRepository(auth, firestore);
            readRepository = new FirestoreReadRepository(firestore);
            backendApiClient = new BackendApiClient(auth);
        } else {
            authRepository = new FirebaseAuthRepository(null, null);
            readRepository = new FirestoreReadRepository(null);
            backendApiClient = new BackendApiClient(null);
        }
    }

    private static synchronized FirebaseApp localDemoApp(Context context) {
        for (FirebaseApp app : FirebaseApp.getApps(context)) {
            if (DEMO_APP.equals(app.getName())) return app;
        }
        FirebaseOptions options = new FirebaseOptions.Builder()
                .setProjectId("demo-mobile-banking")
                .setApplicationId("1:1234567890:android:0000000000000000")
                .setApiKey("local-demo-api-key")
                .build();
        FirebaseApp app = FirebaseApp.initializeApp(context, options, DEMO_APP);
        FirebaseAuth.getInstance(app).useEmulator(DEMO_HOST, 19099);
        FirebaseFirestore.getInstance(app).useEmulator(DEMO_HOST, 18080);
        return app;
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

    public BackendApiClient getBackendApiClient() {
        return backendApiClient;
    }
}

package com.example.final_term.auth;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.final_term.core.AppConfig;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Source;

/** Auth adapter. Profile data, not email text, is the role authority for this demo. */
public final class FirebaseAuthRepository implements AuthRepository {
    private final FirebaseAuth firebaseAuth;
    private final FirebaseFirestore firestore;
    private final MutableLiveData<AuthState> authState = new MutableLiveData<>(AuthState.signedOut());

    public FirebaseAuthRepository(FirebaseAuth firebaseAuth, FirebaseFirestore firestore) {
        this.firebaseAuth = firebaseAuth;
        this.firestore = firestore;
        if (firebaseAuth != null) {
            firebaseAuth.addAuthStateListener(auth -> {
                FirebaseUser current = auth.getCurrentUser();
                if (current == null) {
                    authState.postValue(AuthState.signedOut());
                } else {
                    // Never grant a restored cached role without a fresh server profile read.
                    authState.postValue(AuthState.loading());
                    resolveProfile(current).addOnFailureListener(error -> {
                        authState.postValue(AuthState.error("Không thể xác thực hồ sơ người dùng."));
                        firebaseAuth.signOut();
                    });
                }
            });
        } else {
            authState.postValue(AuthState.setupRequired("Chưa cấu hình Firebase cho ứng dụng."));
        }
    }

    @Override
    public LiveData<AuthState> observeAuthState() { return authState; }

    @Override
    public Task<AppUser> signIn(String email, String password) {
        if (firebaseAuth == null || firestore == null) {
            return Tasks.forException(new IllegalStateException("FIREBASE_SETUP_REQUIRED"));
        }
        if (email == null || email.trim().isEmpty() || password == null || password.isEmpty()) {
            return Tasks.forException(new IllegalArgumentException("Email và mật khẩu là bắt buộc."));
        }

        authState.postValue(AuthState.loading());
        Task<AppUser> signInTask = firebaseAuth.signInWithEmailAndPassword(email.trim(), password)
                .continueWithTask(task -> {
                    if (!task.isSuccessful() || task.getResult() == null || task.getResult().getUser() == null) {
                        throw task.getException() == null
                                ? new IllegalStateException("Không thể đăng nhập.")
                                : task.getException();
                    }
                    return resolveProfile(task.getResult().getUser());
                });
        signInTask.addOnFailureListener(error -> authState.postValue(AuthState.error(error.getMessage())));
        return signInTask;
    }

    @Override
    public void signOut() {
        if (firebaseAuth != null) firebaseAuth.signOut();
        authState.postValue(AuthState.signedOut());
    }

    private Task<AppUser> resolveProfile(FirebaseUser user) {
        return firestore.collection("users").document(user.getUid()).get(Source.SERVER)
                .continueWith(profileTask -> {
                    if (!profileTask.isSuccessful() || profileTask.getResult() == null
                            || !profileTask.getResult().exists()) {
                        throw new IllegalStateException("PROFILE_NOT_FOUND");
                    }
                    UserRole role = parseRole(profileTask.getResult().getString("role"));
                    Boolean active = profileTask.getResult().getBoolean("active");
                    if (role == UserRole.UNKNOWN || !Boolean.TRUE.equals(active)) {
                        throw new IllegalStateException("PROFILE_NOT_ALLOWED");
                    }
                    AppUser appUser = new AppUser(user.getUid(),
                            user.getEmail() == null ? "" : user.getEmail(), role, true);
                    authState.postValue(AuthState.signedIn(appUser));
                    return appUser;
                });
    }

    private static UserRole parseRole(String role) {
        if (AppConfig.CUSTOMER_ROLE.equals(role)) return UserRole.CUSTOMER;
        if (AppConfig.OFFICER_ROLE.equals(role)) return UserRole.OFFICER;
        return UserRole.UNKNOWN;
    }
}

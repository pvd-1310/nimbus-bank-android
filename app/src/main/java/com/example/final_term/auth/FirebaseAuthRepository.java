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
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.MetadataChanges;

public final class FirebaseAuthRepository implements AuthRepository {
    private final FirebaseAuth firebaseAuth;
    private final FirebaseFirestore firestore;
    private final MutableLiveData<AuthState> authState = new MutableLiveData<>(AuthState.signedOut());
    private ListenerRegistration profileListener;

    public FirebaseAuthRepository(FirebaseAuth firebaseAuth, FirebaseFirestore firestore) {
        this.firebaseAuth = firebaseAuth;
        this.firestore = firestore;
        if (firebaseAuth != null) {
            firebaseAuth.addAuthStateListener(auth -> {
                FirebaseUser current = auth.getCurrentUser();
                if (profileListener != null) {
                    profileListener.remove();
                    profileListener = null;
                }
                if (current == null) {
                    authState.postValue(AuthState.signedOut());
                } else {
                    authState.postValue(AuthState.loading());
                    String uid = current.getUid();
                    profileListener = firestore.collection("users").document(uid)
                            .addSnapshotListener(MetadataChanges.INCLUDE, (snapshot, error) -> {
                                if (!isCurrent(current)) return;
                                if (error != null) {
                                    authState.setValue(AuthState.error("Không thể cập nhật trạng thái hồ sơ."));
                                } else if (snapshot != null
                                        && (snapshot.exists() || !snapshot.getMetadata().isFromCache())) {
                                    publishProfile(current, snapshot).addOnFailureListener(failure -> {
                                        if (isCurrent(current)) {
                                            authState.setValue(AuthState.error("Không thể xác thực hồ sơ người dùng."));
                                        }
                                    });
                                }
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

    @Override
    public Task<Void> sendPasswordResetEmail(String email) {
        if (firebaseAuth == null) return Tasks.forException(new IllegalStateException("FIREBASE_SETUP_REQUIRED"));
        if (email == null || email.trim().isEmpty())
            return Tasks.forException(new IllegalArgumentException("EMAIL_REQUIRED"));
        return firebaseAuth.sendPasswordResetEmail(email.trim());
    }

    @Override
    public Task<Void> createRegistrationIdentity(String email, String password) {
        if (firebaseAuth == null) return Tasks.forException(new IllegalStateException("FIREBASE_SETUP_REQUIRED"));
        return firebaseAuth.createUserWithEmailAndPassword(email, password)
                .onSuccessTask(result -> Tasks.forResult(null));
    }

    @Override
    public Task<Void> sendRegistrationEmail() {
        FirebaseUser user = firebaseAuth == null ? null : firebaseAuth.getCurrentUser();
        if (user == null) return Tasks.forException(new IllegalStateException("SIGN_IN_REQUIRED"));
        return user.sendEmailVerification();
    }

    @Override
    public Task<Boolean> refreshRegistrationEmail() {
        FirebaseUser user = firebaseAuth == null ? null : firebaseAuth.getCurrentUser();
        if (user == null) return Tasks.forException(new IllegalStateException("SIGN_IN_REQUIRED"));
        return user.reload().onSuccessTask(ignored -> user.getIdToken(true))
                .onSuccessTask(token -> Tasks.forResult(Boolean.TRUE.equals(token.getClaims().get("email_verified"))));
    }

    @Override
    public Task<AppUser> refreshRegisteredProfile() {
        FirebaseUser user = firebaseAuth == null ? null : firebaseAuth.getCurrentUser();
        if (user == null) return Tasks.forException(new IllegalStateException("SIGN_IN_REQUIRED"));
        return user.getIdToken(true).onSuccessTask(ignored -> resolveProfile(user));
    }

    @Override
    public String currentEmail() {
        FirebaseUser user = firebaseAuth == null ? null : firebaseAuth.getCurrentUser();
        return user == null ? null : user.getEmail();
    }

    private boolean isCurrent(FirebaseUser user) {
        FirebaseUser current = firebaseAuth.getCurrentUser();
        return current != null && current.getUid().equals(user.getUid());
    }

    private Task<AppUser> resolveProfile(FirebaseUser user) {
        return firestore.collection("users").document(user.getUid()).get(Source.SERVER)
                .onSuccessTask(profile -> publishProfile(user, profile));
    }

    private Task<AppUser> publishProfile(FirebaseUser user, DocumentSnapshot profile) {
        return user.getIdToken(false).onSuccessTask(token -> {
            if (!isCurrent(user)) return Tasks.forException(new IllegalStateException("SIGN_IN_REQUIRED"));
            Object role = token.getClaims().get("role");
            if (role == null && (!profile.exists()
                    || "FIREBASE_EMAIL".equals(profile.getString("registrationMethod")))) {
                authState.setValue(AuthState.registrationPending());
                return Tasks.forResult(null);
            }
            try {
                if (!profile.exists()) throw new IllegalStateException("PROFILE_NOT_FOUND");
                AppUser appUser = toAppUser(user, profile);
                authState.setValue(AuthState.signedIn(appUser));
                return Tasks.forResult(appUser);
            } catch (IllegalStateException invalid) {
                authState.setValue(AuthState.error("Tài khoản chưa được kích hoạt."));
                return Tasks.forException(invalid);
            }
        });
    }

    private AppUser toAppUser(FirebaseUser user, DocumentSnapshot profile) {
        UserRole role = parseRole(profile.getString("role"));
        Boolean active = profile.getBoolean("active");
        String status = profile.getString("status");
        if (status == null) status = Boolean.TRUE.equals(active) ? "ACTIVE" : "INACTIVE";
        boolean onboarding = role == UserRole.CUSTOMER && "ONBOARDING".equals(status);
        boolean allowed = onboarding || (Boolean.TRUE.equals(active) && "ACTIVE".equals(status));
        if (role == UserRole.UNKNOWN || !allowed) throw new IllegalStateException("PROFILE_NOT_ALLOWED");
        String fullName = profile.getString("fullName");
        return new AppUser(user.getUid(), user.getEmail() == null ? "" : user.getEmail(),
                fullName == null ? "" : fullName, role, true, status);
    }

    private static UserRole parseRole(String role) {
        if (AppConfig.CUSTOMER_ROLE.equals(role)) return UserRole.CUSTOMER;
        if (AppConfig.OFFICER_ROLE.equals(role)) return UserRole.OFFICER;
        return UserRole.UNKNOWN;
    }
}

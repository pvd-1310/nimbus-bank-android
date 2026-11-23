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
                    resolveProfile(current).addOnFailureListener(error -> {
                        authState.postValue(AuthState.error("Không thể xác thực hồ sơ người dùng."));
                        firebaseAuth.signOut();
                    });
                    String uid = current.getUid();
                    profileListener = firestore.collection("users").document(uid)
                            .addSnapshotListener((snapshot, error) -> {
                                if (error != null) {
                                    authState.postValue(AuthState.error("Không thể cập nhật trạng thái hồ sơ."));
                                    return;
                                }
                                try {
                                    if (snapshot == null || !snapshot.exists()) {
                                        authState.postValue(AuthState.error("Không tìm thấy hồ sơ người dùng."));
                                        return;
                                    }
                                    authState.postValue(AuthState.signedIn(toAppUser(current, snapshot)));
                                } catch (IllegalStateException invalid) {
                                    authState.postValue(AuthState.error("Tài khoản chưa được kích hoạt."));
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

    private Task<AppUser> resolveProfile(FirebaseUser user) {
        return firestore.collection("users").document(user.getUid()).get(Source.SERVER)
                .continueWith(profileTask -> {
                    if (!profileTask.isSuccessful() || profileTask.getResult() == null
                            || !profileTask.getResult().exists()) {
                        throw new IllegalStateException("PROFILE_NOT_FOUND");
                    }
                    AppUser appUser = toAppUser(user, profileTask.getResult());
                    authState.postValue(AuthState.signedIn(appUser));
                    return appUser;
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

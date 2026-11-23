package com.example.final_term.ui.auth;

import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.final_term.R;
import com.example.final_term.auth.AuthState;
import com.example.final_term.core.FinalTermApplication;
import com.example.final_term.databinding.FragmentLoginBinding;

import com.example.final_term.ui.ekyc.FaceGalleryPicker;
import com.example.final_term.data.BackendApiClient;

public final class LoginFragment extends Fragment {
    private FragmentLoginBinding binding;
    private BackendApiClient api;
    private boolean faceBusy;
    private final FaceGalleryPicker faceGalleryPicker = new FaceGalleryPicker(this, this::signInWithFace, this::faceError);

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentLoginBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        binding.emailInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        FinalTermApplication application = (FinalTermApplication) requireActivity().getApplication();
        api = application.getAppContainer().getBackendApiClient();
        binding.faceLoginButton.setEnabled(api != null);
        binding.faceLoginButton.setOnClickListener(v -> {
            binding.errorText.setVisibility(View.GONE);
            faceBusy = true;
            setFaceBusy();
            faceGalleryPicker.open();
        });
        application.getAppContainer().getAuthRepository().observeAuthState().observe(getViewLifecycleOwner(), this::renderState);
        binding.signInButton.setOnClickListener(v -> signIn());
        binding.resetPasswordButton.setOnClickListener(v -> sendPasswordReset());
    }

    private void signInWithFace(byte[] image) {
        if (binding == null || api == null) return;
        faceBusy = true;
        setFaceBusy();
        api.signInWithFace(image).addOnCompleteListener(task -> {
            faceBusy = false;
            if (binding == null) return;
            setFaceBusy();
            if (task.isSuccessful()) return;
            Exception error = task.getException();
            String code = error == null || error.getMessage() == null
                    ? "" : error.getMessage().toUpperCase(java.util.Locale.ROOT);
            String message;
            if (code.contains("FACE_SETUP_REQUIRED") || code.contains("SETUP_REQUIRED"))
                message = "Dịch vụ xác minh khuôn mặt chưa sẵn sàng. Vui lòng thử lại sau.";
            else if (code.contains("RATE_LIMITED")) message = "Quá nhiều lần thử. Vui lòng đợi rồi thử lại.";
            else if (code.contains("FACE_BUSY")) message = "Dịch vụ đang bận. Vui lòng thử lại sau giây lát.";
            else if (code.contains("FACE_LOGIN_FAILED")) message = "Không tìm thấy tài khoản khớp với ảnh hoặc tài khoản chưa đủ điều kiện.";
            else if (code.contains("FACE_LOW_QUALITY") || code.contains("MULTIPLE_FACES") || code.contains("FACE_NOT_FOUND"))
                message = "Chọn ảnh có một khuôn mặt rõ, đủ sáng và không bị che.";
            else if (code.contains("NETWORK_ERROR") || code.contains("NETWORK_REQUEST_FAILED")
                    || code.contains("UNAVAILABLE") || code.contains("TIMED OUT"))
                message = "Kết nối bị gián đoạn. Kiểm tra mạng rồi thử lại.";
            else if (code.contains("INVALID_CUSTOM_TOKEN") || code.contains("CUSTOM_TOKEN_MISMATCH"))
                message = "Phiên đăng nhập khuôn mặt không hợp lệ. Vui lòng thử lại.";
            else message = "Không thể đăng nhập bằng ảnh đã chọn.";
            faceError(message + " Bạn có thể dùng email và mật khẩu.");
        });
    }

    private void setFaceBusy() {
        binding.faceLoginButton.setEnabled(!faceBusy && api != null);
        binding.signInButton.setEnabled(!faceBusy);
        binding.progress.setVisibility(faceBusy ? View.VISIBLE : View.GONE);
    }

    private void faceError(String message) {
        faceBusy = false;
        if (binding == null) return;
        setFaceBusy();
        binding.errorText.setText(message);
        binding.errorText.setVisibility(View.VISIBLE);
    }

    private void sendPasswordReset() {
        String email = binding.emailInput.getText() == null ? "" : binding.emailInput.getText().toString().trim();
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.emailInput.setError("Nhập email hợp lệ để nhận liên kết đặt mật khẩu.");
            return;
        }
        binding.resetPasswordButton.setEnabled(false);
        ((FinalTermApplication) requireActivity().getApplication()).getAppContainer()
                .getAuthRepository().sendPasswordResetEmail(email)
                .addOnCompleteListener(task -> {
                    if (!isAdded() || binding == null) return;
                    binding.resetPasswordButton.setEnabled(true);
                    Toast.makeText(requireContext(), task.isSuccessful()
                            ? "Nếu email đã được đăng ký, liên kết đặt mật khẩu sẽ được gửi đến hộp thư."
                            : "Chưa gửi được email đặt mật khẩu. Vui lòng thử lại.", Toast.LENGTH_LONG).show();
                });
    }

    private void signIn() {
        binding.emailInput.setError(null);
        binding.passwordInput.setError(null);
        String email = binding.emailInput.getText() == null ? "" : binding.emailInput.getText().toString().trim();
        String password = binding.passwordInput.getText() == null ? "" : binding.passwordInput.getText().toString();
        if (email.isEmpty()) { binding.emailInput.setError(getString(R.string.error_email_required)); return; }
        if (password.isEmpty()) { binding.passwordInput.setError(getString(R.string.error_password_required)); return; }
        binding.signInButton.setEnabled(false);
        ((FinalTermApplication) requireActivity().getApplication()).getAppContainer()
                .getAuthRepository().signIn(email, password)
                .addOnCompleteListener(task -> {
                    if (binding == null) return;
                    if (!task.isSuccessful()) {
                        String message = task.getException() == null
                                ? getString(R.string.error_sign_in)
                                : mapError(task.getException().getMessage());
                        binding.errorText.setText(message);
                        binding.errorText.setVisibility(View.VISIBLE);
                    }
                    binding.signInButton.setEnabled(true);
                });
    }

    private void renderState(AuthState state) {
        if (state == null) return;
        boolean loading = faceBusy || state.getStatus() == AuthState.Status.LOADING;
        binding.faceLoginButton.setEnabled(!loading && api != null);
        binding.signInButton.setEnabled(!loading);
        binding.progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (state.getStatus() == AuthState.Status.SETUP_REQUIRED) {
            binding.errorText.setVisibility(View.VISIBLE);
            binding.errorText.setText(getString(R.string.production_configuration_required));
            binding.signInButton.setEnabled(false);
        }
        if (state.getStatus() == AuthState.Status.ERROR) {
            binding.errorText.setVisibility(View.VISIBLE);
            binding.errorText.setText(state.getMessage());
        } else if (!loading) {
            binding.errorText.setVisibility(View.GONE);
        }
    }

    private String mapError(String raw) {
        if (raw == null) return getString(R.string.error_sign_in);
        if (raw.contains("PROFILE_NOT_FOUND")) return getString(R.string.error_profile_missing);
        if (raw.contains("PROFILE_NOT_ALLOWED")) return getString(R.string.error_profile_denied);
        if (raw.contains("FIREBASE_SETUP_REQUIRED")) return getString(R.string.production_configuration_required);
        return getString(R.string.error_sign_in);
    }

    @Override
    public void onDestroyView() { super.onDestroyView(); binding = null; }
}

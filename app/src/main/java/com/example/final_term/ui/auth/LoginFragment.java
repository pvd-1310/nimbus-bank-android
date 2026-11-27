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

import com.example.final_term.ui.ekyc.FaceScanDialogFragment;
import com.example.final_term.data.BackendApiClient;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LoginFragment extends Fragment {
    private static final ExecutorService FACE_IO = Executors.newSingleThreadExecutor();
    private FragmentLoginBinding binding;
    private BackendApiClient api;
    private boolean faceBusy;
    private boolean preserveFaceError;

    @Override
    public void onResume() {
        super.onResume();
        WelcomeGuide.showIfNeeded(this);
    }

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
        getParentFragmentManager().setFragmentResultListener(FaceScanDialogFragment.RESULT_KEY,
                getViewLifecycleOwner(), (key, result) -> {
                    String path = result.getString(FaceScanDialogFragment.RESULT_PATH);
                    String error = result.getString(FaceScanDialogFragment.RESULT_ERROR);
                    if (path != null) {
                        faceBusy = true;
                        setFaceBusy();
                        FACE_IO.execute(() -> {
                            File file = new File(path);
                            try {
                                byte[] bytes = Files.readAllBytes(file.toPath());
                                requireActivity().runOnUiThread(() -> signInWithFace(bytes));
                            } catch (Exception e) {
                                requireActivity().runOnUiThread(() -> faceError("Không đọc được ảnh khuôn mặt. Vui lòng thử lại."));
                            } finally {
                                file.delete();
                            }
                        });
                    } else if (error != null && !error.trim().isEmpty()) {
                        faceError(error);
                    }
                });
        binding.faceLoginButton.setEnabled(api != null);
        binding.faceLoginButton.setOnClickListener(v -> {
            preserveFaceError = false;
            binding.errorText.setVisibility(View.GONE);
            FaceScanDialogFragment.open(this);
        });
        application.getAppContainer().getAuthRepository().observeAuthState().observe(getViewLifecycleOwner(), this::renderState);
        var currentEntry = androidx.navigation.fragment.NavHostFragment.findNavController(this).getCurrentBackStackEntry();
        if (currentEntry != null) {
            currentEntry.getSavedStateHandle().<String>getLiveData(ResetPasswordFragment.ARG_EMAIL)
                    .observe(getViewLifecycleOwner(), updatedEmail -> {
                        if (binding != null && updatedEmail != null && !updatedEmail.isEmpty()) {
                            binding.emailInput.setText(updatedEmail);
                            binding.passwordInput.setText("");
                        }
                    });
        }
        binding.signInButton.setOnClickListener(v -> signIn());
        binding.resetPasswordButton.setOnClickListener(v -> openResetPassword());
        binding.registerButton.setOnClickListener(v -> openRegister());
    }

    private void signInWithFace(byte[] image) {
        if (binding == null || api == null) return;
        preserveFaceError = false;
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
            else if (code.contains("RATE_LIMITED")) message = "Quá nhiều lần thử. Vui lòng đợi vài phút rồi thử lại.";
            else if (code.contains("FACE_BUSY")) message = "Dịch vụ đang bận. Vui lòng thử lại sau giây lát.";
            else if (code.contains("FACE_TEMPLATE_OUTDATED"))
                message = "Mẫu khuôn mặt đã cũ. Vui lòng đăng nhập bằng mật khẩu và định danh lại eKYC.";
            else if (code.contains("FACE_LOGIN_FAILED"))
                message = "Không tìm thấy tài khoản khớp với khuôn mặt (cần hoàn tất eKYC và được duyệt).";
            else if (code.contains("FACE_BLURRY")) message = "Ảnh bị mờ. Vui lòng giữ máy ổn định và thử lại.";
            else if (code.contains("FACE_EXPOSURE")) message = "Ánh sáng chưa đạt (quá tối hoặc quá chói).";
            else if (code.contains("FACE_TOO_SMALL") || code.contains("FACE_CROPPED"))
                message = "Khuôn mặt quá nhỏ hoặc bị cắt góc. Đưa mặt vào giữa khung hình.";
            else if (code.contains("FACE_LOW_QUALITY") || code.contains("MULTIPLE_FACES")
                    || code.contains("FACE_NOT_FOUND") || code.contains("FACE_DETECTION_UNCERTAIN")
                    || code.contains("FACE_LANDMARKS_INVALID") || code.contains("INVALID_IMAGE"))
                message = "Chọn ảnh có một khuôn mặt rõ, đủ sáng và không bị che.";
            else if (code.contains("NETWORK_ERROR") || code.contains("NETWORK_REQUEST_FAILED")
                    || code.contains("UNAVAILABLE") || code.contains("TIMED OUT") || code.contains("FAILED TO CONNECT"))
                message = "Không kết nối được tới máy chủ. Kiểm tra mạng hoặc ADB reverse.";
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
        preserveFaceError = true;
        if (binding == null) return;
        setFaceBusy();
        binding.errorText.setText(message);
        binding.errorText.setVisibility(View.VISIBLE);
    }

    private void openResetPassword() {
        preserveFaceError = false;
        binding.errorText.setVisibility(View.GONE);
        String email = binding.emailInput.getText() == null ? "" : binding.emailInput.getText().toString().trim();
        Bundle args = new Bundle();
        args.putString(ResetPasswordFragment.ARG_EMAIL, email);
        androidx.navigation.fragment.NavHostFragment.findNavController(this).navigate(R.id.resetPassword, args);
    }

    private void openRegister() {
        preserveFaceError = false;
        binding.errorText.setVisibility(View.GONE);
        androidx.navigation.fragment.NavHostFragment.findNavController(this).navigate(R.id.register);
    }

    private void signIn() {
        preserveFaceError = false;
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
            preserveFaceError = false;
            binding.errorText.setVisibility(View.VISIBLE);
            binding.errorText.setText(mapError(state.getMessage()));
        } else if (!loading && !preserveFaceError) {
            binding.errorText.setVisibility(View.GONE);
        }
    }

    private String mapError(String raw) {
        if (raw == null) return getString(R.string.error_sign_in);
        String normalized = raw.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("supplied auth credential")
                || normalized.contains("invalid_credential")
                || normalized.contains("invalid-credential")
                || normalized.contains("invalid_login_credentials")) {
            return "Thông tin đăng nhập không hợp lệ hoặc đã hết hạn. Vui lòng kiểm tra lại.";
        }
        if (raw.contains("PROFILE_NOT_FOUND")) return getString(R.string.error_profile_missing);
        if (raw.contains("PROFILE_NOT_ALLOWED")) return getString(R.string.error_profile_denied);
        if (raw.contains("FIREBASE_SETUP_REQUIRED")) return getString(R.string.production_configuration_required);
        return getString(R.string.error_sign_in);
    }

    @Override
    public void onDestroyView() { super.onDestroyView(); binding = null; }
}

package com.example.final_term.ui.auth;

import android.os.Bundle;
import android.os.CountDownTimer;
import android.text.TextUtils;
import android.util.Patterns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import com.example.final_term.R;
import com.example.final_term.core.FinalTermApplication;
import com.example.final_term.data.BackendApiClient;
import com.example.final_term.databinding.FragmentResetPasswordBinding;
import com.example.final_term.ui.transaction.OtpBottomSheetDialog;

import org.json.JSONObject;

import java.util.Locale;

public final class ResetPasswordFragment extends Fragment {
    public static final String ARG_EMAIL = "email";

    private FragmentResetPasswordBinding binding;
    private BackendApiClient api;
    @Nullable
    private OtpBottomSheetDialog otpDialog;
    @Nullable
    private CountDownTimer resendTimer;

    private String activeEmail = "";
    private String challengeId = "";
    private String resetToken = "";
    private boolean busy;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentResetPasswordBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        FinalTermApplication application = (FinalTermApplication) requireActivity().getApplication();
        api = application.getAppContainer().getBackendApiClient();

        Bundle args = getArguments();
        String initialEmail = args != null ? args.getString(ARG_EMAIL, "") : "";
        if (!TextUtils.isEmpty(initialEmail)) {
            binding.resetEmailInput.setText(initialEmail.trim());
        }

        binding.resetBackButton.setOnClickListener(v -> returnToLogin());
        binding.resetBackToLoginButton.setOnClickListener(v -> returnToLogin());
        binding.resetRequestOtpButton.setOnClickListener(v -> requestResetOtp(false));
        binding.resetSubmitButton.setOnClickListener(v -> submitNewPassword());
    }

    private void requestResetOtp(boolean fromResend) {
        if (binding == null || busy) return;
        clearError();
        if (api == null) {
            showError("Không thể kết nối máy chủ đặt lại mật khẩu.");
            return;
        }

        String email = binding.resetEmailInput.getText() == null
                ? "" : binding.resetEmailInput.getText().toString().trim();
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.resetEmailInput.setError("Vui lòng nhập email hợp lệ.");
            return;
        }
        binding.resetEmailInput.setError(null);

        setBusy(true);
        if (fromResend && otpDialog != null) {
            otpDialog.setBusy(true);
            otpDialog.setStatusMessage(null);
        }

        try {
            JSONObject payload = new JSONObject();
            payload.put("email", email);
            api.postPublic("/auth/password-reset/request", payload.toString())
                    .addOnCompleteListener(task -> {
                        if (!isAdded() || binding == null) return;
                        setBusy(false);
                        if (otpDialog != null) otpDialog.setBusy(false);

                        if (!task.isSuccessful() || task.getResult() == null) {
                            String msg = "Không thể kiểm tra email. Vui lòng kiểm tra kết nối và thử lại.";
                            if (fromResend && otpDialog != null) otpDialog.setStatusMessage(msg);
                            else showError(msg);
                            return;
                        }

                        BackendApiClient.Response response = task.getResult();
                        JSONObject json = parseJson(response.body);
                        if (response.statusCode < 200 || response.statusCode >= 300 || json == null) {
                            String msg = mapResetError(json != null ? json.optString("error", "") : "", response.statusCode);
                            if (fromResend && otpDialog != null) otpDialog.setStatusMessage(msg);
                            else showError(msg);
                            return;
                        }

                        activeEmail = email;
                        challengeId = json.optString("challengeId", "");
                        resetToken = "";
                        String code = json.optString("code", "");
                        String phoneMasked = json.optString("phoneMasked", "");
                        showOtpSheet(email, phoneMasked, code);
                    });
        } catch (Exception e) {
            setBusy(false);
            if (otpDialog != null) otpDialog.setBusy(false);
            showError("Không thể tạo yêu cầu đặt lại mật khẩu.");
        }
    }

    private void showOtpSheet(String email, String phoneMasked, String demoCode) {
        if (getContext() == null) return;
        if (otpDialog == null) {
            otpDialog = new OtpBottomSheetDialog(requireContext(), new OtpBottomSheetDialog.Listener() {
                @Override
                public void onSubmitOtp(@NonNull String code) {
                    verifyResetOtp(code);
                }

                @Override
                public void onResendRequested() {
                    requestResetOtp(true);
                }

                @Override
                public void onDismissRequested() {
                    stopResendTimer();
                    if (otpDialog != null) otpDialog.dismiss();
                }
            });
        }

        String target = !TextUtils.isEmpty(phoneMasked)
                ? email + " (" + phoneMasked + ")"
                : maskEmail(email);
        otpDialog.setSubtitle("Nhập mã OTP 6 số xác thực cho " + target);
        otpDialog.setStatusMessage(null);
        otpDialog.setBusy(false);
        otpDialog.setDemoCode(demoCode);
        if (!otpDialog.isShowing()) {
            otpDialog.show();
        }
        startResendCountdown();
    }

    private void verifyResetOtp(String code) {
        if (binding == null || api == null || TextUtils.isEmpty(challengeId)) return;
        if (otpDialog != null) {
            otpDialog.setBusy(true);
            otpDialog.setStatusMessage(null);
        }

        try {
            JSONObject payload = new JSONObject();
            payload.put("challengeId", challengeId);
            payload.put("code", code);
            api.postPublic("/auth/password-reset/verify-otp", payload.toString())
                    .addOnCompleteListener(task -> {
                        if (!isAdded() || binding == null) return;
                        if (otpDialog != null) otpDialog.setBusy(false);

                        if (!task.isSuccessful() || task.getResult() == null) {
                            if (otpDialog != null) {
                                otpDialog.setStatusMessage("Lỗi kết nối khi xác thực OTP. Vui lòng thử lại.");
                            }
                            return;
                        }

                        BackendApiClient.Response response = task.getResult();
                        JSONObject json = parseJson(response.body);
                        if (response.statusCode < 200 || response.statusCode >= 300 || json == null) {
                            String errCode = json != null ? json.optString("error", "") : "";
                            if (otpDialog != null) {
                                otpDialog.setStatusMessage(mapResetError(errCode, response.statusCode));
                            }
                            return;
                        }

                        String token = json.optString("resetToken", "");
                        if (TextUtils.isEmpty(token)) {
                            if (otpDialog != null) {
                                otpDialog.setStatusMessage("Không nhận được mã xác thực đặt lại mật khẩu.");
                            }
                            return;
                        }

                        resetToken = token;
                        stopResendTimer();
                        if (otpDialog != null) otpDialog.dismiss();
                        showPasswordStep();
                    });
        } catch (Exception e) {
            if (otpDialog != null) {
                otpDialog.setBusy(false);
                otpDialog.setStatusMessage("Không thể xác thực OTP.");
            }
        }
    }

    private void showPasswordStep() {
        if (binding == null) return;
        clearError();
        binding.resetStepBadge.setText("Bước 2/2");
        binding.resetEmailInput.setEnabled(false);
        binding.resetRequestOtpButton.setVisibility(View.GONE);
        binding.resetPasswordCard.setVisibility(View.VISIBLE);
        binding.resetNewPasswordInput.requestFocus();
    }

    private void submitNewPassword() {
        if (binding == null || busy) return;
        clearError();
        if (api == null || TextUtils.isEmpty(challengeId) || TextUtils.isEmpty(resetToken)) {
            showError("Phiên xác thực OTP chưa sẵn sàng. Vui lòng thực hiện lại.");
            return;
        }

        String newPassword = binding.resetNewPasswordInput.getText() == null
                ? "" : binding.resetNewPasswordInput.getText().toString();
        String confirmPassword = binding.resetConfirmPasswordInput.getText() == null
                ? "" : binding.resetConfirmPasswordInput.getText().toString();

        if (newPassword.length() < 8) {
            binding.resetNewPasswordInput.setError("Mật khẩu mới phải có ít nhất 8 ký tự.");
            return;
        }
        binding.resetNewPasswordInput.setError(null);

        if (!newPassword.equals(confirmPassword)) {
            binding.resetConfirmPasswordInput.setError("Mật khẩu nhập lại không khớp.");
            return;
        }
        binding.resetConfirmPasswordInput.setError(null);

        setBusy(true);
        try {
            JSONObject payload = new JSONObject();
            payload.put("challengeId", challengeId);
            payload.put("resetToken", resetToken);
            payload.put("newPassword", newPassword);
            api.postPublic("/auth/password-reset/confirm", payload.toString())
                    .addOnCompleteListener(task -> {
                        if (!isAdded() || binding == null) return;
                        setBusy(false);

                        if (!task.isSuccessful() || task.getResult() == null) {
                            showError("Không thể cập nhật mật khẩu. Vui lòng thử lại.");
                            return;
                        }

                        BackendApiClient.Response response = task.getResult();
                        JSONObject json = parseJson(response.body);
                        if (response.statusCode < 200 || response.statusCode >= 300 || json == null
                                || !json.optBoolean("updated", false)) {
                            String errCode = json != null ? json.optString("error", "") : "";
                            if ("RESET_TOKEN_EXPIRED".equals(errCode) || "RESET_TOKEN_INVALID".equals(errCode)) {
                                resetToEmailStep();
                            }
                            showError(mapResetError(errCode, response.statusCode));
                            return;
                        }

                        Toast.makeText(
                                requireContext(),
                                "Đặt lại mật khẩu thành công. Vui lòng đăng nhập lại.",
                                Toast.LENGTH_LONG
                        ).show();
                        returnToLogin();
                    });
        } catch (Exception e) {
            setBusy(false);
            showError("Không thể hoàn tất đặt lại mật khẩu.");
        }
    }

    private void resetToEmailStep() {
        if (binding == null) return;
        challengeId = "";
        resetToken = "";
        binding.resetStepBadge.setText("Bước 1/2");
        binding.resetEmailInput.setEnabled(true);
        binding.resetRequestOtpButton.setVisibility(View.VISIBLE);
        binding.resetPasswordCard.setVisibility(View.GONE);
        binding.resetNewPasswordInput.setText("");
        binding.resetConfirmPasswordInput.setText("");
    }

    private void returnToLogin() {
        if (!isAdded()) return;
        NavController navController = NavHostFragment.findNavController(this);
        if (!TextUtils.isEmpty(activeEmail) && navController.getPreviousBackStackEntry() != null) {
            navController.getPreviousBackStackEntry().getSavedStateHandle().set(ARG_EMAIL, activeEmail);
        }
        if (!navController.popBackStack(R.id.login, false)) {
            navController.navigate(R.id.login);
        }
    }

    private void startResendCountdown() {
        stopResendTimer();
        if (otpDialog == null) return;
        resendTimer = new CountDownTimer(30_000L, 1_000L) {
            @Override
            public void onTick(long millisUntilFinished) {
                if (otpDialog == null || !isAdded()) return;
                long seconds = (millisUntilFinished + 999L) / 1_000L;
                otpDialog.setCountdownText(
                        String.format(Locale.ROOT, "Gửi lại mã sau 00:%02d", seconds),
                        false
                );
            }

            @Override
            public void onFinish() {
                if (otpDialog == null || !isAdded()) return;
                otpDialog.setCountdownText("Gửi lại mã", true);
            }
        }.start();
    }

    private void stopResendTimer() {
        if (resendTimer != null) {
            resendTimer.cancel();
            resendTimer = null;
        }
    }

    private void setBusy(boolean loading) {
        busy = loading;
        if (binding == null) return;
        binding.resetProgress.setVisibility(loading ? View.VISIBLE : View.GONE);
        binding.resetRequestOtpButton.setEnabled(!loading);
        binding.resetSubmitButton.setEnabled(!loading);
    }

    private void clearError() {
        if (binding == null) return;
        binding.resetErrorText.setVisibility(View.GONE);
        binding.resetErrorText.setText("");
    }

    private void showError(String message) {
        if (binding == null) return;
        binding.resetErrorText.setText(message);
        binding.resetErrorText.setVisibility(View.VISIBLE);
    }

    @Nullable
    private static JSONObject parseJson(@Nullable String body) {
        if (body == null || body.trim().isEmpty()) return null;
        try {
            return new JSONObject(body);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 2) return email;
        return email.substring(0, 2) + "***" + email.substring(at);
    }

    private static String mapResetError(String code, int statusCode) {
        switch (code) {
            case "EMAIL_NOT_FOUND":
                return "Email không tồn tại hoặc tài khoản chưa được kích hoạt trong hệ thống.";
            case "INVALID_OTP":
                return "Mã OTP không chính xác. Vui lòng kiểm tra lại.";
            case "OTP_EXPIRED":
                return "Mã OTP đã hết hạn. Vui lòng nhấn Gửi lại mã.";
            case "OTP_LOCKED":
                return "Bạn đã nhập sai OTP quá 5 lần. Vui lòng nhấn Gửi lại mã mới.";
            case "OTP_USED":
                return "Mã OTP này đã được sử dụng. Vui lòng yêu cầu mã mới.";
            case "WEAK_PASSWORD":
                return "Mật khẩu mới phải có từ 8 đến 128 ký tự.";
            case "RESET_TOKEN_EXPIRED":
            case "RESET_TOKEN_INVALID":
                return "Phiên đặt lại mật khẩu đã hết hạn. Vui lòng xác thực lại OTP.";
            case "RATE_LIMITED":
                return "Bạn đã gửi yêu cầu quá nhiều lần. Vui lòng thử lại sau ít phút.";
            case "PASSWORD_RESET_UNAVAILABLE":
                return "Dịch vụ đặt lại mật khẩu hiện chưa khả dụng.";
            default:
                if (statusCode == 400) return "Thông tin chưa hợp lệ. Vui lòng kiểm tra lại.";
                return "Không thể xử lý yêu cầu đặt lại mật khẩu (mã " + statusCode + ").";
        }
    }

    @Override
    public void onDestroyView() {
        stopResendTimer();
        if (otpDialog != null) {
            otpDialog.dismiss();
            otpDialog = null;
        }
        binding = null;
        super.onDestroyView();
    }
}

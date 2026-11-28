package com.example.final_term.ui.auth;

import android.os.Bundle;
import android.util.Patterns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import com.example.final_term.core.FinalTermApplication;
import com.example.final_term.data.BackendApiClient;
import com.example.final_term.databinding.FragmentResetPasswordBinding;

import org.json.JSONException;
import org.json.JSONObject;

public final class ResetPasswordFragment extends Fragment {
    public static final String ARG_EMAIL = "email";
    private static final String NETWORK_ERROR = "Chưa thể kết nối máy chủ. Vui lòng thử lại.";

    private FragmentResetPasswordBinding binding;
    private BackendApiClient api;
    private EmailOtpSheet otpSheet;
    private String challengeEmail;
    private String resetToken;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle state) {
        binding = FragmentResetPasswordBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle state) {
        super.onViewCreated(view, state);
        FinalTermApplication app = (FinalTermApplication) requireActivity().getApplication();
        api = app.getAppContainer().getBackendApiClient();
        if (getArguments() != null) binding.resetEmailInput.setText(getArguments().getString(ARG_EMAIL, ""));
        binding.resetBackButton.setOnClickListener(v -> returnToEmailOrLogin());
        binding.resetBackToLoginButton.setOnClickListener(v -> NavHostFragment.findNavController(this).navigateUp());
        binding.resetSendCodeButton.setOnClickListener(v -> requestOtp(false));
        binding.resetSubmitButton.setOnClickListener(v -> submitPassword());
    }

    private void requestOtp(boolean resend) {
        var form = binding;
        String email = resend ? challengeEmail : text(form.resetEmailInput).trim();
        form.resetErrorText.setVisibility(View.GONE);
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            form.resetEmailInput.setError("Vui lòng nhập email hợp lệ.");
            return;
        }
        form.resetEmailInput.setError(null);
        if (!resend && otpSheet != null && email.equalsIgnoreCase(challengeEmail)) {
            otpSheet.reopen();
            return;
        }
        setBusy(true);
        try {
            api.postPublic("/auth/password-reset/email-otp", new JSONObject().put("email", email).toString())
                    .addOnCompleteListener(task -> {
                        if (binding != form) return;
                        setBusy(false);
                        if (!task.isSuccessful()) {
                            showError(NETWORK_ERROR);
                            return;
                        }
                        if (task.getResult().statusCode != 200) {
                            showError(EmailOtpSheet.responseError(task.getResult().body));
                            return;
                        }
                        try {
                            JSONObject result = new JSONObject(task.getResult().body);
                            if (otpSheet == null || !email.equalsIgnoreCase(challengeEmail)) {
                                if (otpSheet != null) otpSheet.dismiss();
                                otpSheet = new EmailOtpSheet(requireContext(),
                                        "Nếu email đã đăng ký, bạn sẽ nhận được mã OTP. Hãy nhập mã 6 số.",
                                        this::verifyOtp, () -> requestOtp(true));
                            }
                            challengeEmail = email;
                            otpSheet.show(result.getString("challengeId"), result.getInt("resendAfterSeconds"));
                        } catch (JSONException invalid) {
                            showError("Không đọc được phản hồi gửi mã. Vui lòng thử lại.");
                        }
                    });
        } catch (JSONException invalid) {
            setBusy(false);
            showError("Vui lòng kiểm tra lại email.");
        }
    }

    private void verifyOtp(String code) {
        var form = binding;
        setBusy(true);
        try {
            String body = new JSONObject().put("challengeId", otpSheet.challengeId()).put("code", code).toString();
            api.postPublic("/auth/password-reset/email-otp/verify", body).addOnCompleteListener(task -> {
                if (binding != form) return;
                setBusy(false);
                if (!task.isSuccessful()) {
                    showError(NETWORK_ERROR);
                } else if (task.getResult().statusCode != 200) {
                    showError(EmailOtpSheet.responseError(task.getResult().body));
                } else {
                    try {
                        showPasswordForm(new JSONObject(task.getResult().body).getString("resetToken"));
                    } catch (JSONException invalid) {
                        showError("Phản hồi xác minh chưa hợp lệ. Vui lòng thử lại.");
                    }
                }
            });
        } catch (JSONException invalid) {
            setBusy(false);
            showError("Vui lòng nhập lại mã.");
        }
    }

    private void showPasswordForm(String token) {
        otpSheet.dismiss();
        resetToken = token;
        binding.resetEmailCard.setVisibility(View.GONE);
        binding.resetPasswordCard.setVisibility(View.VISIBLE);
        binding.resetErrorText.setVisibility(View.GONE);
        binding.resetNewPasswordInput.requestFocus();
    }

    private void submitPassword() {
        var form = binding;
        if (resetToken == null || otpSheet == null) return;
        String password = text(form.resetNewPasswordInput);
        if (password.length() < 8 || password.length() > 128) {
            form.resetNewPasswordInput.setError("Mật khẩu phải từ 8 đến 128 ký tự.");
            return;
        }
        if (!password.equals(text(form.resetConfirmPasswordInput))) {
            form.resetConfirmPasswordInput.setError("Mật khẩu nhập lại chưa khớp.");
            return;
        }
        setBusy(true);
        try {
            String body = new JSONObject().put("resetToken", resetToken).put("password", password).toString();
            api.postPublic("/auth/password-reset/email-otp/confirm", body).addOnCompleteListener(task -> {
                if (binding != form) return;
                setBusy(false);
                if (!task.isSuccessful()) {
                    showError(NETWORK_ERROR);
                } else if (task.getResult().statusCode != 200) {
                    returnToEmailOrLogin();
                    otpSheet.reopen();
                    otpSheet.fail(EmailOtpSheet.responseError(task.getResult().body));
                } else {
                    resetToken = null;
                    Toast.makeText(requireContext(), "Đã đặt lại mật khẩu. Hãy đăng nhập lại.", Toast.LENGTH_LONG).show();
                    NavHostFragment.findNavController(this).navigateUp();
                }
            });
        } catch (JSONException invalid) {
            setBusy(false);
            showError("Thông tin đặt lại mật khẩu chưa hợp lệ.");
        }
    }

    private void returnToEmailOrLogin() {
        if (!binding.resetBackButton.isEnabled()) return;
        if (resetToken == null) {
            NavHostFragment.findNavController(this).navigateUp();
            return;
        }
        resetToken = null;
        binding.resetNewPasswordInput.setText("");
        binding.resetConfirmPasswordInput.setText("");
        binding.resetPasswordCard.setVisibility(View.GONE);
        binding.resetEmailCard.setVisibility(View.VISIBLE);
        binding.resetErrorText.setVisibility(View.GONE);
    }

    private void setBusy(boolean busy) {
        if (otpSheet != null) otpSheet.setBusy(busy);
        binding.resetProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
        binding.resetSendCodeButton.setEnabled(!busy);
        binding.resetSubmitButton.setEnabled(!busy);
        binding.resetBackButton.setEnabled(!busy);
        binding.resetBackToLoginButton.setEnabled(!busy);
    }

    private void showError(String message) {
        if (otpSheet != null && otpSheet.isShowing()) {
            otpSheet.fail(message);
        } else {
            binding.resetErrorText.setText(message);
            binding.resetErrorText.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onDestroyView() {
        if (otpSheet != null) otpSheet.dismiss();
        otpSheet = null;
        resetToken = null;
        binding = null;
        super.onDestroyView();
    }

    private static String text(EditText input) {
        return input.getText() == null ? "" : input.getText().toString();
    }
}

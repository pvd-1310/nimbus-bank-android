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
import androidx.navigation.fragment.NavHostFragment;

import com.example.final_term.R;
import com.example.final_term.auth.AuthRepository;
import com.example.final_term.core.FinalTermApplication;
import com.example.final_term.data.BackendApiClient;
import com.example.final_term.databinding.FragmentRegisterBinding;
import com.example.final_term.ui.ekyc.EkycDialogFragment;
import com.example.final_term.ui.transaction.OtpBottomSheetDialog;

import org.json.JSONObject;

import java.util.Locale;

public final class RegisterFragment extends Fragment {
    private static final String EKYC_TAG = "register-ekyc-dialog";

    private FragmentRegisterBinding binding;
    private BackendApiClient api;
    private AuthRepository authRepository;
    @Nullable
    private OtpBottomSheetDialog otpDialog;
    @Nullable
    private CountDownTimer resendTimer;

    private String challengeId = "";
    private String pendingEmail = "";
    private String pendingPassword = "";
    private boolean busy;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentRegisterBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        FinalTermApplication application = (FinalTermApplication) requireActivity().getApplication();
        api = application.getAppContainer().getBackendApiClient();
        authRepository = application.getAppContainer().getAuthRepository();

        binding.registerBackButton.setOnClickListener(v -> returnToLogin());
        binding.registerBackToLoginButton.setOnClickListener(v -> returnToLogin());
        binding.registerSubmitButton.setOnClickListener(v -> requestRegistrationOtp(false));
        binding.registerOpenEkycButton.setOnClickListener(v -> openEkycDialog());
        binding.registerSignOutButton.setOnClickListener(v -> {
            if (authRepository != null) authRepository.signOut();
            returnToLogin();
        });
    }

    private void requestRegistrationOtp(boolean fromResend) {
        if (binding == null || busy) return;
        clearError();
        if (api == null) {
            showError("Không thể kết nối máy chủ đăng ký.");
            return;
        }

        String fullName = textOf(binding.registerFullNameInput);
        String email = textOf(binding.registerEmailInput);
        String phone = textOf(binding.registerPhoneInput);
        String nationalId = textOf(binding.registerNationalIdInput);
        String password = rawTextOf(binding.registerPasswordInput);
        String confirmPassword = rawTextOf(binding.registerConfirmPasswordInput);

        if (fullName.length() < 2) {
            binding.registerFullNameInput.setError("Vui lòng nhập họ và tên hợp lệ.");
            return;
        }
        binding.registerFullNameInput.setError(null);

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.registerEmailInput.setError("Vui lòng nhập email hợp lệ.");
            return;
        }
        binding.registerEmailInput.setError(null);

        if (!phone.matches("^(?:0[0-9]{9}|\\+84[0-9]{9})$")) {
            binding.registerPhoneInput.setError("Số điện thoại phải gồm 10 số (VD: 0901234567).");
            return;
        }
        binding.registerPhoneInput.setError(null);

        if (!nationalId.matches("^\\d{12}$")) {
            binding.registerNationalIdInput.setError("Số CCCD phải gồm đúng 12 chữ số.");
            return;
        }
        binding.registerNationalIdInput.setError(null);

        if (password.length() < 8 || password.length() > 128) {
            binding.registerPasswordInput.setError("Mật khẩu phải từ 8 đến 128 ký tự.");
            return;
        }
        binding.registerPasswordInput.setError(null);

        if (!password.equals(confirmPassword)) {
            binding.registerConfirmPasswordInput.setError("Mật khẩu xác nhận không khớp.");
            return;
        }
        binding.registerConfirmPasswordInput.setError(null);

        setBusy(true);
        if (fromResend && otpDialog != null) {
            otpDialog.setBusy(true);
            otpDialog.setStatusMessage(null);
        }

        try {
            JSONObject payload = new JSONObject();
            payload.put("fullName", fullName);
            payload.put("email", email);
            payload.put("phoneNumber", phone);
            payload.put("nationalIdNumber", nationalId);
            payload.put("password", password);

            api.postPublic("/auth/register/request", payload.toString())
                    .addOnCompleteListener(task -> {
                        if (!isAdded() || binding == null) return;
                        setBusy(false);
                        if (otpDialog != null) otpDialog.setBusy(false);

                        if (!task.isSuccessful() || task.getResult() == null) {
                            String detail = task.getException() != null && !TextUtils.isEmpty(task.getException().getMessage())
                                    ? " (" + task.getException().getMessage() + ")"
                                    : "";
                            String msg = "Không thể kết nối máy chủ" + detail + ". Vui lòng thử lại.";
                            if (fromResend && otpDialog != null) otpDialog.setStatusMessage(msg);
                            else showError(msg);
                            return;
                        }

                        BackendApiClient.Response response = task.getResult();
                        JSONObject json = parseJson(response.body);
                        if (response.statusCode < 200 || response.statusCode >= 300 || json == null) {
                            String errCode = json != null ? json.optString("error", "") : "";
                            String requestId = json != null ? json.optString("requestId", "") : "";
                            String msg = mapRegisterError(errCode, response.statusCode, requestId);
                            if (fromResend && otpDialog != null) otpDialog.setStatusMessage(msg);
                            else showError(msg);
                            return;
                        }

                        challengeId = json.optString("challengeId", "");
                        pendingEmail = email;
                        pendingPassword = password;
                        String demoCode = json.optString("code", "");
                        String phoneMasked = json.optString("phoneMasked", "");
                        showOtpSheet(email, phoneMasked, demoCode);
                    });
        } catch (Exception e) {
            setBusy(false);
            if (otpDialog != null) otpDialog.setBusy(false);
            showError("Không thể tạo yêu cầu đăng ký.");
        }
    }

    private void showOtpSheet(String email, String phoneMasked, String demoCode) {
        if (getContext() == null) return;
        if (otpDialog == null) {
            otpDialog = new OtpBottomSheetDialog(requireContext(), new OtpBottomSheetDialog.Listener() {
                @Override
                public void onSubmitOtp(@NonNull String code) {
                    confirmRegistrationOtp(code);
                }

                @Override
                public void onResendRequested() {
                    requestRegistrationOtp(true);
                }

                @Override
                public void onDismissRequested() {
                    stopResendTimer();
                    if (otpDialog != null) otpDialog.dismiss();
                }
            });
        }

        String target = !TextUtils.isEmpty(phoneMasked) ? email + " (" + phoneMasked + ")" : email;
        otpDialog.setSubtitle("Nhập mã OTP 6 số xác thực đăng ký cho " + target);
        otpDialog.setStatusMessage(null);
        otpDialog.setBusy(false);
        otpDialog.setDemoCode(demoCode);
        if (!otpDialog.isShowing()) {
            otpDialog.show();
        }
        startResendCountdown();
    }

    private void confirmRegistrationOtp(String code) {
        if (binding == null || api == null || TextUtils.isEmpty(challengeId)) return;
        if (otpDialog != null) {
            otpDialog.setBusy(true);
            otpDialog.setStatusMessage(null);
        }

        try {
            JSONObject payload = new JSONObject();
            payload.put("challengeId", challengeId);
            payload.put("code", code);
            payload.put("password", pendingPassword);

            api.postPublic("/auth/register/confirm", payload.toString())
                    .addOnCompleteListener(task -> {
                        if (!isAdded() || binding == null) return;

                        if (!task.isSuccessful() || task.getResult() == null) {
                            if (otpDialog != null) {
                                otpDialog.setBusy(false);
                                String detail = task.getException() != null && !TextUtils.isEmpty(task.getException().getMessage())
                                        ? " (" + task.getException().getMessage() + ")"
                                        : "";
                                otpDialog.setStatusMessage("Lỗi kết nối khi xác thực OTP" + detail + ". Vui lòng thử lại.");
                            }
                            return;
                        }

                        BackendApiClient.Response response = task.getResult();
                        JSONObject json = parseJson(response.body);
                        if (response.statusCode < 200 || response.statusCode >= 300 || json == null) {
                            if (otpDialog != null) {
                                otpDialog.setBusy(false);
                                String errCode = json != null ? json.optString("error", "") : "";
                                String requestId = json != null ? json.optString("requestId", "") : "";
                                otpDialog.setStatusMessage(mapRegisterError(errCode, response.statusCode, requestId));
                            }
                            return;
                        }

                        stopResendTimer();
                        if (otpDialog != null && otpDialog.isShowing()) {
                            otpDialog.dismiss();
                        }
                        signInAndStartEkyc();
                    });
        } catch (Exception e) {
            if (otpDialog != null) {
                otpDialog.setBusy(false);
                otpDialog.setStatusMessage("Không thể xác nhận đăng ký.");
            }
        }
    }

    private void signInAndStartEkyc() {
        if (binding == null || authRepository == null) return;
        setBusy(true);
        authRepository.signIn(pendingEmail, pendingPassword)
                .addOnCompleteListener(task -> {
                    if (!isAdded() || binding == null) return;
                    setBusy(false);
                    if (!task.isSuccessful()) {
                        Toast.makeText(requireContext(),
                                "Đã tạo tài khoản. Vui lòng đăng nhập để hoàn tất eKYC.",
                                Toast.LENGTH_LONG).show();
                        returnToLogin();
                        return;
                    }
                    binding.registerStepBadge.setText("Bước 2/2");
                    binding.registerFormCard.setVisibility(View.GONE);
                    binding.registerEkycCard.setVisibility(View.VISIBLE);
                    openEkycDialog();
                });
    }

    private void openEkycDialog() {
        if (!isAdded()) return;
        if (getChildFragmentManager().findFragmentByTag(EKYC_TAG) == null) {
            new EkycDialogFragment().show(getChildFragmentManager(), EKYC_TAG);
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

    private void setBusy(boolean value) {
        busy = value;
        if (binding == null) return;
        binding.registerProgress.setVisibility(value ? View.VISIBLE : View.GONE);
        binding.registerSubmitButton.setEnabled(!value);
        binding.registerBackButton.setEnabled(!value);
    }

    private void showError(String message) {
        if (binding == null) return;
        binding.registerErrorText.setText(message);
        binding.registerErrorText.setVisibility(View.VISIBLE);
    }

    private void clearError() {
        if (binding == null) return;
        binding.registerErrorText.setText("");
        binding.registerErrorText.setVisibility(View.GONE);
    }

    private void returnToLogin() {
        if (!isAdded()) return;
        NavHostFragment.findNavController(this).popBackStack(R.id.login, false);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopResendTimer();
        if (otpDialog != null && otpDialog.isShowing()) {
            otpDialog.dismiss();
        }
        otpDialog = null;
        binding = null;
    }

    private static String textOf(com.google.android.material.textfield.TextInputEditText input) {
        return input.getText() == null ? "" : input.getText().toString().trim();
    }

    private static String rawTextOf(com.google.android.material.textfield.TextInputEditText input) {
        return input.getText() == null ? "" : input.getText().toString();
    }

    @Nullable
    private static JSONObject parseJson(@Nullable String body) {
        if (TextUtils.isEmpty(body)) return null;
        try {
            return new JSONObject(body);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String mapRegisterError(String code, int statusCode, String requestId) {
        String normalized = code == null ? "" : code.toUpperCase(Locale.ROOT);
        switch (normalized) {
            case "EMAIL_ALREADY_EXISTS":
                return "Email này đã được đăng ký. Vui lòng dùng email khác hoặc đăng nhập.";
            case "PHONE_ALREADY_EXISTS":
                return "Số điện thoại này đã được sử dụng cho tài khoản khác.";
            case "NATIONAL_ID_EXISTS":
                return "Số CCCD này đã tồn tại trên hệ thống.";
            case "INVALID_REQUEST":
                return "Thông tin đăng ký chưa hợp lệ. Vui lòng kiểm tra lại họ tên, email, SĐT, CCCD và mật khẩu.";
            case "REGISTRATION_IDENTITY_FAILED":
                return "Không thể tạo tài khoản xác thực (email hoặc mật khẩu không hợp lệ trên Firebase).";
            case "CHALLENGE_NOT_FOUND":
                return "Phiên OTP không tồn tại. Vui lòng gửi lại yêu cầu đăng ký.";
            case "INVALID_OTP":
                return "Mã OTP không đúng. Vui lòng kiểm tra lại.";
            case "OTP_EXPIRED":
                return "Mã OTP đã hết hạn. Vui lòng gửi lại mã mới.";
            case "OTP_LOCKED":
                return "Bạn đã nhập sai OTP quá 5 lần. Vui lòng gửi lại yêu cầu mới.";
            case "OTP_USED":
                return "Mã OTP này đã được sử dụng.";
            case "RATE_LIMITED":
                return "Bạn thao tác quá nhanh. Vui lòng đợi vài phút rồi thử lại.";
            case "REGISTRATION_UNAVAILABLE":
                return "Chức năng đăng ký hiện chưa khả dụng trên máy chủ.";
            case "INTERNAL_ERROR":
                return "Lỗi máy chủ nội bộ (INTERNAL_ERROR"
                        + (TextUtils.isEmpty(requestId) ? "" : ", ref: " + requestId) + ").";
            default:
                if (statusCode == 404 && normalized.isEmpty()) {
                    return "Máy chủ chưa cập nhật API đăng ký (HTTP 404). Vui lòng rebuild/restart backend.";
                }
                if (statusCode == 429) return "Quá nhiều yêu cầu. Vui lòng thử lại sau.";
                return "Không thể hoàn tất đăng ký (HTTP " + statusCode
                        + (normalized.isEmpty() ? "" : ", mã: " + normalized) + ").";
        }
    }
}

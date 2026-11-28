package com.example.final_term.ui.auth;

import android.os.Bundle;
import android.util.Patterns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import com.example.final_term.R;
import com.example.final_term.auth.AuthRepository;
import com.example.final_term.auth.AuthState;
import com.example.final_term.core.FinalTermApplication;
import com.example.final_term.data.BackendApiClient;
import com.example.final_term.databinding.FragmentRegisterBinding;
import com.example.final_term.ui.ekyc.EkycDialogFragment;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;

import org.json.JSONException;
import org.json.JSONObject;

public final class RegisterFragment extends Fragment {
    private static final String EKYC_TAG = "register-ekyc-dialog";
    private FragmentRegisterBinding binding;
    private AuthRepository auth;
    private BackendApiClient api;
    private boolean emailVerified;
    private EmailOtpSheet otpSheet;
    private boolean busy;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle state) {
        binding = FragmentRegisterBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle state) {
        var container = ((FinalTermApplication) requireActivity().getApplication()).getAppContainer();
        auth = container.getAuthRepository();
        api = container.getBackendApiClient();
        binding.registerBackButton.setOnClickListener(v -> returnToLogin());
        binding.registerBackToLoginButton.setOnClickListener(v -> returnToLogin());
        binding.registerSignOutButton.setOnClickListener(v -> returnToLogin());
        binding.registerOpenEkycButton.setOnClickListener(v -> openEkycDialog());
        binding.registerSubmitButton.setOnClickListener(v -> {
            if (auth.currentEmail() == null) createIdentity();
            else if (!emailVerified) sendEmail();
            else completeRegistration();
        });
        auth.observeAuthState().observe(getViewLifecycleOwner(), ignored -> render());
        render();
        if (auth.currentEmail() != null && !isOnboarding()) checkEmail(false);
    }

    private boolean isOnboarding() {
        AuthState state = auth.observeAuthState().getValue();
        return state != null && state.getUser() != null && state.getUser().isOnboarding();
    }

    private void render() {
        if (binding == null) return;
        String email = auth.currentEmail();
        boolean hasIdentity = email != null;
        boolean onboarding = isOnboarding();
        binding.registerFormCard.setVisibility(onboarding ? View.GONE : View.VISIBLE);
        binding.registerEkycCard.setVisibility(onboarding ? View.VISIBLE : View.GONE);
        binding.registerFullNameField.setVisibility(emailVerified ? View.VISIBLE : View.GONE);
        binding.registerPhoneField.setVisibility(emailVerified ? View.VISIBLE : View.GONE);
        binding.registerNationalIdField.setVisibility(emailVerified ? View.VISIBLE : View.GONE);
        binding.registerPasswordField.setVisibility(hasIdentity ? View.GONE : View.VISIBLE);
        binding.registerConfirmPasswordField.setVisibility(hasIdentity ? View.GONE : View.VISIBLE);
        binding.registerEmailField.setEnabled(!hasIdentity);
        if (hasIdentity) binding.registerEmailInput.setText(email);

        binding.registerStepBadge.setText(onboarding ? "Bước 3/3" : emailVerified ? "Bước 2/3" : "Bước 1/3");
        binding.registerFormTitle.setText(emailVerified ? "Thông tin mở tài khoản"
                : hasIdentity ? "Xác minh email" : "Tạo thông tin đăng nhập");
        binding.registerFormHint.setText(emailVerified ? "Nhập thông tin để tạo hồ sơ và tiếp tục eKYC."
                : hasIdentity ? "Nhận mã qua email và nhập mã tại đây để tiếp tục."
                : "Nhập email và mật khẩu để nhận mã xác minh.");
        binding.registerSubmitButton.setText(emailVerified ? "Tiếp tục eKYC"
                : hasIdentity ? "Nhập mã OTP" : "Tạo tài khoản");
        binding.registerSubmitButton.setEnabled(!busy);
        binding.registerProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    private void createIdentity() {
        var form = binding;
        String email = text(form.registerEmailInput).trim();
        String password = text(form.registerPasswordInput);
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            form.registerEmailInput.setError("Vui lòng nhập email hợp lệ.");
            return;
        }
        if (password.length() < 8 || password.length() > 128) {
            form.registerPasswordInput.setError("Mật khẩu phải từ 8 đến 128 ký tự.");
            return;
        }
        if (!password.equals(text(form.registerConfirmPasswordInput))) {
            form.registerConfirmPasswordInput.setError("Mật khẩu nhập lại chưa khớp.");
            return;
        }
        startRequest();
        auth.createRegistrationIdentity(email, password).addOnCompleteListener(task -> {
            if (binding != form) return;
            if (task.isSuccessful()) {
                form.registerPasswordInput.setText("");
                form.registerConfirmPasswordInput.setText("");
                sendEmail();
            } else {
                finishRequest(task.getException() instanceof FirebaseAuthUserCollisionException
                        ? "Email đã có tài khoản. Hãy đăng nhập để tiếp tục."
                        : "Chưa tạo được tài khoản. Vui lòng kiểm tra thông tin và thử lại.");
            }
        });
    }

    private void sendEmail() {
        if (otpSheet != null && !otpSheet.isShowing()) {
            otpSheet.reopen();
            return;
        }
        var form = binding;
        startRequest();
        if (otpSheet != null) otpSheet.setBusy(true);
        api.post("/auth/register/email-otp", "{}").addOnCompleteListener(task -> {
            if (binding != form) return;
            if (!task.isSuccessful()) {
                emailFailure("Chưa thể kết nối máy chủ. Vui lòng thử lại.");
                return;
            }
            var response = task.getResult();
            try {
                JSONObject data = new JSONObject(response.body);
                if (response.statusCode != 200) {
                    emailFailure(EmailOtpSheet.responseError(response.body));
                    return;
                }
                if (data.optBoolean("verified")) {
                    if (otpSheet != null) otpSheet.dismiss();
                    otpSheet = null;
                    checkEmail(true);
                    return;
                }
                if (otpSheet == null) {
                    otpSheet = new EmailOtpSheet(requireContext(),
                            "Nhập mã 6 số đã gửi đến " + auth.currentEmail() + ".", this::verifyOtp, this::sendEmail);
                }
                otpSheet.show(data.getString("challengeId"), data.getInt("resendAfterSeconds"));
                finishRequest(null);
            } catch (JSONException invalid) {
                emailFailure("Không đọc được phản hồi gửi mã. Vui lòng thử lại.");
            }
        });
    }

    private void verifyOtp(String code) {
        var form = binding;
        startRequest();
        otpSheet.setBusy(true);
        try {
            String body = new JSONObject().put("challengeId", otpSheet.challengeId()).put("code", code).toString();
            api.post("/auth/register/email-otp/verify", body).addOnCompleteListener(task -> {
                if (binding != form) return;
                if (!task.isSuccessful()) {
                    emailFailure("Chưa thể kết nối máy chủ. Vui lòng thử lại.");
                } else if (task.getResult().statusCode != 200) {
                    emailFailure(EmailOtpSheet.responseError(task.getResult().body));
                } else {
                    otpSheet.dismiss();
                    otpSheet = null;
                    checkEmail(true);
                }
            });
        } catch (JSONException invalid) {
            emailFailure("Không đọc được mã. Vui lòng nhập lại.");
        }
    }

    private void emailFailure(String message) {
        if (otpSheet != null && otpSheet.isShowing()) {
            otpSheet.fail(message);
            finishRequest(null);
        } else {
            finishRequest(message);
        }
    }

    private void checkEmail(boolean explicit) {
        var form = binding;
        startRequest();
        auth.refreshRegistrationEmail().addOnCompleteListener(task -> {
            if (binding != form) return;
            emailVerified = task.isSuccessful() && Boolean.TRUE.equals(task.getResult());
            String error = !task.isSuccessful() ? "Chưa kiểm tra được email. Vui lòng thử lại."
                    : explicit && !emailVerified ? "Email chưa được xác minh. Hãy nhập mã gửi qua email." : null;
            if (!emailVerified) {
                finishRequest(error);
                return;
            }
            auth.refreshRegisteredProfile().addOnCompleteListener(profile -> {
                if (binding != form) return;
                finishRequest(profile.isSuccessful() ? null : "Chưa tải được hồ sơ. Vui lòng thử lại.");
            });
        });
    }

    private void completeRegistration() {
        var form = binding;
        String name = text(form.registerFullNameInput).trim();
        String phone = text(form.registerPhoneInput).trim();
        String nationalId = text(form.registerNationalIdInput).trim();
        if (name.length() < 2 || name.length() > 120) {
            form.registerFullNameInput.setError("Vui lòng nhập họ và tên hợp lệ.");
            return;
        }
        if (!phone.matches("^(?:0[0-9]{9}|\\+84[0-9]{9})$")) {
            form.registerPhoneInput.setError("Vui lòng nhập số điện thoại Việt Nam hợp lệ.");
            return;
        }
        if (!nationalId.matches("^\\d{12}$")) {
            form.registerNationalIdInput.setError("Số CCCD phải gồm 12 chữ số.");
            return;
        }
        startRequest();
        try {
            String body = new JSONObject().put("fullName", name).put("phoneNumber", phone)
                    .put("nationalIdNumber", nationalId).toString();
            api.post("/auth/register/complete", body).addOnCompleteListener(task -> {
                if (binding != form) return;
                if (!task.isSuccessful() || task.getResult().statusCode != 200) {
                    finishRequest(task.isSuccessful() ? registrationError(task.getResult().body)
                            : "Chưa thể kết nối máy chủ. Vui lòng thử lại.");
                    return;
                }
                auth.refreshRegisteredProfile().addOnCompleteListener(profile -> {
                    if (binding != form) return;
                    boolean ready = profile.isSuccessful() && profile.getResult() != null;
                    finishRequest(ready ? null : "Chưa tải được hồ sơ. Vui lòng thử lại.");
                    if (ready && profile.getResult().isOnboarding()) openEkycDialog();
                });
            });
        } catch (JSONException invalid) {
            finishRequest("Thông tin đăng ký không hợp lệ.");
        }
    }

    private String registrationError(String body) {
        try {
            switch (new JSONObject(body).optString("error")) {
                case "CONTACT_PHONE_EXISTS": return "Số điện thoại này đã được sử dụng.";
                case "NATIONAL_ID_EXISTS": return "Số CCCD này đã được sử dụng.";
                case "EMAIL_NOT_VERIFIED": return "Vui lòng xác minh email và thử lại.";
                default: return "Chưa hoàn tất được đăng ký. Vui lòng thử lại.";
            }
        } catch (JSONException invalid) {
            return "Chưa hoàn tất được đăng ký. Vui lòng thử lại.";
        }
    }

    private void openEkycDialog() {
        if (!isAdded()) return;
        if (getChildFragmentManager().findFragmentByTag(EKYC_TAG) == null) {
            new EkycDialogFragment().show(getChildFragmentManager(), EKYC_TAG);
        }
    }

    private void startRequest() {
        busy = true;
        binding.registerErrorText.setVisibility(View.GONE);
        render();
    }

    private void finishRequest(String error) {
        busy = false;
        binding.registerErrorText.setText(error);
        binding.registerErrorText.setVisibility(error == null ? View.GONE : View.VISIBLE);
        render();
    }

    private void returnToLogin() {
        if (auth.currentEmail() != null) auth.signOut();
        var nav = NavHostFragment.findNavController(this);
        if (!nav.popBackStack(R.id.login, false)) {
            nav.navigate(R.id.login);
        }
    }

    @Override
    public void onDestroyView() {
        if (otpSheet != null) otpSheet.dismiss();
        otpSheet = null;
        binding = null;
        busy = false;
        super.onDestroyView();
    }

    private static String text(android.widget.EditText input) {
        return input.getText() == null ? "" : input.getText().toString();
    }
}

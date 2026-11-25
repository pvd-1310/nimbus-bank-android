package com.example.final_term.ui.officer;

import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.example.final_term.R;
import com.example.final_term.auth.AuthState;
import com.example.final_term.auth.UserRole;
import com.example.final_term.core.FinalTermApplication;
import com.example.final_term.databinding.FragmentOfficerCustomerFormBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

import java.util.UUID;

public final class OfficerCustomerFormFragment extends Fragment {
    private FragmentOfficerCustomerFormBinding binding;
    private FinalTermApplication app;
    private boolean editing;
    private String customerId = "";
    private String accountNumber = "";
    private String pendingRequestId;
    private String pendingSnapshot;
    private JSONObject original = new JSONObject();
    private boolean submitting;
    private boolean started;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentOfficerCustomerFormBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        app = (FinalTermApplication) requireActivity().getApplication();
        Bundle args = getArguments();
        editing = args != null && "edit".equals(args.getString("mode", "create"));
        customerId = args == null ? "" : args.getString("customerId", "");
        accountNumber = args == null ? "" : args.getString("accountNumber", "");
        binding.backButton.setOnClickListener(v -> Navigation.findNavController(v).navigateUp());
        binding.formTitle.setText(editing ? "Điều chỉnh thông tin" : "Cấp mới tài khoản");
        binding.formIntro.setText(editing
                ? "Thông tin chủ tài khoản thanh toán"
                : "Nhập thông tin khách hàng để tạo hồ sơ và tài khoản thanh toán.");
        binding.contactSectionTitle.setText(editing ? "Thông tin chủ tài khoản" : "Thông tin khách hàng");
        binding.emailInput.setHint(editing ? "Email liên hệ" : "Email đăng nhập");
        binding.phoneInput.setHint(editing ? "Số điện thoại liên hệ, dạng +84..." : "Số điện thoại, 10 chữ số bắt đầu bằng 0");
        binding.phoneInput.setInputType(InputType.TYPE_CLASS_PHONE);
        binding.nationalIdInput.setVisibility(editing ? View.GONE : View.VISIBLE);
        binding.customerNoInput.setVisibility(editing ? View.GONE : View.VISIBLE);
        binding.reasonInput.setVisibility(editing ? View.VISIBLE : View.GONE);
        binding.reasonSectionTitle.setVisibility(editing ? View.VISIBLE : View.GONE);
        binding.auditNote.setVisibility(editing ? View.VISIBLE : View.GONE);
        if (!accountNumber.isEmpty()) {
            binding.accountContextCard.setVisibility(View.VISIBLE);
            binding.accountNumberText.setText(accountNumber);
        }
        binding.submitButton.setText(editing ? "Lưu điều chỉnh" : "Cấp mới hồ sơ");
        binding.submitButton.setOnClickListener(v -> submit());
        binding.retryButton.setOnClickListener(v -> loadCustomer());
        binding.submitButton.setEnabled(!editing);
        if (editing) binding.formContent.setVisibility(View.GONE);
        app.getAppContainer().getAuthRepository().observeAuthState().observe(getViewLifecycleOwner(), auth -> {
            if (auth == null || auth.getStatus() != AuthState.Status.SIGNED_IN || auth.getUser() == null
                    || auth.getUser().getRole() != UserRole.OFFICER) {
                requireActivity().getOnBackPressedDispatcher().onBackPressed();
                return;
            }
            if (started) return;
            started = true;
            if (editing && customerId.isEmpty()) {
                showLoadError("Không tìm thấy mã hồ sơ cần điều chỉnh.");
            } else if (editing) {
                loadCustomer();
            } else {
                binding.loadStatePanel.setVisibility(View.GONE);
                binding.formContent.setVisibility(View.VISIBLE);
            }
        });
    }

    private void loadCustomer() {
        if (binding == null) return;
        binding.formContent.setVisibility(View.GONE);
        binding.submitButton.setEnabled(false);
        binding.loadStatePanel.setVisibility(View.VISIBLE);
        binding.loadProgress.setVisibility(View.VISIBLE);
        binding.retryButton.setVisibility(View.GONE);
        binding.statusText.setText("Đang tải thông tin khách hàng...");
        String path = "/officer/customers/" + android.net.Uri.encode(customerId);
        app.getAppContainer().getBackendApiClient().get(path).addOnCompleteListener(task -> {
            if (binding == null) return;
            try {
                if (!task.isSuccessful() || task.getResult() == null) {
                    showLoadError("Không kết nối được máy chủ. Kiểm tra mạng rồi thử tải lại.");
                    return;
                }
                if (task.getResult().statusCode != 200) {
                    int status = task.getResult().statusCode;
                    String errorCode = "";
                    try { errorCode = new JSONObject(task.getResult().body).optString("error", ""); }
                    catch (Exception ignored) { }
                    String message = status == 403
                            ? "Tài khoản Officer không có quyền điều chỉnh hồ sơ này."
                            : ("CUSTOMER_PROFILE_NOT_FOUND".equals(errorCode) || status == 404)
                                ? "Hồ sơ này chưa được liên kết đầy đủ với tài khoản thanh toán."
                                : "Không tải được hồ sơ khách hàng. Chạm thử tải lại để tiếp tục.";
                    showLoadError(message);
                    return;
                }
                original = new JSONObject(task.getResult().body);
                binding.nameInput.setText(original.optString("fullName", ""));
                binding.emailInput.setText(original.optString("contactEmail", ""));
                binding.phoneInput.setText(original.optString("contactPhone", ""));
                binding.addressInput.setText(original.optString("address", ""));
                binding.loadStatePanel.setVisibility(View.GONE);
                binding.formContent.setVisibility(View.VISIBLE);
                binding.submitButton.setEnabled(true);
            } catch (Exception error) {
                showLoadError("Dữ liệu hồ sơ không đúng định dạng. Thử tải lại hoặc quay lại danh sách.");
            }
        });
    }

    private void showLoadError(String message) {
        if (binding == null) return;
        binding.formContent.setVisibility(View.GONE);
        binding.submitButton.setEnabled(false);
        binding.loadStatePanel.setVisibility(View.VISIBLE);
        binding.loadProgress.setVisibility(View.GONE);
        binding.retryButton.setVisibility(View.VISIBLE);
        binding.statusText.setText(message);
    }

    private void submit() {
        if (submitting || binding == null) return;
        String name = binding.nameInput.getText().toString().trim();
        String email = binding.emailInput.getText().toString().trim();
        String phone = binding.phoneInput.getText().toString().trim();
        String address = binding.addressInput.getText().toString().trim();
        String nationalId = binding.nationalIdInput.getText().toString().trim();
        String customerNo = binding.customerNoInput.getText().toString().trim();
        String reason = binding.reasonInput.getText().toString().trim();

        if (name.length() < 2) { binding.nameInput.setError("Nhập họ tên đầy đủ"); return; }
        if (editing) submitEdit(name, email, phone, address, reason);
        else submitCreate(name, email, phone, address, nationalId, customerNo);
    }

    private void submitCreate(String name, String email, String phone, String address, String nationalId, String customerNo) {
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) { binding.emailInput.setError("Email không hợp lệ"); return; }
        if (!phone.matches("0[0-9]{9}")) { binding.phoneInput.setError("Nhập 10 chữ số, bắt đầu bằng 0"); return; }
        if (!nationalId.matches("[0-9]{12}")) { binding.nationalIdInput.setError("CCCD cần đúng 12 chữ số"); return; }
        try {
            JSONObject body = new JSONObject().put("fullName", name).put("email", email)
                    .put("phoneNumber", phone).put("nationalIdNumber", nationalId);
            if (!address.isEmpty()) body.put("address", address);
            if (!customerNo.isEmpty()) body.put("customerNo", customerNo);
            String snapshot = body.toString();
            if (pendingRequestId == null || !snapshot.equals(pendingSnapshot)) {
                pendingRequestId = UUID.randomUUID().toString();
                pendingSnapshot = snapshot;
            }
            body.put("requestId", pendingRequestId);
            submitting = true;
            binding.submitButton.setEnabled(false);
            app.getAppContainer().getBackendApiClient().post("/officer/customers", body.toString())
                    .addOnSuccessListener(response -> {
                        submitting = false;
                        if (binding == null) return;
                        binding.submitButton.setEnabled(true);
                        if (response.statusCode >= 200 && response.statusCode < 300) {
                            sendPasswordSetup(email, response.body);
                        } else {
                            String code = "";
                            try { code = new JSONObject(response.body).optString("error", ""); } catch (Exception ignored) { }
                            String message = "Không thể tạo hồ sơ. Kiểm tra thông tin rồi thử lại.";
                            if ("CUSTOMER_NUMBER_EXISTS".equals(code)) message = "Mã khách hàng đã tồn tại.";
                            else if ("CONTACT_PHONE_EXISTS".equals(code)) message = "Số điện thoại liên hệ đã được sử dụng.";
                            else if ("PROVISION_IDENTITY_FAILED".equals(code)) message = "Email đăng nhập hoặc số điện thoại đã được sử dụng.";
                            showError(message);
                        }
                    }).addOnFailureListener(error -> {
                        submitting = false;
                        if (binding != null) { binding.submitButton.setEnabled(true); showError("Không thể kết nối. Kiểm tra mạng rồi thử lại."); }
                    });
        } catch (Exception error) { showError("Không thể gửi thông tin khách hàng."); }
    }

    private void submitEdit(String name, String email, String phone, String address, String reason) {
        if (reason.length() < 3) { binding.reasonInput.setError("Nêu lý do điều chỉnh (ít nhất 3 ký tự)"); return; }
        if (!email.isEmpty() && !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) { binding.emailInput.setError("Email không hợp lệ"); return; }
        boolean phoneChanged = !phone.equals(original.optString("contactPhone", ""));
        if (phoneChanged && !phone.isEmpty() && !phone.matches("\\+[1-9][0-9]{7,14}")) { binding.phoneInput.setError("Dùng định dạng quốc tế, ví dụ +84901234567"); return; }
        try {
            JSONObject body = new JSONObject().put("reason", reason);
            if (!name.equals(original.optString("fullName", ""))) body.put("fullName", name);
            if (!email.equals(original.optString("contactEmail", "")) && !email.isEmpty()) body.put("contactEmail", email);
            if (!phone.equals(original.optString("contactPhone", "")) && !phone.isEmpty()) body.put("contactPhone", phone);
            if (!address.equals(original.optString("address", "")) && !address.isEmpty()) body.put("address", address);
            if (body.length() == 1) { showError("Không có thông tin nào thay đổi."); return; }
            submitting = true;
            binding.submitButton.setEnabled(false);
            String path = "/officer/customers/" + android.net.Uri.encode(customerId);
            app.getAppContainer().getBackendApiClient().patch(path, body.toString())
                    .addOnSuccessListener(response -> {
                        submitting = false;
                        if (binding == null) return;
                        binding.submitButton.setEnabled(true);
                        if (response.statusCode >= 200 && response.statusCode < 300) {
                            Toast.makeText(requireContext(), "Đã cập nhật hồ sơ khách hàng.", Toast.LENGTH_SHORT).show();
                            Navigation.findNavController(binding.getRoot()).navigateUp();
                        } else showError("Không thể cập nhật hồ sơ. Kiểm tra thông tin rồi thử lại.");
                    }).addOnFailureListener(error -> {
                        submitting = false;
                        if (binding != null) { binding.submitButton.setEnabled(true); showError("Không thể kết nối. Kiểm tra mạng rồi thử lại."); }
                    });
        } catch (Exception error) { showError("Không thể gửi thay đổi."); }
    }

    private void sendPasswordSetup(String email, String responseBody) {
        String accountId = "";
        try { accountId = new JSONObject(responseBody).optString("accountId", ""); } catch (Exception ignored) { }
        String account = accountId;
        app.getAppContainer().getAuthRepository().sendPasswordResetEmail(email).addOnCompleteListener(reset -> {
            if (binding == null) return;
            String message = "Đã tạo hồ sơ ONBOARDING và tài khoản thanh toán " + account
                    + ". Khách hàng cần đặt mật khẩu qua email trước khi đăng nhập.";
            if (!reset.isSuccessful()) message += "\n\nEmail chưa gửi được; khách hàng có thể dùng mục Đặt/quên mật khẩu ở màn đăng nhập.";
            new MaterialAlertDialogBuilder(requireContext()).setTitle("Đã tiếp nhận khách hàng")
                    .setMessage(message).setPositiveButton("Đóng", (dialog, which) -> Navigation.findNavController(binding.getRoot()).navigateUp()).show();
        });
    }

    private void showError(String message) {
        if (binding != null) Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
    }

    @Override public void onDestroyView() { binding = null; super.onDestroyView(); }
}

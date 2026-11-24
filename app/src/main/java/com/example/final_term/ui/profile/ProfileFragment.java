package com.example.final_term.ui.profile;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.example.final_term.R;
import com.example.final_term.auth.AuthState;
import com.example.final_term.core.FinalTermApplication;
import com.example.final_term.data.BackendApiClient;
import com.example.final_term.databinding.FragmentProfileBinding;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;

public final class ProfileFragment extends Fragment {
    private FragmentProfileBinding binding;
    private BackendApiClient apiClient;
    private boolean profileLoaded;
    private JSONObject currentProfile = new JSONObject();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle state) {
        binding = FragmentProfileBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        FinalTermApplication app = (FinalTermApplication) requireActivity().getApplication();
        apiClient = app.getAppContainer().getBackendApiClient();

        View.OnClickListener openEkycListener = v -> {
            com.example.final_term.ui.ekyc.EkycDialogFragment dialog = new com.example.final_term.ui.ekyc.EkycDialogFragment();
            dialog.show(getParentFragmentManager(), "ekyc_dialog");
        };

        binding.itemPersonalInfo.setOnClickListener(openEkycListener);
        binding.ekycBadge.setOnClickListener(openEkycListener);

        app.getAppContainer().getAuthRepository().observeAuthState()
                .observe(getViewLifecycleOwner(), authState -> {
                    render(authState);
                    if (authState != null && authState.getUser() != null) {
                        boolean customer = authState.getUser().getRole()
                                == com.example.final_term.auth.UserRole.CUSTOMER;
                        binding.editContactButton.setVisibility(customer ? View.VISIBLE : View.GONE);
                        binding.ekycBadge.setVisibility(customer ? View.VISIBLE : View.GONE);
                        binding.itemRevokeFace.setVisibility(customer ? View.VISIBLE : View.GONE);
                        if (customer) fetchEkycStatus();
                        binding.itemPersonalInfo.setOnClickListener(customer ? openEkycListener : null);
                        binding.ekycBadge.setOnClickListener(customer ? openEkycListener : null);
                        loadProfile();
                    }
                });

        getParentFragmentManager().setFragmentResultListener("ekyc_status_changed", getViewLifecycleOwner(),
                (requestKey, result) -> fetchEkycStatus());

        binding.logoutButton.setOnClickListener(v -> app.getAppContainer().getAuthRepository().signOut());
        binding.editContactButton.setOnClickListener(v -> {
            if (profileLoaded) showContactEditor();
            else loadProfile();
        });

        binding.qrButton.setOnClickListener(v -> new AlertDialog.Builder(requireContext())
                .setTitle("Mã QR nhận tiền")
                .setMessage("Mã QR cá nhân liên kết tài khoản thanh toán chính của bạn để nhận chuyển khoản nhanh.")
                .setPositiveButton("Đóng", null)
                .show());



        binding.itemCameraSecurity.setOnClickListener(v -> showCameraAndBiometricDialog());
        binding.itemRevokeFace.setOnClickListener(v -> confirmRevokeFaceTemplate());

        binding.itemCards.setOnClickListener(v -> new AlertDialog.Builder(requireContext())
                .setTitle("Hạn mức & Bảo mật")
                .setMessage("Hạn mức được kiểm tra theo tài khoản. Mã xác nhận hiển thị trên màn hình này. Giao dịch vượt ngưỡng có thể cần xác minh khuôn mặt.")
                .setPositiveButton("Đóng", null)
                .show());

        binding.itemLanguage.setOnClickListener(v -> Toast.makeText(requireContext(), "Ngôn ngữ hiện tại: Tiếng Việt", Toast.LENGTH_SHORT).show());

        binding.itemAi.setOnClickListener(v ->
                new com.example.final_term.ui.customer.ChatbotDialogFragment().show(getChildFragmentManager(), "Chatbot"));

        fetchCameraAndCapabilitiesStatus();
    }

    private void fetchCameraAndCapabilitiesStatus() {
        if (!isAdded() || binding == null) return;
        boolean cameraPermission = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;

        if (apiClient == null) {
            binding.cameraStatusText.setText("Camera: " + (cameraPermission ? "Đã cấp quyền" : "Chưa cấp quyền"));
            return;
        }

        apiClient.get("/capabilities").addOnCompleteListener(task -> {
            if (!isAdded() || binding == null) return;
            String faceCap = "Chưa khả dụng";
            if (task.isSuccessful() && task.getResult() != null && task.getResult().statusCode == 200) {
                try {
                    JSONObject cap = new JSONObject(task.getResult().body);
                    faceCap = "ENABLED".equals(cap.optString("face")) ? "Có thể sử dụng" : "Chưa khả dụng";
                } catch (Exception ignored) { }
            }
            binding.cameraStatusText.setText("Camera: " + (cameraPermission ? "Đã cấp quyền" : "Chưa cấp quyền") +
                    " · Khuôn mặt: " + faceCap);
        });
    }

    private void showCameraAndBiometricDialog() {
        boolean cameraPermission = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;

        new AlertDialog.Builder(requireContext())
                .setTitle("Trạng thái Camera & Sinh trắc học")
                .setMessage("• Quyền truy cập Camera: " + (cameraPermission ? "Đã cấp quyền" : "Chưa cấp quyền (Cần cho eKYC & Face)") +
                        "\n• Phương thức khuôn mặt: Ảnh selfie dùng để thử đăng ký và đăng nhập; chưa xác minh giấy tờ hoặc danh tính pháp lý." +
                        "\n• Mã xác nhận giao dịch hiển thị trực tiếp trên màn hình." +
                        "\n\nLưu ý: Phương thức đăng nhập bằng Email và Mật khẩu luôn là phương thức dự phòng chính thức và an toàn.")
                .setPositiveButton("Đóng", null)
                .show();
    }

    private void confirmRevokeFaceTemplate() {
        new AlertDialog.Builder(requireContext())
                .setTitle("Thu hồi dữ liệu mẫu khuôn mặt")
                .setMessage("Thao tác này sẽ tắt đăng nhập khuôn mặt, đặt eKYC về trạng thái cần đăng ký lại và đưa ảnh eKYC đã tải lên vào hàng chờ xóa. Tài khoản của bạn vẫn hoạt động; bạn cần hoàn tất eKYC mới để bật lại đăng nhập khuôn mặt. Tiếp tục thu hồi?")
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Thu hồi ngay", (d, which) -> {
                    if (apiClient == null) return;
                    Toast.makeText(requireContext(), "Đang gửi yêu cầu thu hồi…", Toast.LENGTH_SHORT).show();
                    apiClient.post("/me/face/revoke", "{}").addOnCompleteListener(task -> {
                        if (!isAdded() || binding == null) return;
                        if (task.isSuccessful() && task.getResult() != null && task.getResult().statusCode == 200) {
                            String message = "Đã gửi yêu cầu thu hồi.";
                            try {
                                JSONObject result = new JSONObject(task.getResult().body);
                                if ("RESUBMIT_REQUIRED".equals(result.optString("ekycStatus"))) {
                                    message = "Đã thu hồi mẫu khuôn mặt và đặt lại eKYC.";
                                    JSONObject cleanup = result.optJSONObject("evidenceCleanup");
                                    if (cleanup != null && "PENDING".equals(cleanup.optString("status"))) {
                                        message += " Ảnh đã được đưa vào hàng chờ xóa.";
                                    } else if (cleanup != null && "NOT_REQUIRED".equals(cleanup.optString("status"))) {
                                        message += " Không có ảnh eKYC nào cần xóa.";
                                    }
                                } else {
                                    message = "Chưa thể cập nhật trạng thái định danh. Vui lòng thử lại sau.";
                                }
                            } catch (Exception ignored) { }
                            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
                            fetchEkycStatus();
                            fetchCameraAndCapabilitiesStatus();
                        } else {
                            String message = task.isSuccessful()
                                    ? "Không thể xóa dữ liệu khuôn mặt. Vui lòng thử lại sau."
                                    : "Chưa thể kết nối để xóa dữ liệu khuôn mặt. Vui lòng thử lại.";
                            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
                        }
                    });
                })
                .show();
    }

    private void loadProfile() {
        if (apiClient == null || binding == null) return;
        var current = binding;
        profileLoaded = false;
        current.editContactButton.setEnabled(false);
        current.editContactButton.setText("Đang tải hồ sơ…");
        apiClient.get("/me").addOnCompleteListener(task -> {
            if (binding != current) return;
            try {
                if (!task.isSuccessful() || task.getResult().statusCode != 200)
                    throw new org.json.JSONException("Unavailable");
                currentProfile = new JSONObject(task.getResult().body);
                profileLoaded = true;
            } catch (org.json.JSONException ignored) {
                currentProfile = new JSONObject();
            }
            current.editContactButton.setEnabled(true);
            current.editContactButton.setText(profileLoaded ? "Cập nhật thông tin liên hệ" : "Không tải được hồ sơ · Thử lại");
        });
    }

    private void showContactEditor() {
        LinearLayout form = new LinearLayout(requireContext());
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (18 * getResources().getDisplayMetrics().density);
        form.setPadding(pad, pad, pad, 0);

        EditText contactEmail = new EditText(requireContext());
        contactEmail.setSingleLine(true);
        contactEmail.setHint("Email liên hệ");
        contactEmail.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        contactEmail.setText(currentProfile.optString("contactEmail", ""));
        form.addView(contactEmail);

        EditText contactPhone = new EditText(requireContext());
        contactPhone.setSingleLine(true);
        contactPhone.setHint("Số điện thoại liên hệ (+84...)");
        contactPhone.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        contactPhone.setText(currentProfile.optString("contactPhone", ""));
        form.addView(contactPhone);

        JSONObject preferences = currentProfile.optJSONObject("preferences");
        CheckBox notifications = new CheckBox(requireContext());
        notifications.setText("Nhận thông báo trong ứng dụng");
        notifications.setChecked(preferences == null || preferences.optBoolean("notificationsEnabled", true));
        form.addView(notifications);

        Spinner language = new Spinner(requireContext());
        ArrayAdapter<String> languages = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, Arrays.asList("vi", "en"));
        languages.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        language.setAdapter(languages);
        language.setSelection("en".equals(preferences == null ? "vi" : preferences.optString("language", "vi")) ? 1 : 0);
        form.addView(language);

        form.setFocusableInTouchMode(true);
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle("Cập nhật thông tin liên hệ")
                .setView(form)
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Lưu", null)
                .create();
        dialog.setCanceledOnTouchOutside(false);

        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                View currentFocus = dialog.getCurrentFocus();
                if (currentFocus != null) {
                    InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.hideSoftInputFromWindow(currentFocus.getWindowToken(), 0);
                    currentFocus.clearFocus();
                }
            String email = contactEmail.getText().toString().trim();
            String phone = contactPhone.getText().toString().trim();
            if (!email.isEmpty() && !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                contactEmail.setError("Email không hợp lệ");
                return;
            }
            if (!phone.isEmpty() && !phone.matches("\\+[1-9][0-9]{7,14}")) {
                contactPhone.setError("Dùng định dạng quốc tế, ví dụ +84901234567");
                return;
            }
            try {
                JSONObject updatedPreferences = new JSONObject(preferences == null ? "{}" : preferences.toString());
                updatedPreferences.put("language", language.getSelectedItem().toString());
                updatedPreferences.put("notificationsEnabled", notifications.isChecked());
                JSONObject body = new JSONObject().put("preferences", updatedPreferences);
                if (!email.isEmpty()) body.put("contactEmail", email);
                if (!phone.isEmpty()) body.put("contactPhone", phone);
                apiClient.patch("/me", body.toString()).addOnCompleteListener(task -> {
                    if (!isAdded() || binding == null) return;
                    if (task.isSuccessful() && task.getResult() != null
                            && task.getResult().statusCode >= 200 && task.getResult().statusCode < 300) {
                        Toast.makeText(requireContext(), "Đã cập nhật thông tin liên hệ.", Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                        loadProfile();
                    } else {
                        String message = "Không thể cập nhật hồ sơ. Kiểm tra kết nối rồi thử lại.";
                        if (task.isSuccessful()) {
                            var response = task.getResult();
                            message = "Không thể cập nhật hồ sơ. Vui lòng thử lại sau.";
                            try {
                                if ("CONTACT_PHONE_EXISTS".equals(new JSONObject(response.body).optString("error")))
                                    message = "Số điện thoại liên hệ đã được sử dụng.";
                            } catch (org.json.JSONException ignoredResponse) { }
                        }
                        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                Toast.makeText(requireContext(), "Không thể tạo yêu cầu cập nhật.", Toast.LENGTH_SHORT).show();
            }
            });
        });
        dialog.show();
    }

    private void fetchEkycStatus() {
        if (apiClient == null) return;
        apiClient.get("/ekyc").addOnCompleteListener(task -> {
            if (!isAdded() || binding == null) return;
            requireActivity().runOnUiThread(() -> {
                if (binding == null) return;
                if (task.isSuccessful() && task.getResult() != null && task.getResult().statusCode == 200) {
                    try {
                        JSONObject json = new JSONObject(task.getResult().body);
                        JSONArray items = json.optJSONArray("items");
                        if (items != null && items.length() > 0) {
                            JSONObject latest = items.getJSONObject(0);
                            String status = latest.optString("reviewStatus", "DRAFT");
                            applyEkycStatus(status, "DEMO_SELFIE_ONLY".equals(latest.optString("mode")));
                            return;
                        }
                        applyEkycStatus("NONE", "DEMO_SELFIE_ONLY".equals(json.optString("captureMode")));
                        return;
                    } catch (Exception ignored) {}
                }
                applyEkycStatus("NONE", false);
            });
        });
    }

    private void applyEkycStatus(String status, boolean demo) {
        if (binding == null) return;
        switch (status) {
            case "VERIFIED":
                binding.ekycBadge.setText(demo ? "✓ Đã đăng ký khuôn mặt" : "✓ Đã xác thực eKYC");
                binding.ekycBadge.setBackgroundResource(R.drawable.badge_status_success);
                binding.ekycBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.success));
                break;
            case "REVIEWING":
                binding.ekycBadge.setText(demo ? "⏳ Đang kiểm tra ảnh selfie" : "⏳ eKYC đang xử lý");
                binding.ekycBadge.setBackgroundResource(R.drawable.badge_status_warning);
                binding.ekycBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.warning));
                break;
            case "RESUBMIT_REQUIRED":
                binding.ekycBadge.setText(demo ? "⚠️ Cần chọn ảnh selfie khác" : "⚠️ Cần chọn lại ảnh");
                binding.ekycBadge.setBackgroundResource(R.drawable.badge_status_warning);
                binding.ekycBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.warning));
                break;
            case "REJECTED":
                binding.ekycBadge.setText("✕ eKYC Bị từ chối");
                binding.ekycBadge.setBackgroundResource(R.drawable.badge_status_danger);
                binding.ekycBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.danger));
                break;
            case "DRAFT":
                binding.ekycBadge.setText(demo ? "Chưa hoàn tất đăng ký khuôn mặt" : "Chưa hoàn tất eKYC");
                binding.ekycBadge.setBackgroundResource(R.drawable.glass_panel_soft);
                binding.ekycBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink_muted));
                break;
            default:
                binding.ekycBadge.setText(demo ? "Chưa đăng ký khuôn mặt" : "Chưa định danh eKYC");
                binding.ekycBadge.setBackgroundResource(R.drawable.glass_panel_soft);
                binding.ekycBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink_muted));
                break;
        }
    }

    private void render(AuthState state) {
        if (state == null || state.getUser() == null || binding == null) return;
        String fullName = state.getUser().getFullName();
        String displayName = (fullName == null || fullName.trim().isEmpty())
                ? state.getUser().getEmail() : fullName;

        binding.nameText.setText(displayName);
        binding.emailText.setText(state.getUser().getEmail());
        binding.roleText.setText(state.getUser().getRole().name());

        // Avatar initials
        String initials = "NB";
        if (displayName != null && !displayName.isEmpty()) {
            String[] parts = displayName.trim().split("\\s+");
            if (parts.length >= 2) {
                initials = ("" + parts[0].charAt(0) + parts[parts.length - 1].charAt(0)).toUpperCase();
            } else if (displayName.length() >= 2) {
                initials = displayName.substring(0, 2).toUpperCase();
            }
        }
        binding.avatarInitials.setText(initials);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}

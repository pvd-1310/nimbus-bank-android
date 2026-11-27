package com.example.final_term.ui.officer;

import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.Patterns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.example.final_term.auth.AuthState;
import com.example.final_term.auth.UserRole;
import com.example.final_term.core.FinalTermApplication;
import com.example.final_term.databinding.FragmentOfficerCustomerFormBinding;
import com.example.final_term.ui.common.ThemedDialogBuilder;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

public final class OfficerCustomerFormFragment extends Fragment {
    private static final String[] GENDER_OPTIONS = {"Nam", "Nữ", "Khác"};
    private static final String[] LANGUAGE_OPTIONS = {"Tiếng Việt", "English"};

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

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle state) {
        binding = FragmentOfficerCustomerFormBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        app = (FinalTermApplication) requireActivity().getApplication();
        Bundle args = getArguments();
        editing = args != null && "edit".equals(args.getString("mode", "create"));
        customerId = args == null ? "" : args.getString("customerId", "");
        accountNumber = args == null ? "" : args.getString("accountNumber", "");

        binding.backButton.setOnClickListener(v -> Navigation.findNavController(v).navigateUp());
        binding.cancelButton.setOnClickListener(v -> Navigation.findNavController(v).navigateUp());
        binding.formTitle.setText(editing ? "Điều chỉnh thông tin" : "Mở hồ sơ khách hàng mới");
        binding.formIntro.setText(editing
                ? "Thông tin chủ tài khoản thanh toán"
                : "Nhập thông tin cơ bản để cấp tài khoản mới. Khách hàng sẽ tự xác thực eKYC sau khi đăng nhập.");

        binding.genderInput.setSimpleItems(GENDER_OPTIONS);
        binding.languageInput.setSimpleItems(LANGUAGE_OPTIONS);
        binding.languageInput.setText("Tiếng Việt", false);
        if (!editing) {
            binding.nationalityInput.setText("Việt Nam");
            int randomNum = 100000 + new java.util.Random().nextInt(900000);
            binding.customerNoInput.setText("KH-" + randomNum);
        }

        binding.dobInput.setOnClickListener(v -> openDatePicker("Chọn ngày sinh", binding.dobInput));
        binding.dobLayout.setEndIconOnClickListener(v -> openDatePicker("Chọn ngày sinh", binding.dobInput));
        binding.idExpiryInput.setOnClickListener(v -> openDatePicker("Chọn ngày hết hạn CCCD", binding.idExpiryInput));
        binding.idExpiryLayout.setEndIconOnClickListener(v -> openDatePicker("Chọn ngày hết hạn CCCD", binding.idExpiryInput));

        binding.emailLayout.setHint(editing ? "Email liên hệ" : "Email đăng nhập");
        binding.phoneLayout.setHint(editing ? "Số điện thoại liên hệ" : "Số điện thoại");
        binding.phoneLayout.setHelperText(editing
                ? "Dạng quốc tế, ví dụ +84901234567"
                : "10 chữ số bắt đầu bằng 0");
        binding.phoneInput.setInputType(InputType.TYPE_CLASS_PHONE);

        binding.nationalIdLayout.setVisibility(editing ? View.GONE : View.VISIBLE);
        binding.customerNoLayout.setVisibility(editing ? View.GONE : View.VISIBLE);
        binding.nationalityLayout.setVisibility(editing ? View.VISIBLE : View.GONE);
        binding.idExpiryLayout.setVisibility(editing ? View.VISIBLE : View.GONE);
        binding.preferencesCard.setVisibility(editing ? View.VISIBLE : View.GONE);
        binding.reasonCard.setVisibility(editing ? View.VISIBLE : View.GONE);

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

    private void openDatePicker(String title, TextInputEditText target) {
        MaterialDatePicker.Builder<Long> builder = MaterialDatePicker.Builder.datePicker().setTitleText(title);
        String currentIso = toIsoDate(textOf(target));
        if (!currentIso.isEmpty()) {
            try {
                SimpleDateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                isoFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date parsed = isoFormat.parse(currentIso);
                if (parsed != null) builder.setSelection(parsed.getTime());
            } catch (Exception ignored) { }
        }
        MaterialDatePicker<Long> picker = builder.build();
        picker.addOnPositiveButtonClickListener(selection -> {
            if (selection == null || binding == null) return;
            SimpleDateFormat displayFormat = new SimpleDateFormat("dd/MM/yyyy", Locale.US);
            displayFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
            target.setText(displayFormat.format(new Date(selection)));
        });
        picker.show(getParentFragmentManager(), title);
    }

    private void loadCustomer() {
        if (binding == null) return;
        binding.formContent.setVisibility(View.GONE);
        binding.submitButton.setEnabled(false);
        binding.loadStatePanel.setVisibility(View.VISIBLE);
        binding.loadProgress.setVisibility(View.VISIBLE);
        binding.retryButton.setVisibility(View.GONE);
        binding.statusText.setText("Đang tải thông tin khách hàng...");
        String path = "/officer/customers/" + Uri.encode(customerId);
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
                binding.dobInput.setText(formatIsoToDisplay(original.optString("dateOfBirth", "")));
                String gender = original.optString("gender", "");
                if (!gender.isEmpty()) binding.genderInput.setText(gender, false);
                binding.nationalityInput.setText(original.optString("nationality", ""));
                binding.idExpiryInput.setText(formatIsoToDisplay(original.optString("identityDocumentExpiry", "")));
                binding.addressInput.setText(original.optString("address", ""));
                binding.emailInput.setText(original.optString("contactEmail", ""));
                binding.phoneInput.setText(original.optString("contactPhone", ""));

                JSONObject prefs = original.optJSONObject("preferences");
                boolean notifications = prefs == null || prefs.optBoolean("notificationsEnabled", true);
                binding.notificationsSwitch.setChecked(notifications);
                String lang = prefs == null ? "vi" : prefs.optString("language", "vi");
                binding.languageInput.setText("en".equalsIgnoreCase(lang) ? "English" : "Tiếng Việt", false);

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
        clearErrors();

        String name = textOf(binding.nameInput);
        String email = textOf(binding.emailInput);
        String phone = textOf(binding.phoneInput);
        String address = textOf(binding.addressInput);
        String nationalId = textOf(binding.nationalIdInput);
        String customerNo = textOf(binding.customerNoInput);
        String dobIso = toIsoDate(textOf(binding.dobInput));
        String gender = String.valueOf(binding.genderInput.getText()).trim();
        String nationality = textOf(binding.nationalityInput);
        String idExpiryIso = toIsoDate(textOf(binding.idExpiryInput));
        boolean notificationsEnabled = binding.notificationsSwitch.isChecked();
        String languageCode = "English".equals(String.valueOf(binding.languageInput.getText()).trim()) ? "en" : "vi";
        String reason = textOf(binding.reasonInput);

        if (name.length() < 2) {
            binding.nameLayout.setError("Nhập họ tên đầy đủ");
            binding.nameInput.requestFocus();
            return;
        }
        if (editing) {
            submitEdit(name, email, phone, address, dobIso, gender, nationality, idExpiryIso,
                    notificationsEnabled, languageCode, reason);
        } else {
            submitCreate(name, email, phone, address, nationalId, customerNo, dobIso, gender,
                    nationality, idExpiryIso, notificationsEnabled, languageCode);
        }
    }

    private void submitCreate(String name, String email, String phone, String address,
                              String nationalId, String customerNo, String dobIso, String gender,
                              String nationality, String idExpiryIso, boolean notificationsEnabled,
                              String languageCode) {
        if (!nationalId.matches("[0-9]{12}")) {
            binding.nationalIdLayout.setError("CCCD cần đúng 12 chữ số");
            binding.nationalIdInput.requestFocus();
            return;
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.emailLayout.setError("Email không hợp lệ");
            binding.emailInput.requestFocus();
            return;
        }
        if (!phone.matches("0[0-9]{9}")) {
            binding.phoneLayout.setError("Nhập 10 chữ số, bắt đầu bằng 0");
            binding.phoneInput.requestFocus();
            return;
        }
        try {
            JSONObject preferences = new JSONObject()
                    .put("notificationsEnabled", notificationsEnabled)
                    .put("language", languageCode);
            JSONObject body = new JSONObject()
                    .put("fullName", name)
                    .put("email", email)
                    .put("contactEmail", email)
                    .put("phoneNumber", phone)
                    .put("nationalIdNumber", nationalId)
                    .put("preferences", preferences);
            if (!dobIso.isEmpty()) body.put("dateOfBirth", dobIso);
            if (!gender.isEmpty()) body.put("gender", gender);
            if (!nationality.isEmpty()) body.put("nationality", nationality);
            if (!idExpiryIso.isEmpty()) body.put("identityDocumentExpiry", idExpiryIso);
            if (!address.isEmpty()) body.put("address", address);
            if (!customerNo.isEmpty()) body.put("customerNo", customerNo);

            String snapshot = body.toString();
            if (pendingRequestId == null || !snapshot.equals(pendingSnapshot)) {
                pendingRequestId = UUID.randomUUID().toString();
                pendingSnapshot = snapshot;
            }
            body.put("requestId", pendingRequestId);

            setSubmitting(true);
            app.getAppContainer().getBackendApiClient().post("/officer/customers", body.toString())
                    .addOnSuccessListener(response -> {
                        setSubmitting(false);
                        if (binding == null) return;
                        if (response.statusCode >= 200 && response.statusCode < 300) {
                            sendPasswordSetup(email, response.body);
                        } else {
                            String code = "";
                            try { code = new JSONObject(response.body).optString("error", ""); }
                            catch (Exception ignored) { }
                            String message = "Không thể tạo hồ sơ. Kiểm tra thông tin rồi thử lại.";
                            if ("CUSTOMER_NUMBER_EXISTS".equals(code)) message = "Mã khách hàng đã tồn tại.";
                            else if ("CONTACT_PHONE_EXISTS".equals(code)) message = "Số điện thoại liên hệ đã được sử dụng.";
                            else if ("PROVISION_IDENTITY_FAILED".equals(code)) message = "Email đăng nhập hoặc số điện thoại đã được sử dụng.";
                            showInlineError(message);
                        }
                    }).addOnFailureListener(error -> {
                        setSubmitting(false);
                        if (binding != null) showInlineError("Không thể kết nối. Kiểm tra mạng rồi thử lại.");
                    });
        } catch (Exception error) {
            setSubmitting(false);
            showInlineError("Không thể gửi thông tin khách hàng.");
        }
    }

    private void submitEdit(String name, String email, String phone, String address,
                            String dobIso, String gender, String nationality, String idExpiryIso,
                            boolean notificationsEnabled, String languageCode, String reason) {
        if (reason.length() < 3) {
            binding.reasonLayout.setError("Nêu lý do điều chỉnh (ít nhất 3 ký tự)");
            binding.reasonInput.requestFocus();
            return;
        }
        if (!email.isEmpty() && !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.emailLayout.setError("Email không hợp lệ");
            binding.emailInput.requestFocus();
            return;
        }
        boolean phoneChanged = !phone.equals(original.optString("contactPhone", ""));
        if (phoneChanged && !phone.isEmpty() && !phone.matches("\\+[1-9][0-9]{7,14}")) {
            binding.phoneLayout.setError("Dùng định dạng quốc tế, ví dụ +84901234567");
            binding.phoneInput.requestFocus();
            return;
        }
        try {
            JSONObject body = new JSONObject().put("reason", reason);
            if (!name.equals(original.optString("fullName", ""))) body.put("fullName", name);
            if (!email.equals(original.optString("contactEmail", "")) && !email.isEmpty()) body.put("contactEmail", email);
            if (phoneChanged && !phone.isEmpty()) body.put("contactPhone", phone);
            if (!address.equals(original.optString("address", "")) && !address.isEmpty()) body.put("address", address);
            if (!dobIso.equals(original.optString("dateOfBirth", "")) && !dobIso.isEmpty()) body.put("dateOfBirth", dobIso);
            if (!gender.equals(original.optString("gender", "")) && !gender.isEmpty()) body.put("gender", gender);
            if (!nationality.equals(original.optString("nationality", "")) && !nationality.isEmpty()) body.put("nationality", nationality);
            if (!idExpiryIso.equals(original.optString("identityDocumentExpiry", "")) && !idExpiryIso.isEmpty()) {
                body.put("identityDocumentExpiry", idExpiryIso);
            }

            JSONObject origPrefs = original.optJSONObject("preferences");
            boolean origNotif = origPrefs == null || origPrefs.optBoolean("notificationsEnabled", true);
            String origLang = origPrefs == null ? "vi" : origPrefs.optString("language", "vi");
            if (notificationsEnabled != origNotif || !languageCode.equalsIgnoreCase(origLang)) {
                body.put("preferences", new JSONObject()
                        .put("notificationsEnabled", notificationsEnabled)
                        .put("language", languageCode));
            }

            if (body.length() == 1) {
                showInlineError("Không có thông tin nào thay đổi.");
                return;
            }
            setSubmitting(true);
            String path = "/officer/customers/" + Uri.encode(customerId);
            app.getAppContainer().getBackendApiClient().patch(path, body.toString())
                    .addOnSuccessListener(response -> {
                        setSubmitting(false);
                        if (binding == null) return;
                        if (response.statusCode >= 200 && response.statusCode < 300) {
                            Toast.makeText(requireContext(), "Đã cập nhật hồ sơ khách hàng.", Toast.LENGTH_SHORT).show();
                            Navigation.findNavController(binding.getRoot()).navigateUp();
                        } else {
                            showInlineError("Không thể cập nhật hồ sơ. Kiểm tra thông tin rồi thử lại.");
                        }
                    }).addOnFailureListener(error -> {
                        setSubmitting(false);
                        if (binding != null) showInlineError("Không thể kết nối. Kiểm tra mạng rồi thử lại.");
                    });
        } catch (Exception error) {
            setSubmitting(false);
            showInlineError("Không thể gửi thay đổi.");
        }
    }

    private void sendPasswordSetup(String email, String responseBody) {
        String accountNumber = "";
        String accountId = "";
        String customerNo = "";
        try {
            JSONObject res = new JSONObject(responseBody);
            accountNumber = res.optString("accountNumber", "");
            accountId = res.optString("accountId", "");
            customerNo = res.optString("customerNo", "");
        } catch (Exception ignored) { }
        final String displayAccount = (!accountNumber.isEmpty()
                ? accountNumber.replaceAll("(.{4})(?=.)", "$1 ") : accountId);
        final String displayCustomerNo = customerNo.isEmpty() ? "—" : customerNo;
        app.getAppContainer().getAuthRepository().sendPasswordResetEmail(email).addOnCompleteListener(reset -> {
            if (binding == null) return;
            String message = "Mã khách hàng: " + displayCustomerNo
                    + "\nSố tài khoản: " + displayAccount
                    + "\nTrạng thái: Chờ khách hàng eKYC & Officer duyệt"
                    + "\n\nĐã gửi email thiết lập mật khẩu tới " + email + ".";
            if (!reset.isSuccessful()) {
                message = "Mã khách hàng: " + displayCustomerNo
                        + "\nSố tài khoản: " + displayAccount
                        + "\nTrạng thái: Chờ khách hàng eKYC & Officer duyệt"
                        + "\n\nEmail chưa gửi được; khách hàng có thể dùng mục Đặt/quên mật khẩu ở màn đăng nhập.";
            }
            new ThemedDialogBuilder(requireContext())
                    .setTitle("Phiếu cấp hồ sơ mới")
                    .setMessage(message)
                    .setPositiveButton("Hoàn tất", (dialog, which) -> Navigation.findNavController(binding.getRoot()).navigateUp())
                    .show();
        });
    }

    private void setSubmitting(boolean value) {
        submitting = value;
        if (binding == null) return;
        binding.savingProgress.setVisibility(value ? View.VISIBLE : View.GONE);
        binding.submitButton.setEnabled(!value);
        binding.cancelButton.setEnabled(!value);
    }

    private void clearErrors() {
        if (binding == null) return;
        binding.nameLayout.setError(null);
        binding.nationalIdLayout.setError(null);
        binding.dobLayout.setError(null);
        binding.idExpiryLayout.setError(null);
        binding.emailLayout.setError(null);
        binding.phoneLayout.setError(null);
        binding.reasonLayout.setError(null);
        binding.formStatusText.setVisibility(View.GONE);
    }

    private void showInlineError(String message) {
        if (binding == null) return;
        binding.formStatusText.setText(message);
        binding.formStatusText.setVisibility(View.VISIBLE);
    }

    private static String textOf(TextInputEditText input) {
        return input.getText() == null ? "" : input.getText().toString().trim();
    }

    private static String formatIsoToDisplay(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return String.format(Locale.US, "%s/%s/%s",
                    value.substring(8, 10),
                    value.substring(5, 7),
                    value.substring(0, 4));
        }
        return value;
    }

    private static String toIsoDate(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.matches("\\d{2}/\\d{2}/\\d{4}")) {
            return String.format(Locale.US, "%s-%s-%s",
                    value.substring(6, 10),
                    value.substring(3, 5),
                    value.substring(0, 2));
        }
        return value.matches("\\d{4}-\\d{2}-\\d{2}") ? value : "";
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }
}

package com.example.final_term.ui.profile;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.util.Patterns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import com.example.final_term.R;
import com.example.final_term.core.FinalTermApplication;
import com.example.final_term.data.BackendApiClient;
import com.example.final_term.databinding.FragmentPersonalInfoBinding;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Full-screen personal information view and contact/preferences editor.
 */
public class PersonalInfoFragment extends Fragment {
    private static final String NOT_UPDATED = "Chưa cập nhật";

    private FragmentPersonalInfoBinding binding;
    private BackendApiClient apiClient;
    private JSONObject currentProfile = new JSONObject();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentPersonalInfoBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        FinalTermApplication app = (FinalTermApplication) requireActivity().getApplication();
        apiClient = app.getAppContainer().getBackendApiClient();

        String[] languageLabels = {"Tiếng Việt", "English"};
        binding.languageInput.setSimpleItems(languageLabels);
        binding.languageInput.setText("Tiếng Việt", false);

        binding.backButton.setOnClickListener(v -> navigateBack());
        binding.cancelButton.setOnClickListener(v -> navigateBack());
        binding.saveButton.setOnClickListener(v -> saveProfile());
        binding.copyNationalIdButton.setOnClickListener(v -> copyNationalId());

        loadProfile();
    }

    private void loadProfile() {
        if (apiClient == null || binding == null) return;
        final FragmentPersonalInfoBinding current = binding;
        setFormEnabled(false);
        binding.headerNameText.setText("Đang tải thông tin…");

        apiClient.get("/me").addOnCompleteListener(task -> {
            if (!isAdded() || binding != current) return;
            setFormEnabled(true);
            try {
                if (!task.isSuccessful() || task.getResult() == null || task.getResult().statusCode != 200) {
                    throw new JSONException("Unavailable");
                }
                currentProfile = new JSONObject(task.getResult().body);
                bindProfile(currentProfile);
            } catch (JSONException ignored) {
                binding.headerNameText.setText("Chưa tải được hồ sơ");
                showStatus("Không thể tải thông tin cá nhân. Vui lòng thử lại.", true);
            }
        });
    }

    private void bindProfile(JSONObject profile) {
        if (binding == null) return;

        String fullName = profile.optString("fullName", "").trim();
        String nationalId = profile.optString("nationalIdNumber", "").trim();
        String customerNo = profile.optString("customerNo", "").trim();
        String tier = profile.optString("tier", "").trim();

        binding.headerNameText.setText(fullName.isEmpty() ? NOT_UPDATED : fullName);
        if (!nationalId.isEmpty()) {
            binding.headerEkycBadge.setText("Đã định danh");
            binding.headerEkycBadge.setBackgroundResource(R.drawable.badge_status_success);
            binding.headerEkycBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.success));
        } else {
            binding.headerEkycBadge.setText("Chưa định danh");
            binding.headerEkycBadge.setBackgroundResource(R.drawable.badge_status_neutral);
            binding.headerEkycBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink_muted));
        }

        binding.headerCustomerNoText.setText(customerNo.isEmpty() ? "Mã KH: —" : "Mã KH: " + customerNo);
        if (tier.isEmpty()) {
            binding.headerTierText.setText("Hạng Standard");
        } else if ("PLATINUM".equalsIgnoreCase(tier)) {
            binding.headerTierText.setText("Hạng Platinum");
        } else {
            binding.headerTierText.setText("Hạng " + tier);
        }

        bindFieldValue(binding.rowFullName, fullName);
        bindFieldValue(binding.rowNationalId, nationalId);
        binding.copyNationalIdButton.setVisibility(nationalId.isEmpty() ? View.GONE : View.VISIBLE);

        bindFieldValue(binding.rowDob, formatIsoDate(profile.optString("dateOfBirth", "")));
        bindFieldValue(binding.rowGender, profile.optString("gender", ""));
        bindFieldValue(binding.rowNationality, profile.optString("nationality", ""));
        bindFieldValue(binding.rowIdExpiry, formatIsoDate(profile.optString("identityDocumentExpiry", "")));
        bindFieldValue(binding.rowAddress, profile.optString("address", ""));

        binding.contactEmailInput.setText(profile.optString("contactEmail", ""));
        binding.contactPhoneInput.setText(profile.optString("contactPhone", ""));

        JSONObject preferences = profile.optJSONObject("preferences");
        boolean notificationsEnabled = preferences == null
                || preferences.optBoolean("notificationsEnabled", true);
        binding.notificationsSwitch.setChecked(notificationsEnabled);
        String languageCode = preferences == null ? "vi" : preferences.optString("language", "vi");
        binding.languageInput.setText("en".equalsIgnoreCase(languageCode) ? "English" : "Tiếng Việt", false);
    }

    private void bindFieldValue(TextView target, String rawValue) {
        String value = rawValue == null ? "" : rawValue.trim();
        if (value.isEmpty()) {
            target.setText(NOT_UPDATED);
            target.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink_faint));
        } else {
            target.setText(value);
            target.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink));
        }
    }

    private String formatIsoDate(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return String.format(Locale.US, "%s/%s/%s",
                    value.substring(8, 10),
                    value.substring(5, 7),
                    value.substring(0, 4));
        }
        return value;
    }

    private void copyNationalId() {
        String nationalId = currentProfile.optString("nationalIdNumber", "").trim();
        if (nationalId.isEmpty() || !isAdded()) return;
        ClipboardManager clipboard = (ClipboardManager) requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("CCCD", nationalId));
            Toast.makeText(requireContext(), "Đã sao chép số định danh.", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveProfile() {
        if (binding == null || apiClient == null) return;
        String email = binding.contactEmailInput.getText() == null
                ? "" : binding.contactEmailInput.getText().toString().trim();
        String phone = binding.contactPhoneInput.getText() == null
                ? "" : binding.contactPhoneInput.getText().toString().trim();

        binding.contactEmailLayout.setError(null);
        binding.contactPhoneLayout.setError(null);
        binding.formStatusText.setVisibility(View.GONE);

        if (!email.isEmpty() && !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.contactEmailLayout.setError("Nhập email hợp lệ.");
            binding.contactEmailInput.requestFocus();
            return;
        }
        if (!phone.isEmpty() && !phone.matches("\\+[1-9][0-9]{7,14}")) {
            binding.contactPhoneLayout.setError("Dùng định dạng quốc tế, ví dụ +84901234567.");
            binding.contactPhoneInput.requestFocus();
            return;
        }

        hideKeyboard();

        try {
            JSONObject existingPrefs = currentProfile.optJSONObject("preferences");
            JSONObject updatedPreferences = new JSONObject(existingPrefs == null ? "{}" : existingPrefs.toString());
            String selectedLanguage = String.valueOf(binding.languageInput.getText());
            updatedPreferences.put("language", "English".equals(selectedLanguage) ? "en" : "vi");
            updatedPreferences.put("notificationsEnabled", binding.notificationsSwitch.isChecked());

            JSONObject body = new JSONObject().put("preferences", updatedPreferences);
            if (!email.isEmpty()) body.put("contactEmail", email);
            if (!phone.isEmpty()) body.put("contactPhone", phone);

            setSavingState(true);
            showStatus("Đang lưu thay đổi…", false);

            apiClient.patch("/me", body.toString()).addOnCompleteListener(task -> {
                if (!isAdded() || binding == null) return;
                setSavingState(false);

                if (task.isSuccessful() && task.getResult() != null
                        && task.getResult().statusCode >= 200 && task.getResult().statusCode < 300) {
                    Toast.makeText(requireContext(), "Thông tin cá nhân đã được cập nhật.", Toast.LENGTH_SHORT).show();
                    navigateBack();
                    return;
                }

                String message = "Chưa thể lưu thay đổi. Vui lòng kiểm tra kết nối rồi thử lại.";
                if (task.isSuccessful() && task.getResult() != null) {
                    try {
                        String error = new JSONObject(task.getResult().body).optString("error");
                        if ("CONTACT_PHONE_EXISTS".equals(error)) {
                            binding.contactPhoneLayout.setError("Số điện thoại này đã được sử dụng.");
                            binding.contactPhoneInput.requestFocus();
                            message = "Hãy kiểm tra lại số điện thoại liên hệ.";
                        } else if ("CONTACT_EMAIL_EXISTS".equals(error)) {
                            binding.contactEmailLayout.setError("Email này đã được sử dụng.");
                            binding.contactEmailInput.requestFocus();
                            message = "Hãy kiểm tra lại email liên hệ.";
                        } else {
                            message = "Chưa thể lưu thay đổi. Vui lòng thử lại sau.";
                        }
                    } catch (JSONException ignored) {
                        message = "Chưa thể lưu thay đổi. Vui lòng thử lại sau.";
                    }
                }
                showStatus(message, true);
            });
        } catch (Exception e) {
            setSavingState(false);
            showStatus("Không thể lưu thông tin. Vui lòng thử lại.", true);
        }
    }

    private void setSavingState(boolean saving) {
        if (binding == null) return;
        binding.savingProgress.setVisibility(saving ? View.VISIBLE : View.GONE);
        setFormEnabled(!saving);
        binding.saveButton.setText(saving ? "Đang lưu…" : "Lưu thay đổi");
    }

    private void setFormEnabled(boolean enabled) {
        if (binding == null) return;
        binding.saveButton.setEnabled(enabled);
        binding.cancelButton.setEnabled(enabled);
        binding.backButton.setEnabled(enabled);
        binding.contactEmailInput.setEnabled(enabled);
        binding.contactPhoneInput.setEnabled(enabled);
        binding.notificationsSwitch.setEnabled(enabled);
        binding.languageInput.setEnabled(enabled);
    }

    private void showStatus(String message, boolean error) {
        if (binding == null || !isAdded()) return;
        binding.formStatusText.setText(message);
        binding.formStatusText.setTextColor(ContextCompat.getColor(
                requireContext(), error ? R.color.danger : R.color.ink_muted));
        binding.formStatusText.setVisibility(View.VISIBLE);
    }

    private void hideKeyboard() {
        if (!isAdded()) return;
        View currentFocus = requireActivity().getCurrentFocus();
        if (currentFocus != null) {
            InputMethodManager imm = (InputMethodManager) requireContext()
                    .getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(currentFocus.getWindowToken(), 0);
            currentFocus.clearFocus();
        }
    }

    private void navigateBack() {
        if (!isAdded()) return;
        NavHostFragment.findNavController(this).popBackStack();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}

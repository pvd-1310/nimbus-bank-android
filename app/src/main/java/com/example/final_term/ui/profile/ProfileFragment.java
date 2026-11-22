package com.example.final_term.ui.profile;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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

public final class ProfileFragment extends Fragment {
    private FragmentProfileBinding binding;
    private BackendApiClient apiClient;

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

        app.getAppContainer().getAuthRepository().observeAuthState()
                .observe(getViewLifecycleOwner(), this::render);

        getParentFragmentManager().setFragmentResultListener("ekyc_status_changed", getViewLifecycleOwner(),
                (requestKey, result) -> fetchEkycStatus());

        binding.logoutButton.setOnClickListener(v -> app.getAppContainer().getAuthRepository().signOut());

        binding.qrButton.setOnClickListener(v -> new AlertDialog.Builder(requireContext())
                .setTitle("Mã QR nhận tiền")
                .setMessage("Mã QR cá nhân liên kết tài khoản thanh toán chính của bạn để nhận chuyển khoản nhanh.")
                .setPositiveButton("Đóng", null)
                .show());

        View.OnClickListener openEkycListener = v -> {
            com.example.final_term.ui.ekyc.EkycDialogFragment dialog = new com.example.final_term.ui.ekyc.EkycDialogFragment();
            dialog.show(getParentFragmentManager(), "ekyc_dialog");
        };

        binding.itemPersonalInfo.setOnClickListener(openEkycListener);
        binding.ekycBadge.setOnClickListener(openEkycListener);

        binding.itemCards.setOnClickListener(v -> new AlertDialog.Builder(requireContext())
                .setTitle("Hạn mức & Bảo mật")
                .setMessage("Hạn mức giao dịch tối đa: 100.000.000 ₫/ngày.\nPhương thức xác thực: OTP SMS / Biometrics.\n\nĐể nâng hạn mức lên 500.000.000 ₫/ngày, vui lòng đến chi nhánh Nimbus gần nhất.")
                .setPositiveButton("Đóng", null)
                .show());

        binding.itemLanguage.setOnClickListener(v -> Toast.makeText(requireContext(), "Ngôn ngữ hiện tại: Tiếng Việt", Toast.LENGTH_SHORT).show());

        binding.itemAi.setOnClickListener(v -> new AlertDialog.Builder(requireContext())
                .setTitle("Trợ lý tài chính Nimbus AI")
                .setMessage("Tính năng trợ lý AI thông minh đang chờ cấu hình backend (Mô hình: DEFERRED tại GET /capabilities).\n\nTrợ lý sẽ sẵn sàng hỗ trợ phân tích chi tiêu cá nhân ngay khi kết nối.")
                .setPositiveButton("Đóng", null)
                .show());

        fetchEkycStatus();
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
                            applyEkycStatus(status);
                            return;
                        }
                    } catch (Exception ignored) {}
                }
                applyEkycStatus("NONE");
            });
        });
    }

    private void applyEkycStatus(String status) {
        if (binding == null) return;
        switch (status) {
            case "VERIFIED":
                binding.ekycBadge.setText("✓ eKYC Đã xác thực");
                binding.ekycBadge.setBackgroundResource(R.drawable.badge_status_success);
                binding.ekycBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.success));
                break;
            case "REVIEWING":
                binding.ekycBadge.setText("⏳ eKYC Đang thẩm định");
                binding.ekycBadge.setBackgroundResource(R.drawable.badge_status_warning);
                binding.ekycBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.warning));
                break;
            case "RESUBMIT_REQUIRED":
                binding.ekycBadge.setText("⚠️ eKYC Cần chụp lại");
                binding.ekycBadge.setBackgroundResource(R.drawable.badge_status_warning);
                binding.ekycBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.warning));
                break;
            case "REJECTED":
                binding.ekycBadge.setText("✕ eKYC Bị từ chối");
                binding.ekycBadge.setBackgroundResource(R.drawable.badge_status_danger);
                binding.ekycBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.danger));
                break;
            case "DRAFT":
                binding.ekycBadge.setText("Chưa hoàn tất eKYC");
                binding.ekycBadge.setBackgroundResource(R.drawable.glass_panel_soft);
                binding.ekycBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink_muted));
                break;
            default:
                binding.ekycBadge.setText("Chưa định danh eKYC");
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

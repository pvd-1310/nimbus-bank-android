package com.example.final_term.ui.profile;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.final_term.auth.AuthState;
import com.example.final_term.core.FinalTermApplication;
import com.example.final_term.databinding.FragmentProfileBinding;

public final class ProfileFragment extends Fragment {
    private FragmentProfileBinding binding;

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
        app.getAppContainer().getAuthRepository().observeAuthState()
                .observe(getViewLifecycleOwner(), this::render);

        binding.logoutButton.setOnClickListener(v -> app.getAppContainer().getAuthRepository().signOut());

        binding.qrButton.setOnClickListener(v -> new AlertDialog.Builder(requireContext())
                .setTitle("Mã QR nhận tiền")
                .setMessage("Mã QR cá nhân liên kết tài khoản thanh toán chính của bạn để nhận chuyển khoản nhanh.")
                .setPositiveButton("Đóng", null)
                .show());

        binding.itemPersonalInfo.setOnClickListener(v -> {
            com.example.final_term.ui.ekyc.EkycDialogFragment dialog = new com.example.final_term.ui.ekyc.EkycDialogFragment();
            dialog.show(getParentFragmentManager(), "ekyc_dialog");
        });

        binding.itemCards.setOnClickListener(v -> new AlertDialog.Builder(requireContext())
                .setTitle("Hạn mức & Bảo mật")
                .setMessage("Hạn mức giao dịch tối đa: 100.000.000 ₫/ngày.\nPhương thức xác thực: OTP SMS / Biometrics.\n\nĐể nâng hạn mức lên 500.000.000 ₫/ngày, vui lòng đến chi nhánh Nimbus gần nhất.")
                .setPositiveButton("Đóng", null)
                .show());

        binding.itemLanguage.setOnClickListener(v -> Toast.makeText(requireContext(), "Ngôn ngữ hiện tại: Tiếng Việt", Toast.LENGTH_SHORT).show());

        binding.itemAi.setOnClickListener(v -> new AlertDialog.Builder(requireContext())
                .setTitle("Trợ lý tài chính Nimbus AI")
                .setMessage("Tính năng trợ lý AI thông minh đang chờ cấu hình backend (Mô hình: DEFERRED tại GET /v1/capabilities).\n\nTrợ lý sẽ sẵn sàng hỗ trợ phân tích chi tiêu cá nhân ngay khi kết nối.")
                .setPositiveButton("Đóng", null)
                .show());
    }

    private void render(AuthState state) {
        if (state == null || state.getUser() == null) return;
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

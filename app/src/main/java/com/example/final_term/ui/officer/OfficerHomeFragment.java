package com.example.final_term.ui.officer;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.final_term.R;
import com.example.final_term.auth.AuthState;
import com.example.final_term.auth.UserRole;
import com.example.final_term.core.FinalTermApplication;
import com.example.final_term.databinding.FragmentOfficerHomeBinding;
import androidx.navigation.Navigation;

public final class OfficerHomeFragment extends Fragment {
    private FragmentOfficerHomeBinding binding;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentOfficerHomeBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        FinalTermApplication app = (FinalTermApplication) requireActivity().getApplication();
        app.getAppContainer().getAuthRepository().observeAuthState().observe(getViewLifecycleOwner(), authState -> {
            if (authState == null || authState.getStatus() != AuthState.Status.SIGNED_IN
                    || authState.getUser() == null || authState.getUser().getRole() != UserRole.OFFICER) {
                requireActivity().getOnBackPressedDispatcher().onBackPressed();
                return;
            }
            binding.roleText.setText(getString(R.string.role_officer));
            String fullName = authState.getUser().getFullName();
            binding.identityText.setText(fullName == null || fullName.trim().isEmpty()
                    ? authState.getUser().getEmail() : fullName);
            binding.accountMetric.setText("Xem");
            binding.ekycMetric.setText("—");
        });
        binding.signOutButton.setOnClickListener(v -> app.getAppContainer().getAuthRepository().signOut());
        binding.accountButton.setOnClickListener(v -> Navigation.findNavController(v).navigate(R.id.accountList));
        binding.historyButton.setOnClickListener(v -> Navigation.findNavController(v).navigate(R.id.transactionHistory));
        binding.ekycButton.setOnClickListener(v -> openFeature(v, R.id.officerEkyc,
                "Duyệt hồ sơ eKYC", "Xem và xử lý các yêu cầu xác thực danh tính."));
        binding.auditButton.setOnClickListener(v -> openFeature(v, R.id.officerAudit,
                "Kiểm tra giao dịch", "Theo dõi lịch sử và audit log theo permission."));
        binding.ratesButton.setOnClickListener(v -> openFeature(v, R.id.officerRates,
                "Chính sách lãi suất", "Quản lý policy có version và lý do thay đổi."));
    }

    private void openFeature(View anchor, int destination, String title, String subtitle) {
        Bundle args = new Bundle();
        args.putString("title", title);
        args.putString("subtitle", subtitle);
        Navigation.findNavController(anchor).navigate(destination, args);
    }

    @Override public void onDestroyView() { super.onDestroyView(); binding = null; }
}

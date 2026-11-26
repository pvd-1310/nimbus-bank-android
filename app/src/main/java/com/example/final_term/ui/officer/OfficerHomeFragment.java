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
        });
        binding.checkingAccountsButton.setOnClickListener(v -> Navigation.findNavController(v).navigate(R.id.officerCheckingAccounts));
        binding.historyButton.setOnClickListener(v -> openFeature(v, R.id.officerAudit,
                "Lịch sử giao dịch hệ thống", "Tra cứu các giao dịch trên toàn hệ thống."));
        binding.ekycButton.setOnClickListener(v -> openFeature(v, R.id.officerEkyc,
                "Hồ sơ eKYC", "Theo dõi kết quả kiểm tra tự động và bằng chứng hồ sơ."));
        binding.auditButton.setOnClickListener(v -> openFeature(v, R.id.officerAudit,
                "Kiểm tra giao dịch", "Theo dõi lịch sử thao tác của cán bộ."));
        binding.ratesButton.setOnClickListener(v -> openFeature(v, R.id.officerRates,
                "Chính sách lãi suất", "Quản lý biểu lãi suất hiện hành và ngày hiệu lực."));
    }

    private void openFeature(View anchor, int destination, String title, String subtitle) {
        Bundle args = new Bundle();
        args.putString("title", title);
        args.putString("subtitle", subtitle);
        Navigation.findNavController(anchor).navigate(destination, args);
    }

    @Override public void onDestroyView() { super.onDestroyView(); binding = null; }
}

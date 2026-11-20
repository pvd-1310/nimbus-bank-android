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
import com.example.final_term.navigation.BottomNavigationBinder;

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
        binding.signOutButton.setOnClickListener(v -> app.getAppContainer().getAuthRepository().signOut());
        binding.accountButton.setOnClickListener(v -> Navigation.findNavController(v).navigate(R.id.accountList));
        binding.historyButton.setOnClickListener(v -> Navigation.findNavController(v).navigate(R.id.transactionHistory));
        BottomNavigationBinder.bind(binding.bottomNavigation.getRoot(), Navigation.findNavController(view),
                R.id.officerHome, R.id.officerHome);
    }

    @Override public void onDestroyView() { super.onDestroyView(); binding = null; }
}

package com.example.final_term.ui.officer;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;
import com.example.final_term.R;
import com.example.final_term.auth.AuthState;
import com.example.final_term.auth.UserRole;
import com.example.final_term.core.FinalTermApplication;
import com.example.final_term.databinding.FragmentOfficerFeatureBinding;

public final class OfficerFeatureFragment extends Fragment {
    private FragmentOfficerFeatureBinding binding;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                                                   @Nullable Bundle state) {
        binding = FragmentOfficerFeatureBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        FinalTermApplication app = (FinalTermApplication) requireActivity().getApplication();
        app.getAppContainer().getAuthRepository().observeAuthState().observe(getViewLifecycleOwner(), auth -> {
            if (auth == null || auth.getStatus() != AuthState.Status.SIGNED_IN || auth.getUser() == null
                    || auth.getUser().getRole() != UserRole.OFFICER) {
                requireActivity().getOnBackPressedDispatcher().onBackPressed();
                return;
            }
            Bundle args = getArguments();
            binding.eyebrowText.setText(args == null ? "OFFICER OPERATIONS" : args.getString("eyebrow", "OFFICER OPERATIONS"));
            binding.titleText.setText(args == null ? "Tác vụ vận hành" : args.getString("title", "Tác vụ vận hành"));
            binding.subtitleText.setText(args == null ? "Tác vụ này được kiểm soát bởi backend production." : args.getString("subtitle", "Tác vụ này được kiểm soát bởi backend production."));
            binding.statusText.setText("BACKEND CHECKPOINT");
        });
        binding.backButton.setOnClickListener(v -> Navigation.findNavController(v).navigateUp());
        binding.primaryButton.setOnClickListener(v -> Navigation.findNavController(v).navigateUp());
    }

    @Override public void onDestroyView() { super.onDestroyView(); binding = null; }
}

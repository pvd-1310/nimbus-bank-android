package com.example.final_term.ui.profile;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.final_term.R;
import com.example.final_term.auth.AuthState;
import com.example.final_term.core.FinalTermApplication;
import com.example.final_term.databinding.FragmentProfileBinding;

public final class ProfileFragment extends Fragment {
    private FragmentProfileBinding binding;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle state) {
        binding = FragmentProfileBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        FinalTermApplication app = (FinalTermApplication) requireActivity().getApplication();
        app.getAppContainer().getAuthRepository().observeAuthState()
                .observe(getViewLifecycleOwner(), authState -> render(authState));
        binding.logoutButton.setOnClickListener(v -> {
            app.getAppContainer().getAuthRepository().signOut();
        });
    }

    private void render(AuthState state) {
        if (state == null || state.getUser() == null) return;
        binding.nameText.setText(state.getUser().getFullName().isEmpty()
                ? state.getUser().getEmail() : state.getUser().getFullName());
        binding.emailText.setText(state.getUser().getEmail());
        binding.roleText.setText(state.getUser().getRole().name());
    }

    @Override public void onDestroyView() { super.onDestroyView(); binding = null; }
}

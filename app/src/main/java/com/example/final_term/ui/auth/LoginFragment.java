package com.example.final_term.ui.auth;

import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.final_term.R;
import com.example.final_term.auth.AuthState;
import com.example.final_term.core.FinalTermApplication;
import com.example.final_term.databinding.FragmentLoginBinding;

public final class LoginFragment extends Fragment {
    private FragmentLoginBinding binding;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentLoginBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        binding.emailInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        FinalTermApplication application = (FinalTermApplication) requireActivity().getApplication();
        application.getAppContainer().getAuthRepository().observeAuthState().observe(getViewLifecycleOwner(), this::renderState);
        binding.signInButton.setOnClickListener(v -> signIn());
    }

    private void signIn() {
        binding.emailInput.setError(null);
        binding.passwordInput.setError(null);
        String email = binding.emailInput.getText() == null ? "" : binding.emailInput.getText().toString().trim();
        String password = binding.passwordInput.getText() == null ? "" : binding.passwordInput.getText().toString();
        if (email.isEmpty()) { binding.emailInput.setError(getString(R.string.error_email_required)); return; }
        if (password.isEmpty()) { binding.passwordInput.setError(getString(R.string.error_password_required)); return; }
        binding.signInButton.setEnabled(false);
        ((FinalTermApplication) requireActivity().getApplication()).getAppContainer()
                .getAuthRepository().signIn(email, password)
                .addOnCompleteListener(task -> {
                    if (!task.isSuccessful()) {
                        String message = task.getException() == null
                                ? getString(R.string.error_sign_in)
                                : mapError(task.getException().getMessage());
                        binding.errorText.setText(message);
                        binding.errorText.setVisibility(View.VISIBLE);
                    }
                    binding.signInButton.setEnabled(true);
                });
    }

    private void renderState(AuthState state) {
        if (state == null) return;
        boolean loading = state.getStatus() == AuthState.Status.LOADING;
        binding.signInButton.setEnabled(!loading);
        binding.progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (state.getStatus() == AuthState.Status.SETUP_REQUIRED) {
            binding.errorText.setVisibility(View.VISIBLE);
            binding.errorText.setText(getString(R.string.production_configuration_required));
            binding.signInButton.setEnabled(false);
        }
        if (state.getStatus() == AuthState.Status.ERROR) {
            binding.errorText.setVisibility(View.VISIBLE);
            binding.errorText.setText(state.getMessage());
        } else if (!loading) {
            binding.errorText.setVisibility(View.GONE);
        }
    }

    private String mapError(String raw) {
        if (raw == null) return getString(R.string.error_sign_in);
        if (raw.contains("PROFILE_NOT_FOUND")) return getString(R.string.error_profile_missing);
        if (raw.contains("PROFILE_NOT_ALLOWED")) return getString(R.string.error_profile_denied);
        if (raw.contains("FIREBASE_SETUP_REQUIRED")) return getString(R.string.production_configuration_required);
        return getString(R.string.error_sign_in);
    }

    @Override
    public void onDestroyView() { super.onDestroyView(); binding = null; }
}

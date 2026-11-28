package com.example.final_term.ui.auth;

import android.os.Bundle;
import android.util.Patterns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import com.example.final_term.auth.AuthRepository;
import com.example.final_term.core.FinalTermApplication;
import com.example.final_term.databinding.FragmentResetPasswordBinding;
import com.google.firebase.auth.FirebaseAuthInvalidUserException;

public final class ResetPasswordFragment extends Fragment {
    public static final String ARG_EMAIL = "email";

    private FragmentResetPasswordBinding binding;
    private AuthRepository authRepository;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle state) {
        binding = FragmentResetPasswordBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle state) {
        FinalTermApplication app = (FinalTermApplication) requireActivity().getApplication();
        authRepository = app.getAppContainer().getAuthRepository();
        if (getArguments() != null) binding.resetEmailInput.setText(getArguments().getString(ARG_EMAIL, ""));
        binding.resetBackButton.setOnClickListener(v -> NavHostFragment.findNavController(this).navigateUp());
        binding.resetBackToLoginButton.setOnClickListener(v -> NavHostFragment.findNavController(this).navigateUp());
        binding.resetSendLinkButton.setOnClickListener(v -> sendLink());
    }

    private void sendLink() {
        var form = binding;
        String email = form.resetEmailInput.getText() == null
                ? "" : form.resetEmailInput.getText().toString().trim();
        form.resetErrorText.setVisibility(View.GONE);
        form.resetConfirmation.setVisibility(View.GONE);
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            form.resetEmailInput.setError("Vui lòng nhập email hợp lệ.");
            return;
        }
        form.resetEmailInput.setError(null);
        form.resetSendLinkButton.setEnabled(false);
        form.resetProgress.setVisibility(View.VISIBLE);
        authRepository.sendPasswordResetEmail(email).addOnCompleteListener(task -> {
            if (binding != form) return;
            form.resetSendLinkButton.setEnabled(true);
            form.resetProgress.setVisibility(View.GONE);
            if (task.isSuccessful() || task.getException() instanceof FirebaseAuthInvalidUserException) {
                form.resetConfirmation.setVisibility(View.VISIBLE);
            } else {
                form.resetErrorText.setText("Chưa gửi được link. Vui lòng thử lại sau.");
                form.resetErrorText.setVisibility(View.VISIBLE);
            }
        });
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }
}

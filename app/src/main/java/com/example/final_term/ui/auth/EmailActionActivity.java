package com.example.final_term.ui.auth;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.example.final_term.BuildConfig;
import com.example.final_term.MainActivity;
import com.example.final_term.auth.AuthRepository;
import com.example.final_term.core.FinalTermApplication;
import com.example.final_term.databinding.ActivityEmailActionBinding;
import com.google.android.gms.tasks.Task;
import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.auth.ActionCodeResult;
import com.google.firebase.auth.ActionCodeUrl;
import com.google.firebase.auth.FirebaseAuthWeakPasswordException;

public final class EmailActionActivity extends AppCompatActivity {
    public static final class ActionState extends ViewModel {
        Task<Void> verifyEmail;
        Task<String> checkReset;
        Task<Void> resetPassword;
    }

    private ActivityEmailActionBinding binding;
    private ActionState state;
    private AuthRepository auth;
    private String code;
    private String resetEmail;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityEmailActionBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (view, insets) -> {
            var bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        binding.returnToApp.setOnClickListener(v -> returnToApp(null));
        binding.savePassword.setOnClickListener(v -> submitPassword());

        auth = ((FinalTermApplication) getApplication()).getAppContainer().getAuthRepository();
        state = new ViewModelProvider(this).get(ActionState.class);

        Uri uri = getIntent().getData();
        if (!Intent.ACTION_VIEW.equals(getIntent().getAction()) || uri == null
                || !"https".equals(uri.getScheme()) || !BuildConfig.FIREBASE_AUTH_HOST.equalsIgnoreCase(uri.getHost())
                || !"/__/auth/links".equals(uri.getPath())) {
            showInvalidLink();
            return;
        }

        ActionCodeUrl action = ActionCodeUrl.parseLink(uri.toString());
        if (action == null) {
            showInvalidLink();
            return;
        }
        code = action.getCode();
        if (action.getOperation() == ActionCodeResult.VERIFY_EMAIL) {
            binding.emailActionTitle.setText("Xác minh email");
            setWorking(true);
            if (state.verifyEmail == null) state.verifyEmail = auth.applyEmailVerification(code);
        } else if (action.getOperation() == ActionCodeResult.PASSWORD_RESET) {
            binding.emailActionTitle.setText("Đặt lại mật khẩu");
            setWorking(true);
            if (state.checkReset == null) state.checkReset = auth.verifyPasswordResetCode(code);
        } else {
            showInvalidLink();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (state.verifyEmail != null) {
            state.verifyEmail.addOnCompleteListener(this, task -> {
                if (task.isSuccessful()) returnToApp("Email đã được xác minh.");
                else showLinkFailure(task.getException());
            });
        } else if (state.checkReset != null) {
            state.checkReset.addOnCompleteListener(this, task -> {
                if (!task.isSuccessful()) {
                    showLinkFailure(task.getException());
                    return;
                }
                resetEmail = task.getResult();
                binding.emailActionMessage.setText("Nhập mật khẩu mới cho " + resetEmail + ".");
                binding.newPasswordFields.setVisibility(View.VISIBLE);
                if (state.resetPassword == null) setWorking(false);
                else observePasswordReset();
            });
        }
    }

    private void submitPassword() {
        String password = valueOf(binding.newPasswordInput);
        binding.emailActionError.setVisibility(View.GONE);
        binding.newPasswordInput.setError(null);
        binding.confirmPasswordInput.setError(null);
        if (password.length() < 8) {
            binding.newPasswordInput.setError("Mật khẩu cần ít nhất 8 ký tự.");
            return;
        }
        if (!password.equals(valueOf(binding.confirmPasswordInput))) {
            binding.confirmPasswordInput.setError("Mật khẩu nhập lại chưa khớp.");
            return;
        }
        setWorking(true);
        state.resetPassword = auth.confirmPasswordReset(code, password);
        observePasswordReset();
    }

    private void observePasswordReset() {
        state.resetPassword.addOnCompleteListener(this, task -> {
            if (task.isSuccessful()) {
                if (resetEmail != null && resetEmail.equalsIgnoreCase(auth.currentEmail())) auth.signOut();
                returnToApp("Đã đổi mật khẩu. Hãy đăng nhập bằng mật khẩu mới.");
            } else {
                state.resetPassword = null;
                if (task.getException() instanceof FirebaseAuthWeakPasswordException) {
                    setWorking(false);
                    binding.newPasswordInput.setError("Mật khẩu chưa đáp ứng yêu cầu bảo mật.");
                } else if (task.getException() instanceof FirebaseNetworkException) {
                    setWorking(false);
                    binding.emailActionError.setText("Chưa thể kết nối. Hãy thử lại.");
                    binding.emailActionError.setVisibility(View.VISIBLE);
                } else {
                    showInvalidLink();
                }
            }
        });
    }

    private void showLinkFailure(Exception error) {
        if (error instanceof FirebaseNetworkException) {
            setWorking(false);
            binding.emailActionMessage.setText("Chưa thể kết nối. Hãy kiểm tra mạng rồi mở lại link trong email.");
        } else {
            showInvalidLink();
        }
    }

    private void showInvalidLink() {
        setWorking(false);
        binding.newPasswordFields.setVisibility(View.GONE);
        binding.emailActionMessage.setText("Link không hợp lệ, đã hết hạn hoặc đã được dùng. Hãy mở app để yêu cầu link mới.");
    }

    private void setWorking(boolean working) {
        binding.emailActionProgress.setVisibility(working ? View.VISIBLE : View.GONE);
        binding.savePassword.setEnabled(!working);
        binding.returnToApp.setEnabled(!working);
    }

    private void returnToApp(String message) {
        if (message != null) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }

    private static String valueOf(EditText input) {
        return input.getText() == null ? "" : input.getText().toString();
    }
}

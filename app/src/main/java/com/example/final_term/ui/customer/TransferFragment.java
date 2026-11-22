package com.example.final_term.ui.customer;

import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.example.final_term.BuildConfig;
import com.example.final_term.R;
import com.example.final_term.core.FinalTermApplication;
import com.example.final_term.data.BackendApiClient;
import com.example.final_term.data.ReadModelResult;
import com.example.final_term.data.ReadModels;
import com.example.final_term.data.ReadSummaryFormatter;
import com.example.final_term.databinding.FragmentTransferBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.PhoneAuthCredential;
import com.google.firebase.auth.PhoneAuthOptions;
import com.google.firebase.auth.PhoneAuthProvider;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class TransferFragment extends Fragment {
    private FragmentTransferBinding binding;
    private BackendApiClient api;
    private String intentId;
    private FirebaseAuth demoPhoneAuth;
    private String phoneVerificationId;
    private boolean busy;
    private boolean completed;
    private final List<String> sourceIds = new ArrayList<>();
    private CountDownTimer otpTimer;

    private enum Step { INPUT, CONFIRM, OTP, SUCCESS }
    private Step currentStep = Step.INPUT;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle state) {
        binding = FragmentTransferBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        FinalTermApplication app = (FinalTermApplication) requireActivity().getApplication();
        api = app.getAppContainer().getBackendApiClient();

        binding.backButton.setOnClickListener(v -> handleBackNavigation());

        // Quick amount chips
        binding.chip500k.setOnClickListener(v -> appendAmount(500000));
        binding.chip1m.setOnClickListener(v -> appendAmount(1000000));
        binding.chip5m.setOnClickListener(v -> appendAmount(5000000));

        // Step 1 -> Step 2
        binding.btnProceedToConfirm.setOnClickListener(v -> validateAndProceedToConfirm());

        // Step 2 -> Step 1 (Edit)
        binding.btnBackToEdit.setOnClickListener(v -> showStep(Step.INPUT));

        // Step 2 Confirm -> Create Intent & Request OTP
        binding.submitButton.setOnClickListener(v -> createIntent());

        // Step 3 OTP -> Verify OTP & Commit
        binding.verifyButton.setOnClickListener(v -> verifyOtp());
        binding.commitButton.setOnClickListener(v -> commit());

        // Step 4 Success Actions
        binding.btnSuccessHome.setOnClickListener(v -> {
            Navigation.findNavController(v).popBackStack(R.id.customerHome, false);
        });

        binding.btnShareReceipt.setOnClickListener(v -> {
            String receipt = "Biên lai chuyển khoản Nimbus Bank\n" +
                    "Mã giao dịch: " + intentId + "\n" +
                    "Số tiền: " + ReadSummaryFormatter.money(getCurrentAmount()) + "\n" +
                    "Người nhận: " + text(binding.targetInput.getText());
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_TEXT, receipt);
            startActivity(Intent.createChooser(share, "Chia sẻ biên lai giao dịch"));
        });

        binding.qrScanShortcut.setOnClickListener(v -> {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Quét mã QR chuyển khoản")
                    .setMessage("Đang chờ kích hoạt camera scan VietQR.")
                    .setPositiveButton("Đóng", null)
                    .show();
        });

        setBusy(false);
        showStep(Step.INPUT);

        app.getAppContainer().getAuthRepository().observeAuthState().observe(getViewLifecycleOwner(), auth -> {
            if (auth == null || auth.getUser() == null) return;
            app.getAppContainer().getReadRepository().observeAccounts(auth.getUser().getUid())
                    .observe(getViewLifecycleOwner(), this::renderAccounts);
        });
    }

    private void handleBackNavigation() {
        if (currentStep == Step.CONFIRM) {
            showStep(Step.INPUT);
        } else if (currentStep == Step.OTP) {
            showStep(Step.CONFIRM);
        } else if (currentStep == Step.SUCCESS) {
            Navigation.findNavController(requireView()).popBackStack(R.id.customerHome, false);
        } else {
            Navigation.findNavController(requireView()).navigateUp();
        }
    }

    private void showStep(Step step) {
        currentStep = step;
        if (binding == null) return;

        binding.stepInputContainer.setVisibility(step == Step.INPUT ? View.VISIBLE : View.GONE);
        binding.stepConfirmContainer.setVisibility(step == Step.CONFIRM ? View.VISIBLE : View.GONE);
        binding.stepOtpContainer.setVisibility(step == Step.OTP ? View.VISIBLE : View.GONE);
        binding.stepSuccessContainer.setVisibility(step == Step.SUCCESS ? View.VISIBLE : View.GONE);

        if (step == Step.INPUT) {
            binding.headerTitle.setText(R.string.transfer_title);
            status("");
        } else if (step == Step.CONFIRM) {
            binding.headerTitle.setText(R.string.transfer_confirm_title);
            bindConfirmationView();
        } else if (step == Step.OTP) {
            binding.headerTitle.setText(R.string.otp_title);
            startOtpCountdown();
        } else if (step == Step.SUCCESS) {
            binding.headerTitle.setText(R.string.transfer_success_title);
            bindSuccessView();
        }
    }

    private void appendAmount(long value) {
        long current = getCurrentAmount();
        long next = current + value;
        binding.amountInput.setText(String.valueOf(next));
    }

    private long getCurrentAmount() {
        String val = text(binding.amountInput.getText());
        try {
            return Long.parseLong(val);
        } catch (Exception e) {
            return 0L;
        }
    }

    private void validateAndProceedToConfirm() {
        int sourceIndex = binding.sourceInput.getSelectedItemPosition();
        if (sourceIndex < 0 || sourceIndex >= sourceIds.size()) {
            status("Vui lòng chọn tài khoản nguồn.");
            return;
        }
        String target = text(binding.targetInput.getText());
        String amount = text(binding.amountInput.getText());
        if (target.isEmpty() || !Phase2Presentation.validRequestAmount(amount)) {
            status("Nhập tài khoản nhận và số tiền VND hợp lệ.");
            return;
        }
        showStep(Step.CONFIRM);
    }

    private void bindConfirmationView() {
        int sourceIndex = binding.sourceInput.getSelectedItemPosition();
        String sourceId = sourceIndex >= 0 && sourceIndex < sourceIds.size() ? sourceIds.get(sourceIndex) : "";
        String target = text(binding.targetInput.getText());
        String note = text(binding.noteInput.getText());
        long amount = getCurrentAmount();

        binding.confirmAmountDisplay.setText(ReadSummaryFormatter.money(amount));
        binding.confirmSourceText.setText("Tài khoản nguồn: " + sourceId);
        binding.confirmTargetText.setText("Tài khoản nhận: " + target);
        binding.confirmNoteText.setText("Nội dung: " + (note.isEmpty() ? "Chuyển tiền Nimbus" : note));
    }

    private void bindSuccessView() {
        long amount = getCurrentAmount();
        String target = text(binding.targetInput.getText());
        binding.successAmountDisplay.setText(ReadSummaryFormatter.money(amount));
        binding.receiptTxId.setText("Mã giao dịch: " + (intentId != null ? intentId : "tx_nimbus_" + System.currentTimeMillis()));
        binding.receiptTargetInfo.setText("Chuyển đến: " + target);
        binding.receiptTime.setText("Thời gian: Vừa xong · Giao dịch thành công");
    }

    private void startOtpCountdown() {
        if (otpTimer != null) otpTimer.cancel();
        otpTimer = new CountDownTimer(45000, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                if (binding != null) {
                    binding.otpResendTimer.setText(String.format(Locale.getDefault(),
                            "Gửi lại mã sau 00:%02d", millisUntilFinished / 1000));
                }
            }

            @Override
            public void onFinish() {
                if (binding != null) {
                    binding.otpResendTimer.setText("Gửi lại mã OTP ngay");
                    binding.otpResendTimer.setClickable(true);
                    binding.otpResendTimer.setOnClickListener(v -> requestOtp());
                }
            }
        }.start();
    }

    private void renderAccounts(ReadModelResult<List<ReadModels.AccountSummary>> result) {
        if (result == null || result.getData() == null || binding == null) return;
        sourceIds.clear();
        List<String> labels = new ArrayList<>();
        for (ReadModels.AccountSummary account : result.getData()) {
            if ("ACTIVE".equalsIgnoreCase(account.status) && "VND".equalsIgnoreCase(account.currency)) {
                sourceIds.add(account.id);
                labels.add(Phase2Presentation.accountTitle(account.type) + " (••" +
                        (account.id.length() > 4 ? account.id.substring(account.id.length() - 4) : account.id) +
                        ") · " + ReadSummaryFormatter.money(account.availableMinor != null ? account.availableMinor : account.balanceMinor));
            }
        }
        if (labels.isEmpty()) labels.add("Không có tài khoản nguồn khả dụng");
        binding.sourceInput.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, labels));
        updateSubmitButton();
    }

    private void createIntent() {
        int sourceIndex = binding.sourceInput.getSelectedItemPosition();
        if (busy || completed || sourceIndex < 0 || sourceIndex >= sourceIds.size()) return;
        String target = text(binding.targetInput.getText());
        String amount = text(binding.amountInput.getText());
        if (target.isEmpty() || !Phase2Presentation.validRequestAmount(amount)) {
            status("Nhập tài khoản nhận và số tiền VND hợp lệ.");
            return;
        }
        phoneVerificationId = null;
        intentId = null;
        binding.otpInput.setText("");
        FragmentTransferBinding currentView = binding;
        try {
            JSONObject body = new JSONObject()
                    .put("requestId", UUID.randomUUID().toString().replace("-", "_"))
                    .put("sourceAccountId", sourceIds.get(sourceIndex))
                    .put("destinationAccountId", target)
                    .put("amountMinor", Long.parseLong(amount))
                    .put("currency", "VND");
            setBusy(true);
            api.post("/v1/transfers/intents", body.toString())
                    .addOnSuccessListener(response -> {
                        if (binding != currentView) return;
                        if (!ok(response)) {
                            setBusy(false);
                            status(errorMessage(response, "Backend từ chối tạo giao dịch"));
                            return;
                        }
                        try {
                            intentId = new JSONObject(response.body).getString("intentId");
                            requestOtp();
                        } catch (Exception e) {
                            setBusy(false);
                            status("Phản hồi backend không hợp lệ.");
                        }
                    })
                    .addOnFailureListener(error -> {
                        if (binding != currentView) return;
                        setBusy(false);
                        status("Không thể kết nối backend.");
                    });
        } catch (Exception error) {
            setBusy(false);
            status("Dữ liệu giao dịch không hợp lệ.");
        }
    }

    private void requestOtp() {
        FragmentTransferBinding currentView = binding;
        api.post("/v1/intents/" + intentId + "/otp", "{}")
                .addOnSuccessListener(response -> {
                    if (binding != currentView) return;
                    if (!ok(response)) {
                        setBusy(false);
                        status(errorMessage(response, "Không thể yêu cầu OTP"));
                        return;
                    }
                    try {
                        JSONObject payload = new JSONObject(response.body);
                        if ("FIREBASE_PHONE_DEMO".equals(payload.optString("method"))) {
                            beginDemoPhoneVerification(payload.getString("phoneNumber"), currentView);
                        } else if (payload.optBoolean("sent")) {
                            showCodeInput("OTP đã được gửi đến số điện thoại của bạn.");
                        } else {
                            setBusy(false);
                            status("Backend trả về phương thức OTP không được hỗ trợ.");
                        }
                    } catch (Exception error) {
                        setBusy(false);
                        status("Không thể khởi tạo xác minh OTP.");
                    }
                })
                .addOnFailureListener(error -> {
                    if (binding != currentView) return;
                    setBusy(false);
                    status("Không thể kết nối backend để yêu cầu OTP.");
                });
    }

    private void beginDemoPhoneVerification(String phoneNumber, FragmentTransferBinding currentView) {
        if (!BuildConfig.DEBUG) {
            setBusy(false);
            status("OTP Firebase thử nghiệm chỉ khả dụng trong bản Debug.");
            return;
        }
        if (demoPhoneAuth == null) {
            android.content.Context context = requireContext().getApplicationContext();
            String appName = "transfer-phone-demo";
            FirebaseApp phoneApp = FirebaseApp.getApps(context).stream()
                    .filter(app -> appName.equals(app.getName())).findFirst()
                    .orElseGet(() -> FirebaseApp.initializeApp(context, FirebaseApp.getInstance().getOptions(), appName));
            demoPhoneAuth = FirebaseAuth.getInstance(phoneApp);
        }
        demoPhoneAuth.signOut();
        demoPhoneAuth.getFirebaseAuthSettings().setAppVerificationDisabledForTesting(true);
        status("Đang mở xác minh số điện thoại test Firebase…");
        PhoneAuthProvider.verifyPhoneNumber(PhoneAuthOptions.newBuilder(demoPhoneAuth)
                .setActivity(requireActivity())
                .setPhoneNumber(phoneNumber)
                .setTimeout(60L, TimeUnit.SECONDS)
                .setCallbacks(new PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                    @Override
                    public void onVerificationCompleted(@NonNull PhoneAuthCredential credential) {
                        if (binding == currentView) verifyDemoPhone(credential, currentView);
                    }

                    @Override
                    public void onVerificationFailed(@NonNull FirebaseException error) {
                        if (binding != currentView) return;
                        setBusy(false);
                        String detail = error.getMessage();
                        if (detail != null && detail.contains("until this region enabled")) {
                            status("Firebase chưa cho phép vùng của số test. Kiểm tra SMS region policy.");
                            return;
                        }
                        status("Firebase không mở được xác minh số test.");
                    }

                    @Override
                    public void onCodeSent(@NonNull String id,
                                           @NonNull PhoneAuthProvider.ForceResendingToken token) {
                        if (binding != currentView) return;
                        phoneVerificationId = id;
                        showCodeInput("Nhập mã test đã cấu hình trong Firebase Console.");
                    }
                }).build());
    }

    private void showCodeInput(String message) {
        showStep(Step.OTP);
        setBusy(false);
        status(message);
    }

    private void verifyOtp() {
        if (busy || intentId == null) return;
        String code = text(binding.otpInput.getText());
        if (!code.matches("[0-9]{6}")) {
            status("Nhập mã OTP 6 chữ số.");
            return;
        }
        setBusy(true);
        try {
            if (phoneVerificationId != null) {
                verifyDemoPhone(PhoneAuthProvider.getCredential(phoneVerificationId, code), binding);
            } else {
                sendOtpProof(new JSONObject().put("code", code), binding);
            }
        } catch (Exception error) {
            setBusy(false);
            status("Mã OTP không hợp lệ.");
        }
    }

    private void verifyDemoPhone(PhoneAuthCredential credential, FragmentTransferBinding currentView) {
        setBusy(true);
        demoPhoneAuth.signInWithCredential(credential).continueWithTask(result -> {
            if (!result.isSuccessful()) throw result.getException();
            com.google.firebase.auth.FirebaseUser user = result.getResult().getUser();
            if (user == null) throw new IllegalStateException("Missing Firebase phone user");
            return user.getIdToken(true);
        }).addOnCompleteListener(result -> {
            demoPhoneAuth.signOut();
            if (binding != currentView) return;
            try {
                if (!result.isSuccessful() || result.getResult().getToken() == null) {
                    setBusy(false);
                    status("Firebase không xác minh được mã test. Kiểm tra lại.");
                    return;
                }
                sendOtpProof(new JSONObject().put("phoneIdToken", result.getResult().getToken()), currentView);
            } catch (Exception error) {
                setBusy(false);
                status("Không thể gửi kết quả xác minh Firebase.");
            }
        });
    }

    private void sendOtpProof(JSONObject body, FragmentTransferBinding currentView) {
        api.post("/v1/intents/" + intentId + "/verify-otp", body.toString())
                .addOnCompleteListener(task -> {
                    if (binding != currentView) return;
                    if (!task.isSuccessful()) {
                        setBusy(false);
                        status("Không thể kết nối backend để xác thực OTP.");
                        return;
                    }
                    BackendApiClient.Response response = task.getResult();
                    if (ok(response)) {
                        // Automatically commit transfer after OTP verification!
                        commit();
                    } else {
                        setBusy(false);
                        status(errorMessage(response, "Backend từ chối OTP"));
                    }
                });
    }

    private void commit() {
        if (completed || intentId == null) return;
        FragmentTransferBinding currentView = binding;
        setBusy(true);
        status("Đang hoàn tất giao dịch…");
        api.post("/v1/transfers/" + intentId + "/commit", "{}")
                .addOnCompleteListener(task -> {
                    if (binding != currentView) return;
                    setBusy(false);
                    if (!task.isSuccessful()) {
                        status("Chưa nhận được kết quả. Bấm xác nhận lại để kiểm tra.");
                        return;
                    }
                    BackendApiClient.Response response = task.getResult();
                    completed = ok(response);
                    if (completed) {
                        showStep(Step.SUCCESS);
                    } else {
                        status(errorMessage(response, "Backend chưa xác nhận giao dịch"));
                    }
                });
    }

    private boolean ok(BackendApiClient.Response response) {
        return response.statusCode >= 200 && response.statusCode < 300;
    }

    private String errorMessage(BackendApiClient.Response response, String prefix) {
        String code = "";
        try {
            code = new JSONObject(response.body).optString("error");
        } catch (Exception ignored) { }
        return code.isEmpty() ? prefix + " (HTTP " + response.statusCode + ")." : prefix + " (" + code + ").";
    }

    private void setBusy(boolean busy) {
        this.busy = busy;
        updateSubmitButton();
        if (binding != null) {
            binding.verifyButton.setEnabled(!busy && !completed);
            binding.commitButton.setEnabled(!busy && !completed);
        }
    }

    private void updateSubmitButton() {
        if (binding == null) return;
        boolean editable = !busy && !completed;
        binding.submitButton.setEnabled(editable && !sourceIds.isEmpty());
        binding.btnProceedToConfirm.setEnabled(editable);
        binding.sourceInput.setEnabled(editable);
        binding.targetInput.setEnabled(editable);
        binding.amountInput.setEnabled(editable);
    }

    private void status(String message) {
        if (binding != null) binding.statusText.setText(message);
    }

    private static String text(CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }

    @Override
    public void onDestroyView() {
        if (otpTimer != null) {
            otpTimer.cancel();
            otpTimer = null;
        }
        binding = null;
        intentId = null;
        phoneVerificationId = null;
        sourceIds.clear();
        completed = false;
        super.onDestroyView();
    }
}

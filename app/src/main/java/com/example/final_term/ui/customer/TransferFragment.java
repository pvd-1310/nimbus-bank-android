package com.example.final_term.ui.customer;

import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.example.final_term.R;
import com.example.final_term.core.FinalTermApplication;
import com.example.final_term.data.BackendApiClient;
import com.example.final_term.data.ReadModelResult;
import com.example.final_term.data.ReadModels;
import com.example.final_term.data.ReadSummaryFormatter;
import com.example.final_term.databinding.FragmentTransferBinding;
import com.example.final_term.domain.DomainDtos.OtpVerification;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import androidx.activity.OnBackPressedCallback;

import com.example.final_term.ui.ekyc.FaceCamera;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class TransferFragment extends Fragment {
    private FragmentTransferBinding binding;
    private BackendApiClient api;
    private String intentId;
    private String requestId;
    private String payloadKey;
    private String challengeId;
    private boolean faceRequired;
    private boolean faceVerified;
    private boolean commitPending;
    private long authoritativeAmount;
    private long authoritativeFee;
    private String authoritativeCurrency = "VND";
    private String authoritativeTarget = "";
    private final FaceCamera faceCamera = new FaceCamera(this, this::verifyFace, this::faceError);
    private boolean busy;
    private boolean completed;
    private final List<String> sourceIds = new ArrayList<>();
    private CountDownTimer otpTimer;

    private enum Step { INPUT, CONFIRM, OTP, SUCCESS }
    private Step currentStep = Step.INPUT;

    @Override
    public void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        faceCamera.restore(state);
        if (state == null) return;
        intentId = state.getString("intentId");
        requestId = state.getString("requestId");
        payloadKey = state.getString("payloadKey");
        challengeId = state.getString("challengeId");
        faceRequired = state.getBoolean("faceRequired");
        commitPending = state.getBoolean("commitPending");
        authoritativeAmount = state.getLong("authoritativeAmount");
        authoritativeFee = state.getLong("authoritativeFee");
        authoritativeCurrency = state.getString("authoritativeCurrency", "VND");
        authoritativeTarget = state.getString("authoritativeTarget", "");
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle state) {
        super.onSaveInstanceState(state);
        faceCamera.save(state);
        state.putString("intentId", intentId);
        state.putString("requestId", requestId);
        state.putString("payloadKey", payloadKey);
        state.putString("challengeId", challengeId);
        state.putBoolean("faceRequired", faceRequired);
        state.putBoolean("commitPending", commitPending);
        state.putLong("authoritativeAmount", authoritativeAmount);
        state.putLong("authoritativeFee", authoritativeFee);
        state.putString("authoritativeCurrency", authoritativeCurrency);
        state.putString("authoritativeTarget", authoritativeTarget);
    }

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
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(),
                new OnBackPressedCallback(true) {
                    @Override public void handleOnBackPressed() { handleBackNavigation(); }
                });

        // Quick amount chips
        binding.chip500k.setOnClickListener(v -> appendAmount(500000));
        binding.chip1m.setOnClickListener(v -> appendAmount(1000000));
        binding.chip5m.setOnClickListener(v -> appendAmount(5000000));

        // Step 1 -> Step 2
        binding.btnProceedToConfirm.setOnClickListener(v -> validateAndProceedToConfirm());

        // Step 2 -> Step 1 (Edit)
        binding.btnBackToEdit.setOnClickListener(v -> cancelForEdit());

        // Step 2 Confirm -> Create Intent & Request OTP
        binding.submitButton.setOnClickListener(v -> createIntent());

        // Step 3 OTP -> Verify OTP & Commit
        binding.otpResendTimer.setOnClickListener(v -> requestOtp());
        binding.verifyButton.setOnClickListener(v -> verifyOtp());
        binding.commitButton.setOnClickListener(v -> recover());
        binding.faceStepUpButton.setOnClickListener(v -> {
            if (busy) return;
            setBusy(true);
            faceCamera.open();
        });

        // Step 4 Success Actions
        binding.btnSuccessHome.setOnClickListener(v -> {
            Navigation.findNavController(v).popBackStack(R.id.customerHome, false);
        });

        binding.btnShareReceipt.setOnClickListener(v -> {
            String receipt = "Biên lai chuyển khoản Nimbus Bank\n" +
                    "Mã giao dịch: " + intentId + "\n" +
                    "Số tiền: " + ReadSummaryFormatter.money(receiptAmount()) + "\n" +
                    "Người nhận: " + receiptTarget();
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
        showStep(intentId == null ? Step.INPUT : Step.OTP);
        if (intentId != null) recover();

        app.getAppContainer().getAuthRepository().observeAuthState().observe(getViewLifecycleOwner(), auth -> {
            if (auth == null || auth.getUser() == null) return;
            app.getAppContainer().getReadRepository().observeAccounts(auth.getUser().getUid())
                    .observe(getViewLifecycleOwner(), this::renderAccounts);
        });
    }

    private void handleBackNavigation() {
        if (busy) return;
        if (commitPending && !completed) {
            status("Chưa có kết quả cuối cùng. Bấm kiểm tra lại trước khi rời.");
            return;
        }
        if (currentStep == Step.CONFIRM || currentStep == Step.OTP) cancelForEdit();
        else if (currentStep == Step.SUCCESS)
            Navigation.findNavController(requireView()).popBackStack(R.id.customerHome, false);
        else Navigation.findNavController(requireView()).navigateUp();
    }

    private void cancelForEdit() {
        if (busy || commitPending) return;
        if (intentId == null) {
            showStep(Step.INPUT);
            return;
        }
        FragmentTransferBinding view = binding;
        setBusy(true);
        api.post("/intents/" + intentId + "/cancel", "{}").addOnCompleteListener(task -> {
            if (binding != view) return;
            setBusy(false);
            if (!task.isSuccessful() || !ok(task.getResult())) {
                status("Chưa hủy được yêu cầu cũ. Kiểm tra kết nối rồi thử lại.");
                return;
            }
            intentId = null;
            requestId = null;
            challengeId = null;
            faceVerified = false;
            binding.demoCode.setText("");
            binding.otpInput.setText("");
            showStep(Step.INPUT);
        });
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
            renderAuthorization();
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
        if (!target.matches("(?:0[0-9]{9}|\\+84[0-9]{9})") || !Phase2Presentation.validRequestAmount(amount)) {
            status("Nhập số điện thoại Việt Nam 10 chữ số và số tiền VND hợp lệ.");
            return;
        }
        showStep(Step.CONFIRM);
    }

    private void bindConfirmationView() {
        int sourceIndex = binding.sourceInput.getSelectedItemPosition();
        String sourceId = sourceIndex >= 0 && sourceIndex < sourceIds.size() ? sourceIds.get(sourceIndex) : "";
        String target = text(binding.targetInput.getText());
        String note = text(binding.noteInput.getText());
        long amount = authoritativeAmount > 0 ? authoritativeAmount : getCurrentAmount();
        String targetText = !authoritativeTarget.isEmpty() ? authoritativeTarget : target;

        binding.confirmAmountDisplay.setText(ReadSummaryFormatter.money(amount) + " " + authoritativeCurrency);
        binding.confirmSourceText.setText("Tài khoản nguồn: " + sourceId);
        binding.confirmTargetText.setText("Tài khoản nhận: " + targetText + "\nPhí: " +
                (authoritativeFee == 0 ? "0 " + authoritativeCurrency : ReadSummaryFormatter.money(authoritativeFee) + " " + authoritativeCurrency) +
                " · Tổng: " + ReadSummaryFormatter.money(amount + authoritativeFee) + " " + authoritativeCurrency);
        binding.confirmNoteText.setText("Nội dung: " + (note.isEmpty() ? "Chuyển tiền Nimbus" : note));
    }

    private void bindSuccessView() {
        long amount = authoritativeAmount > 0 ? authoritativeAmount : receiptAmount();
        String target = !authoritativeTarget.isEmpty() ? authoritativeTarget : receiptTarget();
        binding.successAmountDisplay.setText(ReadSummaryFormatter.money(amount) + " " + authoritativeCurrency);
        binding.receiptTxId.setText("Mã giao dịch: " + intentId);
        binding.receiptTargetInfo.setText("Chuyển đến: " + target + " (Phí: " +
                (authoritativeFee == 0 ? "0 " + authoritativeCurrency : ReadSummaryFormatter.money(authoritativeFee) + " " + authoritativeCurrency) + ")");
        binding.receiptTime.setText("Thời gian: Vừa xong · Giao dịch thành công (Settled)");
    }

    private long receiptAmount() {
        try { return new JSONObject(payloadKey).getLong("amountMinor"); }
        catch (Exception ignored) { return getCurrentAmount(); }
    }

    private String receiptTarget() {
        try { return new JSONObject(payloadKey).getString("destinationAccountId"); }
        catch (Exception ignored) { return text(binding.targetInput.getText()); }
    }

    private void startOtpCountdown() {
        if (otpTimer != null) otpTimer.cancel();
        binding.otpResendTimer.setClickable(false);
        otpTimer = new CountDownTimer(30_000, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                if (binding != null) {
                    binding.otpResendTimer.setText(String.format(Locale.getDefault(),
                            "Tạo mã mới sau 00:%02d", millisUntilFinished / 1000));
                }
            }

            @Override
            public void onFinish() {
                if (binding != null) {
                    binding.otpResendTimer.setText("Tạo mã mới");
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
        binding.otpInput.setText("");
        FragmentTransferBinding currentView = binding;
        try {
            JSONObject body = new JSONObject()
                    .put("sourceAccountId", sourceIds.get(sourceIndex))
                    .put("destinationPhone", target)
                    .put("amountMinor", Long.parseLong(amount))
                    .put("currency", "VND");
            String payload = body.toString();
            if (!payload.equals(payloadKey) || requestId == null) {
                requestId = UUID.randomUUID().toString();
                payloadKey = payload;
            }
            body.put("requestId", requestId);
            setBusy(true);
            api.post("/transfers/intents", body.toString())
                    .addOnSuccessListener(response -> {
                        if (binding != currentView) return;
                        if (!ok(response)) {
                            setBusy(false);
                            status(errorMessage(response, "Backend từ chối tạo giao dịch"));
                            return;
                        }
                        try {
                            JSONObject result = new JSONObject(response.body);
                            intentId = result.getString("intentId");
                            faceRequired = result.getBoolean("faceRequired");
                            showStep(Step.OTP);
                            if (!"CREATED".equals(result.optString("status"))) recover();
                            else if (faceRequired) {
                                setBusy(false);
                                status("Chụp ảnh mới để xác minh khuôn mặt trước khi lấy mã.");
                            } else {
                                setBusy(false);
                                requestOtp();
                            }
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

    private void renderAuthorization() {
        boolean canEnterCode = (!faceRequired || faceVerified) && !commitPending;
        binding.faceStepUpButton.setVisibility(faceRequired && !commitPending ? View.VISIBLE : View.GONE);
        binding.faceStepUpButton.setEnabled(!busy);
        binding.otpInput.setVisibility(canEnterCode ? View.VISIBLE : View.GONE);
        binding.otpResendTimer.setVisibility(canEnterCode ? View.VISIBLE : View.GONE);
        binding.demoCode.setVisibility(canEnterCode ? View.VISIBLE : View.GONE);
        binding.verifyButton.setVisibility(canEnterCode ? View.VISIBLE : View.GONE);
        binding.verifyButton.setEnabled(!busy && challengeId != null);
        binding.commitButton.setVisibility(commitPending ? View.VISIBLE : View.GONE);
        binding.commitButton.setEnabled(!busy);
    }

    private void verifyFace(byte[] image) {
        if (binding == null || intentId == null) return;
        FragmentTransferBinding view = binding;
        setBusy(true);
        status("Đang xác minh khuôn mặt…");
        api.verifyIntentFace(intentId, image).addOnCompleteListener(task -> {
            if (binding != view) return;
            setBusy(false);
            if (!task.isSuccessful()) faceError("Không nhận được kết quả xác minh. Vui lòng thử lại.");
            else if (!ok(task.getResult())) faceError(errorMessage(task.getResult(), "Xác minh khuôn mặt thất bại"));
            else {
                faceVerified = true;
                renderAuthorization();
                recover();
            }
        });
    }

    private void faceError(String message) {
        if (binding == null) return;
        setBusy(false);
        status(message);
    }

    private void requestOtp() {
        if (busy || intentId == null || commitPending || (faceRequired && !faceVerified)) return;
        FragmentTransferBinding view = binding;
        setBusy(true);
        status("Đang tạo mã xác nhận demo…");
        api.post("/intents/" + intentId + "/otp", "{}").addOnCompleteListener(task -> {
            if (binding != view) return;
            setBusy(false);
            if (!task.isSuccessful()) {
                startOtpCountdown();
                status("Chưa nhận được mã. Chờ rồi tạo mã mới.");
                return;
            }
            BackendApiClient.Response response = task.getResult();
            if (!ok(response)) {
                status(errorMessage(response, "Không thể tạo mã"));
                return;
            }
            try {
                JSONObject result = new JSONObject(response.body);
                if (!"DEMO_DISPLAY_CODE".equals(result.getString("method"))) throw new IllegalStateException();
                challengeId = result.getString("challengeId");
                binding.demoCode.setText(result.getString("code"));
                binding.otpInput.setText("");
                startOtpCountdown();
                renderAuthorization();
                status("Nhập mã đang hiển thị để xác nhận.");
            } catch (Exception error) {
                status("Phản hồi backend không hợp lệ.");
            }
        });
    }

    private void verifyOtp() {
        if (busy || intentId == null || challengeId == null) return;
        String code = text(binding.otpInput.getText());
        if (!code.matches("[0-9]{6}")) {
            status("Nhập mã xác nhận gồm 6 chữ số.");
            return;
        }
        FragmentTransferBinding view = binding;
        setBusy(true);
        api.verifyIntentOtp(new OtpVerification(intentId, challengeId, code)).addOnCompleteListener(task -> {
            if (binding != view) return;
            if (!task.isSuccessful() || task.getResult().statusCode >= 500) {
                commitPending = true;
                recover();
            } else if (ok(task.getResult())) {
                commit();
            } else {
                setBusy(false);
                if ("OTP_USED".equals(errorCode(task.getResult()))) recover();
                else status(errorMessage(task.getResult(), "Backend từ chối mã"));
            }
        });
    }

    private void recover() {
        if (intentId == null || completed) return;
        FragmentTransferBinding view = binding;
        setBusy(true);
        binding.otpResendTimer.setText("Tạo mã mới");
        binding.otpResendTimer.setOnClickListener(v -> requestOtp());
        status("Đang kiểm tra trạng thái giao dịch…");
        api.get("/intents/" + intentId).addOnCompleteListener(task -> {
            if (binding != view) return;
            setBusy(false);
            if (!task.isSuccessful() || !ok(task.getResult())) {
                commitPending = true;
                renderAuthorization();
                status("Chưa nhận được trạng thái. Bấm kiểm tra lại.");
                return;
            }
            try {
                JSONObject result = new JSONObject(task.getResult().body);
                String state = result.getString("status");
                authoritativeAmount = result.optLong("amountMinor", authoritativeAmount);
                authoritativeFee = result.optLong("feeMinor", authoritativeFee);
                authoritativeCurrency = result.optString("currency", authoritativeCurrency);
                JSONObject tgt = result.optJSONObject("target");
                if (tgt != null) authoritativeTarget = tgt.optString("id", authoritativeTarget);

                if ("COMMITTED".equals(state)) {
                    finishTransfer();
                    return;
                }
                faceRequired = result.optBoolean("faceRequired");
                faceVerified = "VERIFIED".equals(result.optString("faceStatus"));
                String challenge = result.optString("challengeStatus");
                if ("CREATED".equals(state) && "VERIFIED".equals(challenge) && (!faceRequired || faceVerified)) {
                    commit();
                    return;
                }
                commitPending = "COMMIT_PENDING".equals(state);
                renderAuthorization();
                if ("COMMIT_PENDING".equals(state)) {
                    status("Trạng thái: COMMIT_PENDING — Giao dịch đang chờ xác nhận ghi sổ. Bấm kiểm tra lại.");
                } else if (!"CREATED".equals(state)) {
                    status("Giao dịch " + state + ". Quay lại để tạo giao dịch mới.");
                } else if ("VERIFIED".equals(challenge)) {
                    status("Chụp lại khuôn mặt để tiếp tục giao dịch đã xác nhận bằng mã.");
                } else if (faceRequired && !faceVerified) {
                    status("Giao dịch vượt hạn mức: cần chụp ảnh mới để xác minh khuôn mặt (nhận diện ảnh tĩnh demo).");
                } else if ("REQUIRED".equals(challenge)) {
                    requestOtp();
                } else if ("EXPIRED".equals(challenge)) {
                    status("Mã xác nhận đã hết hạn (OTP_EXPIRED). Tạo mã mới.");
                } else if ("LOCKED".equals(challenge)) {
                    status("Giao dịch bị khóa do nhập sai quá 5 lần (OTP_LOCKED). Cần hủy và tạo lại.");
                } else {
                    status("Trạng thái mã: " + challenge + ". Tạo mã mới nếu mã cũ không còn hiển thị.");
                }
            } catch (Exception error) {
                commitPending = true;
                renderAuthorization();
                status("Chưa đọc được trạng thái. Bấm kiểm tra lại.");
            }
        });
    }

    private void commit() {
        if (completed || intentId == null) return;
        FragmentTransferBinding view = binding;
        commitPending = true;
        setBusy(true);
        binding.otpInput.setText("");
        binding.demoCode.setText("");
        status("Đang hoàn tất giao dịch…");
        api.post("/transfers/" + intentId + "/commit", "{}").addOnCompleteListener(task -> {
            if (binding != view) return;
            setBusy(false);
            if (!task.isSuccessful() || task.getResult().statusCode >= 500) {
                status("Chưa có kết quả cuối cùng từ hệ thống (COMMIT_PENDING). Bấm kiểm tra lại.");
                return;
            }
            BackendApiClient.Response response = task.getResult();
            if (ok(response)) {
                try {
                    if ("SETTLED".equals(new JSONObject(response.body).getString("status"))) {
                        finishTransfer();
                        return;
                    }
                } catch (Exception ignored) { }
                status("Chưa đọc được kết quả. Bấm kiểm tra lại.");
                return;
            }
            commitPending = false;
            if ("FACE_REQUIRED".equals(errorCode(response)) || "STEP_UP_UNAVAILABLE".equals(errorCode(response))) {
                faceRequired = true;
                faceVerified = false;
            }
            renderAuthorization();
            status(errorMessage(response, "Backend từ chối ghi sổ"));
        });
    }

    private void finishTransfer() {
        commitPending = false;
        completed = true;
        showStep(Step.SUCCESS);
        status("");
    }

    private boolean ok(BackendApiClient.Response response) {
        return response.statusCode >= 200 && response.statusCode < 300;
    }

    private String errorCode(BackendApiClient.Response response) {
        try { return new JSONObject(response.body).optString("error"); }
        catch (Exception ignored) { return ""; }
    }

    private String errorMessage(BackendApiClient.Response response, String prefix) {
        String code = errorCode(response);
        switch (code) {
            case "INVALID_OTP": return "Mã xác nhận sai. Vui lòng kiểm tra lại.";
            case "OTP_EXPIRED": return "Mã xác nhận đã hết hạn (OTP_EXPIRED). Tạo mã mới để tiếp tục.";
            case "OTP_LOCKED": return "Đã nhập sai quá 5 lần, giao dịch bị khóa (OTP_LOCKED). Quay lại và tạo yêu cầu mới.";
            case "OTP_USED": return "Mã đã được xác minh. Kiểm tra trạng thái giao dịch.";
            case "RATE_LIMITED": return "Yêu cầu quá nhanh (RATE_LIMITED). Vui lòng chờ rồi thử lại.";
            case "EXPIRED":
            case "INTENT_CHANGED": return "Yêu cầu đã hết hạn hoặc thay đổi. Quay lại để xác nhận lại.";
            case "FACE_REQUIRED": return "Cần chụp lại khuôn mặt (ảnh tĩnh demo) trước khi ghi sổ.";
            case "FACE_SETUP_REQUIRED":
            case "STEP_UP_UNAVAILABLE": return "Máy chủ chưa sẵn sàng xác minh khuôn mặt.";
            case "FACE_MISMATCH": return "Ảnh không khớp với hồ sơ eKYC đã duyệt.";
            case "FACE_NOT_FOUND":
            case "MULTIPLE_FACES":
            case "FACE_LOW_QUALITY": return "Chụp một khuôn mặt rõ nét, đủ sáng trong khung hình.";
            default: return code.isEmpty() ? prefix + " (HTTP " + response.statusCode + ")." : prefix + " (" + code + ").";
        }
    }

    private void setBusy(boolean busy) {
        this.busy = busy;
        updateSubmitButton();
        if (binding != null) renderAuthorization();
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
        sourceIds.clear();
        completed = false;
        busy = false;
        super.onDestroyView();
    }
}

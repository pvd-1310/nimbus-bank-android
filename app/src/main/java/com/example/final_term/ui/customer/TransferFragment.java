package com.example.final_term.ui.customer;

import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.example.final_term.R;
import com.example.final_term.BuildConfig;
import com.example.final_term.core.FinalTermApplication;
import com.example.final_term.data.BackendApiClient;
import com.example.final_term.data.ReadModelResult;
import com.example.final_term.data.ReadModels;
import com.example.final_term.data.ReadSummaryFormatter;
import com.example.final_term.data.ExternalBankCatalog;
import com.example.final_term.databinding.FragmentTransferBinding;
import com.example.final_term.domain.DomainDtos.OtpVerification;
import com.example.final_term.ui.common.ThemedDialogBuilder;
import com.stripe.android.PaymentConfiguration;
import com.stripe.android.paymentsheet.PaymentSheet;
import com.stripe.android.paymentsheet.PaymentSheetResult;

import androidx.activity.OnBackPressedCallback;

import com.example.final_term.ui.ekyc.FaceCamera;
import org.json.JSONObject;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class TransferFragment extends Fragment {
    private com.example.final_term.ui.common.TransactionSheet authorizationSheet;
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
    private Long authoritativeBalanceAfter;
    private String authoritativeCurrency = "VND";
    private String authoritativeTarget = "";
    private String recipientAccountId;
    private String recipientName = "";
    private final FaceCamera faceCamera = new FaceCamera(this, this::verifyFace, this::faceError);
    private boolean busy;
    private boolean external;
    private String externalBankCode = "";
    private String cashOperation;
    private String initialCashAccount;
    private String initialCashAmount;
    private boolean cashArgumentsApplied;
    private String providerReference = "";
    private ReadModelResult<List<ReadModels.AccountSummary>> lastAccounts;
    private String confirmedSource;
    private String customerUid;
    private boolean restoredPending;
    private boolean storedPendingCommit;
    private boolean completed;
    private final List<String> sourceIds = new ArrayList<>();
    private CountDownTimer otpTimer;
    private com.example.final_term.ui.transaction.OtpBottomSheetDialog sharedOtp;
    private PaymentSheet paymentSheet;
    private int topupPollsRemaining;

    private enum Step { INPUT, CONFIRM, OTP, SUCCESS }
    private Step currentStep = Step.INPUT;

    @Override
    public void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        faceCamera.restore(state);
        cashOperation = getArguments() == null ? null : getArguments().getString("demoCashOperation");
        initialCashAccount = getArguments() == null ? null : getArguments().getString("accountId");
        initialCashAmount = getArguments() == null ? null : getArguments().getString("amountMinor");
        if (state == null) return;
        intentId = state.getString("intentId");
        requestId = state.getString("requestId");
        payloadKey = state.getString("payloadKey");
        challengeId = state.getString("challengeId");
        faceRequired = state.getBoolean("faceRequired");
        commitPending = state.getBoolean("commitPending");
        external = state.getBoolean("external");
        externalBankCode = state.getString("externalBankCode", "");
        confirmedSource = state.getString("confirmedSource");
        authoritativeAmount = state.getLong("authoritativeAmount");
        authoritativeFee = state.getLong("authoritativeFee");
        if (state.containsKey("authoritativeBalanceAfter")) authoritativeBalanceAfter = state.getLong("authoritativeBalanceAfter");
        authoritativeCurrency = state.getString("authoritativeCurrency", "VND");
        authoritativeTarget = state.getString("authoritativeTarget", "");
        recipientAccountId = state.getString("recipientAccountId");
        recipientName = state.getString("recipientName", "");
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
        state.putBoolean("external", external);
        state.putString("externalBankCode", externalBankCode);
        state.putString("confirmedSource", confirmedSource);
        state.putLong("authoritativeAmount", authoritativeAmount);
        state.putLong("authoritativeFee", authoritativeFee);
        if (authoritativeBalanceAfter != null) state.putLong("authoritativeBalanceAfter", authoritativeBalanceAfter);
        state.putString("authoritativeCurrency", authoritativeCurrency);
        state.putString("authoritativeTarget", authoritativeTarget);
        state.putString("recipientAccountId", recipientAccountId);
        state.putString("recipientName", recipientName);
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
        faceCamera.attach();
        FinalTermApplication app = (FinalTermApplication) requireActivity().getApplication();
        api = app.getAppContainer().getBackendApiClient();
        if (BuildConfig.STRIPE_PUBLISHABLE_KEY.startsWith("pk_test_")) {
            PaymentConfiguration.init(requireContext(), BuildConfig.STRIPE_PUBLISHABLE_KEY);
            paymentSheet = new PaymentSheet.Builder(this::onPaymentSheetResult).build(this);
        }

        binding.backButton.setOnClickListener(v -> handleBackNavigation());
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(),
                new OnBackPressedCallback(true) {
                    @Override public void handleOnBackPressed() { handleBackNavigation(); }
                });

        binding.tabInternal.setOnClickListener(v -> switchMode(false));
        binding.tabExternal.setOnClickListener(v -> switchMode(true));
        setupExternalBankPicker();
        updateMode();

        // Quick amount chips
        binding.chip500k.setOnClickListener(v -> appendAmount(500000));
        binding.chip1m.setOnClickListener(v -> appendAmount(1000000));
        binding.chip5m.setOnClickListener(v -> appendAmount(5000000));
        loadRecentRecipients();

        // Step 1 -> Step 2
        binding.btnProceedToConfirm.setOnClickListener(v -> validateAndProceedToConfirm());

        // Step 2 -> Step 1 (Edit)
        binding.btnBackToEdit.setOnClickListener(v -> cancelForEdit());

        // Step 2 Confirm -> Create Intent & Request OTP
        binding.submitButton.setOnClickListener(v -> createIntent());

        // Step 3 OTP -> Verify OTP & Commit
        binding.otpResendTimer.setOnClickListener(v -> requestOtp());
        binding.otpClose.setOnClickListener(v -> handleBackNavigation());
        binding.commitButton.setOnClickListener(v -> {
            if (cardTopup() && commitPending) createTopupPayment();
            else recover();
        });
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
            String receipt = "Biên lai giao dịch Nimbus Bank\n" +
                    "Số tiền: " + ReadSummaryFormatter.formatCurrency(receiptAmount(), authoritativeCurrency) + "\n" +
                    "Người nhận: " + receiptTarget();
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_TEXT, receipt);
            startActivity(Intent.createChooser(share, "Chia sẻ biên lai giao dịch"));
        });

        setBusy(false);
        showStep(intentId == null ? Step.INPUT : Step.OTP);
        app.getAppContainer().getAuthRepository().observeAuthState().observe(getViewLifecycleOwner(), auth -> {
            if (auth == null || auth.getUser() == null) return;
            String uid = auth.getUser().getUid();
            if (uid.equals(customerUid)) return;
            customerUid = uid;
            app.getAppContainer().getReadRepository().observeAccounts(uid)
                    .observe(getViewLifecycleOwner(), this::renderAccounts);
            if (!restoredPending) {
                restoredPending = true;
                restorePending();
                if (intentId != null) {
                    showStep(Step.OTP);
                    recover();
                } else if (requestId != null && payloadKey != null) recoverCreation();
            }
        });
    }

    private android.content.SharedPreferences pendingPreferences() {
        return requireContext().getSharedPreferences("pending_bank_transfer", android.content.Context.MODE_PRIVATE);
    }

    private String pendingKey() {
        return customerUid + ":" + (cashOperation == null ? "transfer" : cashOperation);
    }

    private void restorePending() {
        String key = pendingKey();
        var preferences = pendingPreferences();
        String savedIntent = preferences.getString(key, null);
        if (savedIntent == null) return;
        storedPendingCommit = true;
        if (intentId == null) {
            intentId = savedIntent;
            external = preferences.getBoolean(key + ":external", false);
            if (requestId == null) requestId = preferences.getString(key + ":requestId", null);
            if (confirmedSource == null) confirmedSource = preferences.getString(key + ":source", null);
            commitPending = true;
        }
    }

    private boolean rememberPending() {
        if (customerUid == null || intentId == null) return false;
        String key = pendingKey();
        var editor = pendingPreferences().edit()
                .putString(key, intentId)
                .putBoolean(key + ":external", external);
        if (requestId != null) editor.putString(key + ":requestId", requestId);
        if (confirmedSource != null) editor.putString(key + ":source", confirmedSource);
        storedPendingCommit = editor.commit();
        return storedPendingCommit;
    }

    private void forgetPending() {
        if (!storedPendingCommit || customerUid == null) return;
        String key = pendingKey();
        var preferences = pendingPreferences();
        if (intentId != null && intentId.equals(preferences.getString(key, null))) {
            preferences.edit()
                    .remove(key)
                    .remove(key + ":external")
                    .remove(key + ":requestId")
                    .remove(key + ":source")
                    .apply();
        }
        storedPendingCommit = false;
    }

    private boolean demoMode() {
        return external || "WITHDRAWAL".equals(cashOperation);
    }

    private boolean cardTopup() {
        return "DEPOSIT".equals(cashOperation);
    }

    private String demoLabel() {
        if (external) return "Chuyển tiền đến ngân hàng khác";
        return cardTopup() ? "Nạp tiền vào tài khoản" : "Rút tiền";
    }

    private void switchMode(boolean useExternal) {
        if (busy || requestId != null || cashOperation != null || external == useExternal) return;
        external = useExternal;
        binding.targetInput.setText("");
        updateMode();
        renderAccounts(lastAccounts);
    }

    private void updateMode() {
        if (cashOperation != null) {
            ((View) binding.tabInternal.getParent()).setVisibility(View.GONE);
            ((View) binding.targetInput.getParent()).setVisibility(View.GONE);
        }
        binding.tabExternal.setSelected(external);
        binding.tabInternal.setSelected(!external);
        binding.tabExternal.setBackgroundResource(external ? R.drawable.navy_button_pill : android.R.color.transparent);
        binding.tabInternal.setBackgroundResource(external ? android.R.color.transparent : R.drawable.navy_button_pill);
        binding.tabExternal.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), external ? R.color.white : R.color.ink_muted));
        binding.tabInternal.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), external ? R.color.ink_muted : R.color.white));
        binding.tabExternal.setText("Ngân hàng khác");
        binding.externalBankCard.setVisibility(external && cashOperation == null ? View.VISIBLE : View.GONE);
        binding.targetInput.setHint(external ? "Số tài khoản nhận"
                : "Số điện thoại người nhận");
        binding.targetInput.setInputType(external ? android.text.InputType.TYPE_CLASS_NUMBER
                : android.text.InputType.TYPE_CLASS_PHONE);
        binding.headerTitle.setText(demoMode() ? demoLabel() : getString(R.string.transfer_title));
    }

    private void setupExternalBankPicker() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                R.layout.item_nimbus_spinner, ExternalBankCatalog.names());
        adapter.setDropDownViewResource(R.layout.item_nimbus_dropdown);
        binding.externalBankSpinner.setAdapter(adapter);
        binding.externalBankSpinner.setSelection(ExternalBankCatalog.positionForCode(externalBankCode), false);
        binding.externalBankSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String[] codes = ExternalBankCatalog.codes();
                if (position >= 0 && position < codes.length) externalBankCode = codes[position];
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    private boolean validTarget(String target) {
        if (cashOperation != null) return true;
        return external ? target.matches("0{9}[12]") : target.matches("(?:0[0-9]{9}|\\+84[0-9]{9})");
    }

    private void handleBackNavigation() {
        if (busy) return;
        if (commitPending && !completed) {
            if (storedPendingCommit) Navigation.findNavController(requireView()).navigateUp();
            else status("Chưa có kết quả cuối cùng. Bấm kiểm tra lại.");
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
            requestId = null;
            payloadKey = null;
            recipientAccountId = null;
            recipientName = "";
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
            forgetPending();
            intentId = null;
            requestId = null;
            payloadKey = null;
            recipientAccountId = null;
            recipientName = "";
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
        dismissSharedOtp();
        if (authorizationSheet != null) {
            authorizationSheet.close();
            authorizationSheet = null;
        }

        binding.stepInputContainer.setVisibility(step == Step.INPUT ? View.VISIBLE : View.GONE);
        binding.stepConfirmContainer.setVisibility(step == Step.CONFIRM || step == Step.OTP ? View.VISIBLE : View.GONE);
        binding.submitButton.setEnabled(step == Step.CONFIRM && !busy);
        binding.btnBackToEdit.setEnabled(step == Step.CONFIRM && !busy);
        binding.stepSuccessContainer.setVisibility(step == Step.SUCCESS ? View.VISIBLE : View.GONE);

        if (step == Step.INPUT) {
            confirmedSource = null;
            updateMode();
            status("");
        } else if (step == Step.CONFIRM) {
            binding.headerTitle.setText(demoMode() ? demoLabel() : getString(R.string.transfer_confirm_title));
            bindConfirmationView();
        } else if (step == Step.OTP) {
            binding.headerTitle.setText(R.string.transfer_confirm_title);
            renderAuthorization();
        } else if (step == Step.SUCCESS) {
            binding.headerTitle.setText("Biên lai giao dịch");
            bindSuccessView();
        }
        updateSubmitButton();
    }

    private void appendAmount(long value) {
        long current;
        try { current = Long.parseLong(text(binding.amountInput.getText())); }
        catch (NumberFormatException ignored) { current = 0; }
        long next = current + value;
        binding.amountInput.setText(String.valueOf(next));
    }

    private long getCurrentAmount() {
        String val = text(binding.amountInput.getText());
        try {
            return new java.math.BigDecimal(val).longValueExact();
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
        if (!validTarget(target) || (!Phase2Presentation.validRequestAmount(amount) || getCurrentAmount() <= 0)) {
            status(external ? "Nhập số tài khoản nhận và số tiền hợp lệ."
                    : "Kiểm tra số điện thoại người nhận và số tiền VND.");
            return;
        }
        if (external && externalBankCode.isEmpty()) {
            status("Vui lòng chọn ngân hàng nhận.");
            return;
        }
        confirmedSource = sourceIds.get(sourceIndex);
        if (!Phase2Presentation.requiresRecipientLookup(cashOperation, external)) {
            navigateToTransactionFlow(target, getCurrentAmount());
            return;
        }
        var view = binding;
        setBusy(true);
        status("Đang tìm người nhận…");
        try {
            String body = new JSONObject().put("sourceAccountId", confirmedSource)
                    .put("destinationPhone", target).toString();
            api.post("/transfers/recipient", body).addOnCompleteListener(task -> {
                if (binding != view) return;
                setBusy(false);
                if (!task.isSuccessful() || !ok(task.getResult())) {
                    status(task.isSuccessful()
                            ? errorMessage(task.getResult(), "Chưa tìm được người nhận")
                            : "Chưa kết nối được để tìm người nhận. Vui lòng thử lại.");
                    return;
                }
                try {
                    JSONObject recipient = new JSONObject(task.getResult().body);
                    recipientAccountId = recipient.getString("accountId");
                    recipientName = recipient.optString("fullName");
                    status("");
                    navigateToTransactionFlow(target, getCurrentAmount());
                } catch (JSONException error) {
                    status("Chưa đọc được thông tin người nhận. Vui lòng thử lại.");
                }
            });
        } catch (JSONException error) {
            setBusy(false);
            status("Dữ liệu người nhận không hợp lệ.");
        }
    }

    private void loadRecentRecipients() {
        if (api == null || binding == null || cashOperation != null) return;
        var view = binding;
        api.get("/transfers/recent-recipients").addOnCompleteListener(task -> {
            if (binding != view || !task.isSuccessful() || task.getResult() == null
                    || task.getResult().statusCode != 200) return;
            try {
                org.json.JSONArray items = new org.json.JSONObject(task.getResult().body).optJSONArray("items");
                if (items == null || items.length() == 0) {
                    binding.recentRecipientsSection.setVisibility(View.GONE);
                    return;
                }
                binding.recentRecipientsContainer.removeAllViews();
                float density = getResources().getDisplayMetrics().density;
                int padH = Math.round(12 * density);
                int padV = Math.round(8 * density);
                int marginEnd = Math.round(8 * density);
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    String name = item.optString("name", "").trim();
                    String target = item.optString("target", "").trim();
                    if (name.isEmpty() || target.isEmpty()) continue;
                    String displayPhone = target.startsWith("+84") ? "0" + target.substring(3) : target;
                    android.widget.TextView pill = new android.widget.TextView(requireContext());
                    pill.setText(name);
                    pill.setTextSize(12f);
                    pill.setTypeface(null, android.graphics.Typeface.BOLD);
                    pill.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.navy));
                    pill.setBackgroundResource(R.drawable.glass_pill_bg);
                    pill.setPadding(padH, padV, padH, padV);
                    android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.setMarginEnd(marginEnd);
                    pill.setOnClickListener(v -> {
                        if (external) switchMode(false);
                        binding.targetInput.setText(displayPhone);
                    });
                    binding.recentRecipientsContainer.addView(pill, lp);
                }
                binding.recentRecipientsSection.setVisibility(
                        binding.recentRecipientsContainer.getChildCount() > 0 ? View.VISIBLE : View.GONE);
            } catch (Exception ignored) { }
        });
    }

    private void navigateToTransactionFlow(String target, long amountMinor) {
        if (binding == null) return;
        String note = text(binding.noteInput.getText());
        com.example.final_term.ui.transaction.TransactionFlowRequest flowRequest;
        if (cashOperation != null) {
            flowRequest = cardTopup()
                    ? com.example.final_term.ui.transaction.TransactionFlowRequest.deposit(confirmedSource, amountMinor, "VND")
                    : com.example.final_term.ui.transaction.TransactionFlowRequest.withdrawal(confirmedSource, amountMinor, "VND");
        } else if (external) {
            flowRequest = com.example.final_term.ui.transaction.TransactionFlowRequest.externalTransfer(
                    confirmedSource,
                    amountMinor,
                    "VND",
                    externalBankCode,
                    ExternalBankCatalog.nameForCode(externalBankCode),
                    "Hội sở chính",
                    target,
                    note);
        } else {
            flowRequest = com.example.final_term.ui.transaction.TransactionFlowRequest.internalTransfer(
                    confirmedSource,
                    amountMinor,
                    "VND",
                    recipientAccountId,
                    recipientName,
                    target,
                    note);
        }
        Navigation.findNavController(requireView()).navigate(R.id.transactionFlow, flowRequest.toBundle());
    }

    private void bindConfirmationView() {
        String sourceId = confirmedSource == null ? "" : confirmedSource;
        String target = text(binding.targetInput.getText());
        String note = text(binding.noteInput.getText());
        long amount = authoritativeAmount > 0 ? authoritativeAmount : getCurrentAmount();
        String targetText = external ? ExternalBankCatalog.nameForCode(externalBankCode) + " · " + target
                : demoMode() ? (!authoritativeTarget.isEmpty() ? authoritativeTarget : target)
                : recipientName.isEmpty() ? target : recipientName + " · " + target;

        binding.confirmAmountDisplay.setText(ReadSummaryFormatter.formatCurrency(amount, authoritativeCurrency));
        binding.confirmAmountLabel.setText(cardTopup() ? "Tổng tiền nạp" : cashOperation != null
                ? "WITHDRAWAL".equals(cashOperation) ? "Tổng tiền rút" : "Tổng tiền nạp"
                : "Tổng số tiền chuyển");
        binding.confirmFeeLabel.setText("Phí dịch vụ: " + ReadSummaryFormatter.formatCurrency(authoritativeFee, authoritativeCurrency)
                + (authoritativeFee == 0 ? " (Miễn phí)" : ""));
        binding.confirmSourceText.setText((cardTopup() ? "Tài khoản nhận: " : "Tài khoản nguồn: ") + sourceId);
        String recipient = cardTopup() ? "Nguồn thanh toán: thẻ"
                : cashOperation != null ? "Tài khoản đối ứng: Nimbus Bank"
                : external ? "Ngân hàng nhận: " + ExternalBankCatalog.nameForCode(externalBankCode)
                        + "\nTài khoản nhận: " + target
                : "Tài khoản nhận: " + targetText;
        binding.confirmTargetText.setText(recipient + "\nPhí: " +
                ReadSummaryFormatter.formatCurrency(authoritativeFee, authoritativeCurrency) +
                " · Tổng: " + ReadSummaryFormatter.formatCurrency(amount + authoritativeFee, authoritativeCurrency));
        binding.confirmNoteText.setText(cardTopup() ? "Số dư sẽ được cập nhật sau khi thanh toán hoàn tất."
                : external ? "Giao dịch sẽ được xử lý sau khi bạn xác nhận."
                : "Số dư sẽ được cập nhật sau khi bạn xác nhận giao dịch."
                + (note.isEmpty() ? "" : "\nNội dung: " + note));
    }

    private void bindSuccessView() {
        long amount = authoritativeAmount > 0 ? authoritativeAmount : receiptAmount();
        String target = external ? receiptTarget()
                : demoMode() && !authoritativeTarget.isEmpty() ? authoritativeTarget : receiptTarget();
        binding.successAmountDisplay.setText(ReadSummaryFormatter.formatCurrency(amount, authoritativeCurrency));
        binding.receiptTxId.setVisibility(View.VISIBLE);
        binding.receiptTxId.setText("Mã giao dịch: " +
                (providerReference.isEmpty() ? (intentId == null ? "—" : intentId) : providerReference));
        binding.successHeadline.setText(cardTopup() ? "Tiền đã được ghi có vào tài khoản"
                : "WITHDRAWAL".equals(cashOperation) ? "Rút tiền thành công" : "Chuyển khoản thành công");
        binding.receiptTargetInfo.setText((cardTopup() ? "Tài khoản nhận: " : "WITHDRAWAL".equals(cashOperation) ? "Tài khoản đã rút từ: " : "Chuyển đến: ") + target + " (Phí: " +
                ReadSummaryFormatter.formatCurrency(authoritativeFee, authoritativeCurrency) + ")");
        binding.receiptTime.setText(authoritativeBalanceAfter == null ? "Giao dịch đã được xác nhận."
                : "Số dư mới: " + ReadSummaryFormatter.formatCurrency(authoritativeBalanceAfter, authoritativeCurrency));
    }

    private long receiptAmount() {
        try { return new JSONObject(payloadKey).getLong("amountMinor"); }
        catch (Exception ignored) { return getCurrentAmount(); }
    }

    private String receiptTarget() {
        try {
            JSONObject payload = new JSONObject(payloadKey);
            if (external) return ExternalBankCatalog.nameForCode(payload.optString("bankCode"))
                    + " · " + payload.optString("beneficiaryAccount");
            if (cardTopup()) return confirmedSource == null ? "Tài khoản Nimbus" : confirmedSource;
            if ("WITHDRAWAL".equals(cashOperation)) return confirmedSource == null ? "Tài khoản thanh toán" : confirmedSource;
            if (cashOperation != null) return "Tài khoản Nimbus Bank";
            String phone = payload.optString("destinationPhone", payload.optString("destinationAccountId"));
            return recipientName.isEmpty() ? phone : recipientName + " · " + phone;
        }
        catch (Exception ignored) { return text(binding.targetInput.getText()); }
    }

    private void startOtpCountdown() {
        if (otpTimer != null) otpTimer.cancel();
        if (sharedOtp != null) sharedOtp.setCountdownText("Gửi lại sau 00:30", false);
        binding.otpResendTimer.setClickable(false);
        otpTimer = new CountDownTimer(30_000, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                if (binding != null) {
                    if (sharedOtp != null) sharedOtp.setCountdownText(String.format(Locale.getDefault(), "Gửi lại sau 00:%02d", millisUntilFinished / 1000), false);
                    binding.otpResendTimer.setText(String.format(Locale.getDefault(),
                            "Tạo mã mới sau 00:%02d", millisUntilFinished / 1000));
                }
            }

            @Override
            public void onFinish() {
                if (binding != null) {
                    if (sharedOtp != null) sharedOtp.setCountdownText("Gửi lại mã OTP", true);
                    binding.otpResendTimer.setText("Tạo mã mới");
                    binding.otpResendTimer.setClickable(true);
                    binding.otpResendTimer.setOnClickListener(v -> requestOtp());
                }
            }
        }.start();
    }

    private void renderAccounts(ReadModelResult<List<ReadModels.AccountSummary>> result) {
        if (result == null || binding == null) return;
        lastAccounts = result;
        if (result.getData() == null) {
            sourceIds.clear();
            updateSubmitButton();
            if (currentStep == Step.INPUT) status("Chưa thể tải tài khoản. Vui lòng thử lại.");
            return;
        }
        if (currentStep == Step.INPUT) status(result.getStatus() == ReadModelResult.Status.EMPTY
                ? "Chưa có tài khoản khả dụng." : result.isReadOnly()
                ? "Đang hiển thị dữ liệu đã lưu trên thiết bị." : "");
        sourceIds.clear();
        List<String> labels = new ArrayList<>();
        for (ReadModels.AccountSummary account : result.getData()) {
            if ("ACTIVE".equalsIgnoreCase(account.status) && "CHECKING".equals(account.type)) {
                sourceIds.add(account.id);
                labels.add(account.displayAccountNumber() + " · " + account.currency);
            }
        }
        if (labels.isEmpty()) labels.add("Không có tài khoản nguồn khả dụng");
        ArrayAdapter<String> sourceAdapter = new ArrayAdapter<>(requireContext(),
                R.layout.item_nimbus_spinner, labels);
        sourceAdapter.setDropDownViewResource(R.layout.item_nimbus_dropdown);
        binding.sourceInput.setAdapter(sourceAdapter);
        if (!cashArgumentsApplied && cashOperation != null) {
            int selected = sourceIds.indexOf(initialCashAccount);
            if (selected >= 0) binding.sourceInput.setSelection(selected);
            if (initialCashAmount != null) binding.amountInput.setText(initialCashAmount);
            cashArgumentsApplied = true;
        }
        updateSubmitButton();
    }

    private void createIntent() {
        if (busy || completed) return;
        if (requestId != null && payloadKey != null) {
            recoverCreation();
            return;
        }
        if (confirmedSource == null) return;
        String target = text(binding.targetInput.getText());
        String amount = text(binding.amountInput.getText());
        if (!validTarget(target) || (!Phase2Presentation.validRequestAmount(amount) || getCurrentAmount() <= 0)) {
            status("Nhập tài khoản nhận và số tiền VND hợp lệ.");
            return;
        }
        binding.otpInput.setText("");
        try {
            JSONObject body = new JSONObject()
                    .put(demoMode() || cardTopup() ? "accountId" : "sourceAccountId", confirmedSource)
                    .put("amountMinor", getCurrentAmount())
                    .put("currency", "VND");
            if (external) body.put("bankCode", externalBankCode).put("beneficiaryAccount", target);
            else if (cashOperation != null && !cardTopup()) body.put("type", cashOperation);
            else if (Phase2Presentation.requiresRecipientLookup(cashOperation, external)) {
                body.put("destinationPhone", target).put("recipientAccountId", recipientAccountId);
            }
            String payload = body.toString();
            if (!payload.equals(payloadKey) || requestId == null) {
                requestId = UUID.randomUUID().toString();
                payloadKey = payload;
                postIntent();
            } else recoverCreation();
        } catch (JSONException error) {
            status("Dữ liệu giao dịch không hợp lệ.");
        }
    }

    private void recoverCreation() {
        var view = binding;
        setBusy(true);
        status("Đang tìm yêu cầu cũ theo mã…");
        String recoveryPath = cardTopup() ? "/topup-intents/by-request/"
                : demoMode() ? "/demo-movements/by-request/" : "/transfers/by-request/";
        api.get(recoveryPath + requestId)
                .addOnCompleteListener(task -> {
                    if (binding != view) return;
                    if (task.isSuccessful() && task.getResult().statusCode == 404) {
                        postIntent();
                    } else if (task.isSuccessful() && ok(task.getResult())) {
                        acceptIntent(task.getResult());
                    } else {
                        setBusy(false);
                        showStep(Step.CONFIRM);
                        status("Chưa tra cứu được yêu cầu cũ. Bấm xác nhận để kiểm tra lại cùng mã.");
                    }
                });
    }

    private void postIntent() {
        var view = binding;
        setBusy(true);
        try {
            String endpoint = cardTopup() ? "/topups/intents"
                    : external ? "/external-transfers"
                    : cashOperation != null ? "/money-requests/intents" : "/transfers/intents";
            JSONObject body = new JSONObject(payloadKey).put("requestId", requestId);
            api.post(endpoint, body.toString()).addOnCompleteListener(task -> {
                if (binding != view) return;
                if (task.isSuccessful() && ok(task.getResult())) acceptIntent(task.getResult());
                else {
                    setBusy(false);
                    showStep(Step.CONFIRM);
                        status(task.isSuccessful() && task.getResult().statusCode < 500
                            ? errorMessage(task.getResult(), "Không thể tạo giao dịch")
                            : "Chưa xác nhận được yêu cầu. Bấm xác nhận để tra cứu cùng mã.");
                }
            });
        } catch (JSONException error) {
            setBusy(false);
            status("Dữ liệu giao dịch không hợp lệ.");
        }
    }

    private void acceptIntent(BackendApiClient.Response response) {
        try {
            JSONObject result = new JSONObject(response.body);
            intentId = result.getString("intentId");
            showStep(Step.OTP);
            recover();
        } catch (JSONException error) {
            setBusy(false);
            status("Chưa đọc được phản hồi. Kiểm tra lại cùng mã yêu cầu.");
        }
    }

    private void renderAuthorization() {
        if (commitPending) dismissSharedOtp();
        binding.faceStepUpButton.setVisibility(faceRequired && !commitPending ? View.VISIBLE : View.GONE);
        binding.faceStepUpButton.setEnabled(!busy);
        binding.otpInput.setVisibility(View.GONE);
        binding.otpResendTimer.setVisibility(View.GONE);
        binding.demoCode.setVisibility(View.GONE);
        binding.verifyButton.setVisibility(View.GONE);
        binding.commitButton.setVisibility(commitPending ? View.VISIBLE : View.GONE);
        binding.commitButton.setText(cardTopup() ? "Mở lại thanh toán thẻ" : "Kiểm tra trạng thái");
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
        if (sharedOtp == null) {
            sharedOtp = new com.example.final_term.ui.transaction.OtpBottomSheetDialog(requireContext(),
                    new com.example.final_term.ui.transaction.OtpBottomSheetDialog.Listener() {
                        @Override public void onSubmitOtp(@NonNull String code) { verifyOtp(code); }
                        @Override public void onResendRequested() { requestOtp(); }
                        @Override public void onDismissRequested() { dismissSharedOtp(); cancelForEdit(); }
                    });
        }
        sharedOtp.show();
        FragmentTransferBinding view = binding;
        setBusy(true);
        status("Đang xác thực giao dịch…");
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
                String code = result.getString("code");
                if (!code.matches("[0-9]{6}")) throw new IllegalStateException();
                if (sharedOtp != null) sharedOtp.setDemoCode(code);
                startOtpCountdown();
            } catch (Exception error) {
                status("Không thể đọc kết quả. Vui lòng thử lại.");
            }
        });
    }

    private void verifyOtp(@NonNull String code) {
        if (busy || intentId == null || challengeId == null) return;
        if (!code.matches("[0-9]{6}")) {
            setBusy(false);
            status("Không thể xác thực giao dịch. Vui lòng thử lại.");
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
                else status(errorMessage(task.getResult(), "Không thể xác nhận mã"));
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
                if (external && !result.optString("bankCode").isEmpty()) {
                    externalBankCode = result.getString("bankCode");
                    binding.externalBankSpinner.setSelection(ExternalBankCatalog.positionForCode(externalBankCode), false);
                }
                authoritativeAmount = result.optLong("amountMinor", authoritativeAmount);
                authoritativeFee = result.optLong("feeMinor", authoritativeFee);
                authoritativeCurrency = result.optString("currency", authoritativeCurrency);
                if (confirmedSource == null && !result.optString("sourceId").isEmpty()) {
                    confirmedSource = result.optString("sourceId");
                }
                if (requestId == null && !result.optString("requestId").isEmpty()) {
                    requestId = result.optString("requestId");
                }
                JSONObject tgt = result.optJSONObject("target");
                if (tgt != null) authoritativeTarget = tgt.optString("id", authoritativeTarget);

                providerReference = result.optString("providerReference", "");
                if (cardTopup()) {
                    faceRequired = result.optBoolean("faceRequired");
                    faceVerified = "VERIFIED".equals(result.optString("faceStatus"));
                    if ("COMMIT_PENDING".equals(state)) {
                        recoverTopupStatus(false);
                        return;
                    }
                }
                if (demoMode() && "COMMIT_PENDING".equals(state)) {
                    reconcileDemo();
                    return;
                }
                if ("COMMIT_PENDING".equals(state) && "STRIPE_TREASURY".equals(result.optString("provider"))) {
                    commit();
                    return;
                }
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
                    status("Giao dịch đang được xử lý. Kiểm tra lại sau ít phút.");
                } else if (!"CREATED".equals(state)) {
                    forgetPending();
                    status("Giao dịch chưa hoàn tất. Quay lại để kiểm tra thông tin và thử lại.");
                } else if ("VERIFIED".equals(challenge)) {
                    status("Chụp lại khuôn mặt để tiếp tục giao dịch đã xác nhận bằng mã.");
                } else if (faceRequired && !faceVerified) {
                    status("Giao dịch vượt hạn mức. Vui lòng chụp lại khuôn mặt để tiếp tục.");
                } else if ("REQUIRED".equals(challenge)) {
                    requestOtp();
                } else if ("EXPIRED".equals(challenge)) {
                    status("Mã xác nhận đã hết hạn. Tạo mã mới để tiếp tục.");
                } else if ("LOCKED".equals(challenge)) {
                    status("Giao dịch bị khóa do nhập sai quá 5 lần. Hủy và tạo yêu cầu mới.");
                } else {
                status("Không thể tiếp tục với mã xác nhận hiện tại. Hãy tạo mã mới để thử lại.");
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
        if (!rememberPending()) {
            setBusy(false);
            status("Chưa lưu được mã để khôi phục giao dịch. Bấm kiểm tra lại trước khi xác nhận.");
            return;
        }
        FragmentTransferBinding view = binding;
        commitPending = true;
        setBusy(true);
        binding.otpInput.setText("");
        binding.demoCode.setText("");
        if (cardTopup()) {
            createTopupPayment();
            return;
        }
        status("Đang hoàn tất giao dịch…");
        String path = demoMode() ? "/demo-movements/" : "/transfers/";
        api.post(path + intentId + "/commit", "{}").addOnCompleteListener(task -> {
            if (binding != view) return;
            setBusy(false);
            if (!task.isSuccessful() || task.getResult().statusCode >= 500) {
                status("Giao dịch vẫn đang được xử lý. Bấm kiểm tra trạng thái sau ít phút.");
                return;
            }
            BackendApiClient.Response response = task.getResult();
            if (ok(response)) {
                try {
                    JSONObject result = new JSONObject(response.body);
                    if (demoMode()) {
                        renderDemoOutcome(result);
                        return;
                    }
                    if ("SETTLED".equals(result.getString("status"))) {
                        finishTransfer();
                        return;
                    }
                    if ("FAILED".equals(result.optString("status"))) {
                        commitPending = false;
                        forgetPending();
                        renderAuthorization();
                        status("Giao dịch chưa hoàn tất. Số dư chưa thay đổi.");
                        return;
                    }
                } catch (Exception ignored) { }
                status("Chưa đọc được kết quả. Bấm kiểm tra lại.");
                return;
            }
            commitPending = false;
            forgetPending();
            if ("FACE_REQUIRED".equals(errorCode(response)) || "STEP_UP_UNAVAILABLE".equals(errorCode(response))) {
                faceRequired = true;
                faceVerified = false;
            }
            renderAuthorization();
            status(errorMessage(response, "Không thể hoàn tất giao dịch"));
        });
    }

    private void createTopupPayment() {
        if (paymentSheet == null) {
            setBusy(false);
            commitPending = false;
            forgetPending();
            renderAuthorization();
            dismissSharedOtp();
            status("Thanh toán không thành công.");
            return;
        }
        if (requestId == null || confirmedSource == null) {
            setBusy(false);
            commitPending = false;
            forgetPending();
            intentId = null;
            showStep(Step.INPUT);
            status("Phiên nạp tiền cũ không thể mở lại. Vui lòng tạo giao dịch mới.");
            return;
        }
        setBusy(true);
        status("Đang chuẩn bị thanh toán…");
        try {
            String body = new JSONObject().put("accountId", confirmedSource).put("requestId", requestId).toString();
            api.post("/topups", body).addOnCompleteListener(task -> {
                if (binding == null) return;
                setBusy(false);
                if (!task.isSuccessful() || !ok(task.getResult())) {
                    commitPending = false;
                    forgetPending();
                    renderAuthorization();
                    status("Thanh toán không thành công.");
                    return;
                }
                try {
                    JSONObject result = new JSONObject(task.getResult().body);
                    if ("SUCCEEDED".equals(result.optString("status"))) {
                        finishTransfer();
                        return;
                    }
                    String secret = result.optString("clientSecret", "");
                    if (secret.isEmpty()) {
                        recoverTopupStatus(false);
                        return;
                    }
                    status("Hoàn tất thanh toán để nạp tiền vào tài khoản.");
                    PaymentSheet.Configuration.Builder configuration = new PaymentSheet.Configuration.Builder("Nimbus Bank");
                    if (!result.isNull("customerId") && !result.isNull("customerSessionClientSecret")) {
                        configuration.customer(PaymentSheet.CustomerConfiguration.Companion.createWithCustomerSession(
                                result.getString("customerId"), result.getString("customerSessionClientSecret")));
                    }
                    paymentSheet.presentWithPaymentIntent(secret, configuration.build());
                } catch (Exception error) {
                    status("Phản hồi thanh toán không hợp lệ. Bấm kiểm tra lại.");
                }
            });
        } catch (JSONException error) {
            setBusy(false);
            status("Dữ liệu nạp tiền không hợp lệ.");
        }
    }

    private void onPaymentSheetResult(@NonNull PaymentSheetResult result) {
        if (result instanceof PaymentSheetResult.Completed) {
            status("Đang chờ xác nhận thanh toán…");
            topupPollsRemaining = 12;
            recoverTopupStatus(true);
        } else if (result instanceof PaymentSheetResult.Canceled) {
            status("Bạn đã đóng thanh toán; số dư chưa thay đổi.");
            commitPending = true;
        } else if (result instanceof PaymentSheetResult.Failed) {
            commitPending = false;
            forgetPending();
            renderAuthorization();
            status("Thanh toán không thành công.");
        }
    }

    private void recoverTopupStatus(boolean keepPolling) {
        if (intentId == null || binding == null) return;
        api.get("/topups/" + intentId).addOnCompleteListener(task -> {
            if (binding == null) return;
            if (!task.isSuccessful() || !ok(task.getResult())) {
                status("Chưa cập nhật được trạng thái thanh toán. Bấm kiểm tra lại.");
                return;
            }
            try {
                JSONObject result = new JSONObject(task.getResult().body);
                String state = result.optString("status");
                if ("SUCCEEDED".equals(state)) {
                    finishTransfer();
                } else if ("FAILED".equals(state) || "CANCELLED".equals(state)) {
                    commitPending = false;
                    forgetPending();
                    renderAuthorization();
                    status("Thanh toán không thành công.");
                } else {
                    commitPending = true;
                    status("Đang chờ xác nhận thanh toán. Số dư sẽ cập nhật sau khi hoàn tất.");
                    if (keepPolling && topupPollsRemaining-- > 0) new android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed(() -> recoverTopupStatus(true), 2500);
                }
            } catch (JSONException error) {
                status("Không đọc được trạng thái nạp tiền.");
            }
        });
    }

    private void reconcileDemo() {
        var view = binding;
        commitPending = true;
        setBusy(true);
        api.post("/demo-movements/" + intentId + "/reconcile", "{}").addOnCompleteListener(task -> {
            if (binding != view) return;
            setBusy(false);
            try {
                if (!task.isSuccessful() || !ok(task.getResult())) throw new JSONException("Unavailable");
                renderDemoOutcome(new JSONObject(task.getResult().body));
            } catch (JSONException error) {
                status("Chưa cập nhật được trạng thái giao dịch. Bấm kiểm tra lại.");
            }
        });
    }

    private void renderDemoOutcome(JSONObject result) throws JSONException {
        providerReference = result.optString("providerReference", "");
        if (!result.isNull("balanceAfterMinor")) authoritativeBalanceAfter = result.getLong("balanceAfterMinor");
        String state = result.getString("status");
        if ("COMMITTED".equals(state)) {
            finishTransfer();
            return;
        }
        commitPending = "COMMIT_PENDING".equals(state);
        if (!commitPending) forgetPending();
        renderAuthorization();
        status(commitPending ? "Giao dịch đang được xử lý. Bấm kiểm tra lại sau ít phút."
                : "Giao dịch chưa hoàn tất. Vui lòng kiểm tra thông tin và thử lại.");
    }

    private void finishTransfer() {
        forgetPending();
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
            case "INVALID_DESTINATION": return "Không tìm thấy tài khoản nhận đang hoạt động cho số điện thoại này.";
            case "RECIPIENT_CHANGED": return "Thông tin người nhận đã thay đổi. Quay lại để tra cứu lại số điện thoại.";
            case "STRIPE_SANDBOX_ACCOUNT_REQUIRED": return "Tài khoản chưa sẵn sàng cho giao dịch này.";
            case "TOP_UP_REQUIRES_CARD": return "Vui lòng thanh toán bằng thẻ để nạp tiền.";
            case "CURRENCY_NOT_SUPPORTED": return "Luồng này chỉ hỗ trợ tài khoản VND.";
            case "SETUP_REQUIRED": return "Thanh toán không thành công.";
            case "INVALID_OTP": return "Mã xác nhận sai. Vui lòng kiểm tra lại.";
            case "OTP_EXPIRED": return "Mã xác nhận đã hết hạn. Tạo mã mới để tiếp tục.";
            case "OTP_LOCKED": return "Đã nhập sai quá 5 lần, giao dịch bị khóa. Quay lại và tạo yêu cầu mới.";
            case "OTP_USED": return "Mã đã được xác minh. Kiểm tra trạng thái giao dịch.";
            case "RATE_LIMITED": return "Bạn thao tác quá nhanh. Vui lòng chờ rồi thử lại.";
            case "EXPIRED":
            case "INTENT_CHANGED": return "Yêu cầu đã hết hạn hoặc thay đổi. Quay lại để xác nhận lại.";
            case "FACE_REQUIRED": return "Vui lòng chụp lại khuôn mặt để tiếp tục.";
            case "FACE_SETUP_REQUIRED":
            case "STEP_UP_UNAVAILABLE": return "Máy chủ chưa sẵn sàng xác minh khuôn mặt.";
            case "FACE_MISMATCH": return "Ảnh không khớp với hồ sơ eKYC đã duyệt.";
            case "FACE_NOT_FOUND":
            case "MULTIPLE_FACES":
            case "FACE_LOW_QUALITY": return "Chụp một khuôn mặt rõ nét, đủ sáng trong khung hình.";
            default: return prefix + ". Vui lòng thử lại sau.";
        }
    }

    private void setBusy(boolean busy) {
        this.busy = busy;
        if (sharedOtp != null) sharedOtp.setBusy(busy);
        updateSubmitButton();
        if (binding != null) renderAuthorization();
    }

    private void updateSubmitButton() {
        if (binding == null) return;
        boolean editable = !busy && !completed;
        binding.submitButton.setEnabled(editable && (confirmedSource != null || requestId != null));
        binding.btnProceedToConfirm.setEnabled(editable);
        binding.sourceInput.setEnabled(editable);
        binding.targetInput.setEnabled(editable);
        binding.amountInput.setEnabled(editable);
    }

    private void status(String message) {
        if (sharedOtp != null && sharedOtp.isShowing()) sharedOtp.setStatusMessage(message);
        if (binding != null) binding.otpSheetStatus.setText(message);
        if (binding != null) binding.statusText.setText(message);
    }

    private static String text(CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }

    @Override
    public void onDestroyView() {
        dismissSharedOtp();
        if (authorizationSheet != null) { authorizationSheet.close(); authorizationSheet = null; }
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

    private void dismissSharedOtp() {
        if (sharedOtp != null) { sharedOtp.dismiss(); sharedOtp = null; }
    }
}

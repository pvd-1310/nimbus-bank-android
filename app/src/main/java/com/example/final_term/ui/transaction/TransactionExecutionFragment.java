package com.example.final_term.ui.transaction;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.app.AlertDialog;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.example.final_term.BuildConfig;
import com.example.final_term.R;
import com.example.final_term.core.FinalTermApplication;
import com.example.final_term.data.BackendApiClient;
import com.example.final_term.data.ReadSummaryFormatter;
import com.example.final_term.databinding.FragmentTransactionFlowBinding;
import com.example.final_term.databinding.ItemTransactionDetailRowBinding;
import com.example.final_term.domain.DomainDtos.OtpVerification;
import com.example.final_term.ui.ekyc.FaceCamera;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.example.final_term.ui.common.ThemedDialogBuilder;
import com.stripe.android.PaymentConfiguration;
import com.stripe.android.paymentsheet.PaymentSheet;
import com.stripe.android.paymentsheet.PaymentSheetResult;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Unified 3-step transaction execution coordinator (Confirmation -> OTP -> Success Receipt)
 * supporting internal/external transfers, deposits, withdrawals, bill payments, and order payments.
 */
public final class TransactionExecutionFragment extends Fragment {
    private static final String STATE_REQUEST = "tx_flow_request";
    private static final String STATE_ORDER_REQUEST_ID = "tx_order_request_id";
    private static final String STATE_RESOLVED_BILL_ID = "tx_resolved_bill_id";
    private static final String STATE_REQUEST_ID = "tx_request_id";
    private static final String STATE_INTENT_ID = "tx_intent_id";
    private static final String STATE_CHALLENGE_ID = "tx_challenge_id";
    private static final String STATE_FACE_REQUIRED = "tx_face_required";
    private static final String STATE_FACE_VERIFIED = "tx_face_verified";
    private static final String STATE_COMMIT_PENDING = "tx_commit_pending";
    private static final String STATE_COMPLETED = "tx_completed";
    private static final String STATE_AUTHORITATIVE_FEE = "tx_authoritative_fee";
    private static final String STATE_PROVIDER_REFERENCE = "tx_provider_reference";
    private static final String STATE_COMPLETED_AT_MILLIS = "tx_completed_at_millis";

    private static final String PREFS_PENDING_TRANSFER = "pending_bank_transfer";
    private static final int MAX_DEMO_RECONCILE_RETRIES = 6;
    private static final long DEMO_RECONCILE_DELAY_MS = 1500L;
    private static final int MAX_STRIPE_POLL_RETRIES = 20;
    private static final long STRIPE_POLL_DELAY_MS = 2000L;

    private FragmentTransactionFlowBinding binding;
    private BackendApiClient api;
    private PaymentSheet paymentSheet;
    private final FaceCamera faceCamera = new FaceCamera(this, this::verifyFace, this::faceError);
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TransactionFlowRequest request;
    private String orderRequestId;
    private String resolvedBillId;
    private String requestId;
    private String intentId;
    private String challengeId;
    private boolean faceRequired;
    private boolean faceVerified;
    private boolean commitPending;
    private boolean completed;
    private long authoritativeFee;
    private String providerReference;
    private long completedAtMillis;
    private String selectedPaymentMethod = "STRIPE_CARD";
    private String selectedPaymentAccountId;
    private String selectedPaymentAccountName;
    private long selectedPaymentAvailableMinor;
    private boolean accountsLoading;
    private final java.util.ArrayList<JSONObject> eligiblePaymentAccounts = new java.util.ArrayList<>();

    private String customerUid;
    private boolean storedPendingCommit;
    private boolean busy;
    private int demoReconcileRemaining;
    private int stripePollsRemaining;
    private OtpBottomSheetDialog otpDialog;
    private CountDownTimer otpTimer;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        faceCamera.restore(savedInstanceState);
        if (savedInstanceState != null) {
            request = TransactionFlowRequest.fromBundle(savedInstanceState);
            orderRequestId = savedInstanceState.getString(STATE_ORDER_REQUEST_ID);
            resolvedBillId = savedInstanceState.getString(STATE_RESOLVED_BILL_ID);
            requestId = savedInstanceState.getString(STATE_REQUEST_ID);
            intentId = savedInstanceState.getString(STATE_INTENT_ID);
            challengeId = savedInstanceState.getString(STATE_CHALLENGE_ID);
            faceRequired = savedInstanceState.getBoolean(STATE_FACE_REQUIRED);
            faceVerified = savedInstanceState.getBoolean(STATE_FACE_VERIFIED);
            commitPending = savedInstanceState.getBoolean(STATE_COMMIT_PENDING);
            completed = savedInstanceState.getBoolean(STATE_COMPLETED);
            authoritativeFee = savedInstanceState.getLong(STATE_AUTHORITATIVE_FEE);
            providerReference = savedInstanceState.getString(STATE_PROVIDER_REFERENCE);
            completedAtMillis = savedInstanceState.getLong(STATE_COMPLETED_AT_MILLIS);
            selectedPaymentMethod = savedInstanceState.getString("payment_method", "STRIPE_CARD");
            selectedPaymentAccountId = savedInstanceState.getString("payment_account_id");
            selectedPaymentAccountName = savedInstanceState.getString("payment_account_name");
            selectedPaymentAvailableMinor = savedInstanceState.getLong("payment_account_available");
        }
        if (request == null) {
            request = TransactionFlowRequest.fromBundle(getArguments());
        }
        if (resolvedBillId == null && request != null) {
            resolvedBillId = request.billId;
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        faceCamera.save(outState);
        if (request != null) {
            outState.putParcelable(TransactionFlowRequest.EXTRA_REQUEST, request);
        }
        outState.putString(STATE_ORDER_REQUEST_ID, orderRequestId);
        outState.putString(STATE_RESOLVED_BILL_ID, resolvedBillId);
        outState.putString(STATE_REQUEST_ID, requestId);
        outState.putString(STATE_INTENT_ID, intentId);
        outState.putString(STATE_CHALLENGE_ID, challengeId);
        outState.putBoolean(STATE_FACE_REQUIRED, faceRequired);
        outState.putBoolean(STATE_FACE_VERIFIED, faceVerified);
        outState.putBoolean(STATE_COMMIT_PENDING, commitPending);
        outState.putBoolean(STATE_COMPLETED, completed);
        outState.putLong(STATE_AUTHORITATIVE_FEE, authoritativeFee);
        outState.putString(STATE_PROVIDER_REFERENCE, providerReference);
        outState.putLong(STATE_COMPLETED_AT_MILLIS, completedAtMillis);
        outState.putString("payment_method", selectedPaymentMethod);
        outState.putString("payment_account_id", selectedPaymentAccountId);
        outState.putString("payment_account_name", selectedPaymentAccountName);
        outState.putLong("payment_account_available", selectedPaymentAvailableMinor);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentTransactionFlowBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (request == null) {
            showToast("Thiếu thông tin giao dịch.");
            Navigation.findNavController(view).navigateUp();
            return;
        }

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
                    @Override
                    public void handleOnBackPressed() {
                        handleBackNavigation();
                    }
                });

        binding.securityButton.setOnClickListener(v -> showSecurityInfoDialog());
        binding.btnConfirmSubmit.setOnClickListener(v -> onConfirmSubmitClicked());
        binding.cardPayStripe.setOnClickListener(v -> selectPaymentMethod("STRIPE_CARD"));
        binding.cardPayNimbus.setOnClickListener(v -> selectPaymentMethod("NIMBUS_ACCOUNT"));
        binding.btnChangeNimbusAccount.setOnClickListener(v -> {
            if (!commitPending && !completed && eligiblePaymentAccounts.size() > 1) {
                showPaymentAccountPicker();
            }
        });
        binding.btnFaceStepUp.setOnClickListener(v -> {
            if (busy) return;
            setBusy(true);
            faceCamera.open();
        });
        binding.btnPendingAction.setOnClickListener(v -> resumePendingTransaction());
        binding.successCloseButton.setOnClickListener(v -> navigateHome());
        binding.btnSuccessHome.setOnClickListener(v -> navigateHome());

        app.getAppContainer().getAuthRepository().observeAuthState().observe(getViewLifecycleOwner(), auth -> {
            if (auth != null && auth.getUser() != null) {
                customerUid = auth.getUser().getUid();
            }
        });

        if (completed) {
            renderSuccessStep();
        } else {
            renderConfirmationStep();
            if (isBillOrOrderPayment()) loadPaymentAccounts();
            if (commitPending && intentId != null) {
                showPendingCard("Giao dịch đang chờ hoàn tất thanh toán hoặc đối soát.");
                inspectIntent(false);
            }
        }
    }

    private void renderConfirmationStep() {
        if (binding == null || request == null) return;
        binding.stepConfirmContainer.setVisibility(View.VISIBLE);
        binding.stepSuccessContainer.setVisibility(View.GONE);

        refreshConfirmationDetails();

        boolean billPayment = isBillOrOrderPayment();
        binding.paymentMethodSection.setVisibility(billPayment ? View.VISIBLE : View.GONE);
        if (billPayment) renderPaymentMethodChoice();

        boolean showFace = faceRequired && !faceVerified && !commitPending && !completed;
        binding.faceStepUpCard.setVisibility(showFace ? View.VISIBLE : View.GONE);
        binding.pendingStatusCard.setVisibility(commitPending && !completed ? View.VISIBLE : View.GONE);
        updateConfirmButtonState();
    }

    private void refreshConfirmationDetails() {
        if (binding == null || request == null) return;
        TransactionPresentation.ConfirmationModel model =
                TransactionPresentation.buildConfirmation(request, authoritativeFee, selectedPaymentMethod, selectedPaymentAccountId);

        binding.confirmHeaderTitle.setText(model.headerTitle);
        binding.confirmHeroLabel.setText(model.heroLabel);
        bindHeroAmount(binding.confirmAmountValue, binding.confirmCurrencySymbol,
                request.amountMinor, request.currency, model.amountFormatted);

        binding.confirmFeeText.setText(model.feeText);
        if (model.feeFree) {
            binding.confirmFeeIcon.setVisibility(View.VISIBLE);
            binding.confirmFeeText.setTextColor(ContextCompat.getColor(requireContext(), R.color.success));
        } else {
            binding.confirmFeeIcon.setVisibility(View.GONE);
            binding.confirmFeeText.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink_muted));
        }

        populateDetailRows(binding.confirmDetailsCard, model.rows, false);
    }

    private boolean isBillOrOrderPayment() {
        return request != null && (request.kind == TransactionFlowRequest.Kind.BILL_PAYMENT
                || request.kind == TransactionFlowRequest.Kind.ORDER_PAYMENT);
    }

    private void loadPaymentAccounts() {
        if (accountsLoading || !isBillOrOrderPayment() || api == null) return;
        accountsLoading = true;
        renderPaymentMethodChoice();
        api.get("/accounts?limit=100").addOnCompleteListener(task -> {
            accountsLoading = false;
            if (binding == null || !isAdded() || !task.isSuccessful() || task.getResult() == null || !ok(task.getResult())) {
                renderPaymentMethodChoice();
                return;
            }
            try {
                JSONObject response = new JSONObject(task.getResult().body);
                JSONArray items = response.optJSONArray("items");
                eligiblePaymentAccounts.clear();
                if (items != null) for (int i = 0; i < items.length(); i++) {
                    JSONObject account = items.optJSONObject(i);
                    if (account != null && "CHECKING".equalsIgnoreCase(account.optString("type"))
                            && "ACTIVE".equalsIgnoreCase(account.optString("status"))
                            && "VND".equalsIgnoreCase(account.optString("currency"))) eligiblePaymentAccounts.add(account);
                }
                String preferredId = selectedPaymentAccountId != null ? selectedPaymentAccountId
                        : (request != null ? request.sourceAccountId : null);
                if (preferredId != null) {
                    for (JSONObject account : eligiblePaymentAccounts) {
                        if (preferredId.equals(account.optString("id"))) {
                            rememberPaymentAccount(account);
                            break;
                        }
                    }
                }
                if (eligiblePaymentAccounts.size() == 1 && selectedPaymentAccountId == null) {
                    rememberPaymentAccount(eligiblePaymentAccounts.get(0));
                }
                renderPaymentMethodChoice();
                refreshConfirmationDetails();
                updateConfirmButtonState();
            } catch (JSONException ignored) {
                renderPaymentMethodChoice();
            }
        });
    }

    private void showPaymentAccountPicker() {
        String[] labels = new String[eligiblePaymentAccounts.size()];
        for (int i = 0; i < eligiblePaymentAccounts.size(); i++) {
            JSONObject account = eligiblePaymentAccounts.get(i);
            long available = account.optLong("availableMinor", account.optLong("balanceMinor"));
            labels[i] = account.optString("name", "Tài khoản") + " · " + account.optString("id")
                    + " · khả dụng " + ReadSummaryFormatter.formatCurrency(available, "VND");
        }
        new AlertDialog.Builder(requireContext()).setTitle("Chọn tài khoản Nimbus")
                .setItems(labels, (dialog, which) -> setPaymentAccount(eligiblePaymentAccounts.get(which))).show();
    }

    private void setPaymentAccount(JSONObject account) {
        String previous = selectedPaymentAccountId;
        rememberPaymentAccount(account);
        if (previous != null && !previous.equals(selectedPaymentAccountId)) invalidatePaymentIntent();
        selectPaymentMethod("NIMBUS_ACCOUNT");
    }

    private void rememberPaymentAccount(JSONObject account) {
        selectedPaymentAccountId = account.optString("id", null);
        selectedPaymentAccountName = account.optString("name", "Tài khoản Nimbus");
        selectedPaymentAvailableMinor = account.optLong("availableMinor", account.optLong("balanceMinor"));
    }

    private void invalidatePaymentIntent() {
        if (intentId != null && api != null) api.post("/intents/" + intentId + "/cancel", "{}");
        intentId = null;
        requestId = null;
        challengeId = null;
        faceVerified = false;
        faceRequired = false;
        dismissOtpDialog();
    }

    private void selectPaymentMethod(String method) {
        if (commitPending || completed) return;
        if ("NIMBUS_ACCOUNT".equals(method) && selectedPaymentAccountId == null) {
            if (eligiblePaymentAccounts.size() > 1) showPaymentAccountPicker();
            else if (eligiblePaymentAccounts.size() == 1) setPaymentAccount(eligiblePaymentAccounts.get(0));
            else loadPaymentAccounts();
            return;
        }
        if (!method.equals(selectedPaymentMethod) && intentId != null) invalidatePaymentIntent();
        selectedPaymentMethod = method;
        renderPaymentMethodChoice();
        refreshConfirmationDetails();
        updateConfirmButtonState();
    }

    private void renderPaymentMethodChoice() {
        if (binding == null || !isBillOrOrderPayment()) return;
        boolean nimbusSelected = "NIMBUS_ACCOUNT".equals(selectedPaymentMethod);
        boolean stripeSelected = "STRIPE_CARD".equals(selectedPaymentMethod);

        binding.cardPayNimbus.setBackgroundResource(
                nimbusSelected ? R.drawable.bg_payment_option_selected : R.drawable.bg_payment_option_normal);
        binding.cardPayStripe.setBackgroundResource(
                stripeSelected ? R.drawable.bg_payment_option_selected : R.drawable.bg_payment_option_normal);

        binding.radioPayNimbus.setBackgroundResource(
                nimbusSelected ? R.drawable.bg_payment_radio_selected : R.drawable.bg_payment_radio_unselected);
        binding.checkPayNimbus.setVisibility(nimbusSelected ? View.VISIBLE : View.GONE);

        binding.radioPayStripe.setBackgroundResource(
                stripeSelected ? R.drawable.bg_payment_radio_selected : R.drawable.bg_payment_radio_unselected);
        binding.checkPayStripe.setVisibility(stripeSelected ? View.VISIBLE : View.GONE);

        if (selectedPaymentAccountId == null) {
            if (accountsLoading) {
                binding.txtPayNimbusSubtitle.setText("Đang tải tài khoản khả dụng...");
            } else if (eligiblePaymentAccounts.isEmpty()) {
                binding.txtPayNimbusSubtitle.setText("Chạm để tải hoặc chọn tài khoản");
            } else {
                binding.txtPayNimbusSubtitle.setText("Chạm để chọn tài khoản thanh toán");
            }
            binding.paymentAccountBalance.setVisibility(View.GONE);
        } else {
            String accountName = (selectedPaymentAccountName == null || selectedPaymentAccountName.trim().isEmpty())
                    ? "Tài khoản thanh toán" : selectedPaymentAccountName.trim();
            binding.txtPayNimbusSubtitle.setText(
                    accountName + " · " + TransactionPresentation.maskAccountLabel(selectedPaymentAccountId, "TK"));
            binding.paymentAccountBalance.setVisibility(View.VISIBLE);
            long remaining = selectedPaymentAvailableMinor - request.amountMinor;
            binding.paymentAccountBalance.setText("Số dư khả dụng: "
                    + ReadSummaryFormatter.formatCurrency(selectedPaymentAvailableMinor, "VND")
                    + (remaining < 0 ? " · Không đủ số dư" : ""));
            int balanceColor = remaining < 0
                    ? R.color.danger
                    : (nimbusSelected ? R.color.navy : R.color.ink_muted);
            binding.paymentAccountBalance.setTextColor(ContextCompat.getColor(requireContext(), balanceColor));
        }

        boolean canChangeAccount = eligiblePaymentAccounts.size() > 1 && !commitPending && !completed;
        binding.btnChangeNimbusAccount.setVisibility(canChangeAccount ? View.VISIBLE : View.GONE);
    }

    private void renderSuccessStep() {
        if (binding == null || request == null) return;
        dismissOtpDialog();
        binding.stepConfirmContainer.setVisibility(View.GONE);
        binding.stepSuccessContainer.setVisibility(View.VISIBLE);

        if (completedAtMillis <= 0L) {
            completedAtMillis = System.currentTimeMillis();
        }
        TransactionPresentation.ReceiptModel receipt = TransactionPresentation.buildReceipt(
                request, intentId, providerReference, authoritativeFee, completedAtMillis,
                isBillOrOrderPayment() ? selectedPaymentMethod : null, selectedPaymentAccountId);

        binding.successMiniTitle.setText(receipt.miniHeaderTitle);
        binding.successHeadline.setText(receipt.headline);
        binding.successTimestamp.setText(receipt.timestampText);
        bindHeroAmount(binding.successAmountValue, binding.successCurrencySymbol,
                request.amountMinor, request.currency, receipt.amountFormatted);

        populateDetailRows(binding.receiptDetailsCard, receipt.rows, true);

        View.OnClickListener shareListener = v -> shareReceipt(receipt.shareText);
        binding.successShareTopButton.setOnClickListener(shareListener);
        binding.btnShareReceipt.setOnClickListener(shareListener);

        binding.successPulseRing.animate()
                .scaleX(1.22f)
                .scaleY(1.22f)
                .alpha(0f)
                .setDuration(1100L)
                .withEndAction(() -> {
                    if (binding != null) {
                        binding.successPulseRing.setScaleX(1f);
                        binding.successPulseRing.setScaleY(1f);
                        binding.successPulseRing.setAlpha(0.45f);
                    }
                })
                .start();
    }

    private void bindHeroAmount(@NonNull TextView amountView, @NonNull TextView symbolView,
                                long amountMinor, @NonNull String currency, @NonNull String fallbackFormatted) {
        if ("VND".equalsIgnoreCase(currency)) {
            amountView.setText(NumberFormat.getIntegerInstance(new Locale("vi", "VN")).format(amountMinor));
            symbolView.setText("₫");
            symbolView.setVisibility(View.VISIBLE);
        } else {
            amountView.setText(fallbackFormatted);
            symbolView.setVisibility(View.GONE);
        }
    }

    private void populateDetailRows(@NonNull LinearLayout container,
                                    @NonNull List<TransactionPresentation.DetailRow> rows,
                                    boolean isReceipt) {
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (int i = 0; i < rows.size(); i++) {
            TransactionPresentation.DetailRow row = rows.get(i);
            ItemTransactionDetailRowBinding rowBinding =
                    ItemTransactionDetailRowBinding.inflate(inflater, container, false);

            rowBinding.rowDivider.setVisibility(i == 0 ? View.GONE : View.VISIBLE);
            rowBinding.rowLabel.setText(row.label);
            rowBinding.rowValue.setText(row.value);

            if (!isReceipt && row.subtitle != null && !row.subtitle.isEmpty()) {
                rowBinding.rowSubtitle.setText(row.subtitle);
                rowBinding.rowSubtitle.setVisibility(View.VISIBLE);
            } else {
                rowBinding.rowSubtitle.setVisibility(View.GONE);
            }

            if (row.walletIcon && !isReceipt) {
                rowBinding.rowLeadingIcon.setImageResource(R.drawable.ic_nav_wallet);
                rowBinding.rowLeadingIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.navy));
                rowBinding.rowLeadingIcon.setVisibility(View.VISIBLE);
            } else if (row.bankIcon && isReceipt) {
                rowBinding.rowLeadingIcon.setImageResource(R.drawable.ic_account_balance);
                rowBinding.rowLeadingIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.success));
                rowBinding.rowLeadingIcon.setVisibility(View.VISIBLE);
            } else {
                rowBinding.rowLeadingIcon.setVisibility(View.GONE);
            }

            rowBinding.rowValue.setBackground(null);
            rowBinding.rowValue.setPadding(0, 0, 0, 0);
            if (row.monospace) {
                rowBinding.rowValue.setTypeface(Typeface.MONOSPACE);
            } else if (row.methodPill || ("Trạng thái".equals(row.label) && "Hoàn tất".equals(row.value))) {
                rowBinding.rowValue.setTypeface(Typeface.DEFAULT_BOLD);
            } else {
                rowBinding.rowValue.setTypeface(Typeface.DEFAULT);
            }

            if (row.methodPill) {
                rowBinding.rowValue.setText(row.value);
                rowBinding.rowValue.setTextColor(ContextCompat.getColor(requireContext(), R.color.navy));
                rowBinding.rowVerifiedBadge.setVisibility(View.GONE);
            } else if (isReceipt && "Phí dịch vụ".equals(row.label) && row.value.contains("Miễn phí")) {
                rowBinding.rowValue.setText("0 ₫ (Miễn phí)");
                rowBinding.rowValue.setTextColor(ContextCompat.getColor(requireContext(), R.color.success));
                rowBinding.rowVerifiedBadge.setVisibility(View.GONE);
            } else if ("Trạng thái".equals(row.label) && "Hoàn tất".equals(row.value)) {
                rowBinding.rowValue.setTextColor(ContextCompat.getColor(requireContext(), R.color.success));
                rowBinding.rowVerifiedBadge.setVisibility(View.GONE);
            } else {
                rowBinding.rowValue.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink));
                rowBinding.rowVerifiedBadge.setVisibility(!isReceipt && row.verifiedBadge ? View.VISIBLE : View.GONE);
            }

            if (row.copyValue != null && !row.copyValue.isEmpty()) {
                rowBinding.rowCopyIcon.setVisibility(View.VISIBLE);
                View.OnClickListener copyClick = v -> copyToClipboard(row.copyValue);
                rowBinding.rowValueContainer.setOnClickListener(copyClick);
                rowBinding.rowCopyIcon.setOnClickListener(copyClick);
            } else {
                rowBinding.rowCopyIcon.setVisibility(View.GONE);
            }

            container.addView(rowBinding.getRoot());
        }
    }

    private void onConfirmSubmitClicked() {
        if (busy || completed || request == null) return;
        if (commitPending && intentId != null) {
            resumePendingTransaction();
            return;
        }
        if (intentId != null) {
            inspectIntent(true);
            return;
        }
        if (request.kind == TransactionFlowRequest.Kind.ORDER_PAYMENT && resolvedBillId == null) {
            createOrRecoverOrder();
        } else {
            createOrRecoverIntent();
        }
    }

    private void createOrRecoverOrder() {
        if (busy || request == null) return;
        setBusy(true);
        boolean recovering = orderRequestId != null;
        if (!recovering) {
            orderRequestId = UUID.randomUUID().toString();
        }
        try {
            JSONObject body = new JSONObject()
                    .put("requestId", orderRequestId)
                    .put("productId", request.productId)
                    .put("quantity", 1)
                    .put("expectedAmountMinor", request.amountMinor)
                    .put("expectedCurrency", request.currency);
            String details = request.orderCustomerDetails != null ? request.orderCustomerDetails
                    : request.targetNumber != null ? request.targetNumber
                    : request.serviceName != null ? "Dịch vụ trải nghiệm · " + request.serviceName : null;
            if (details != null) {
                body.put("customerDetails", details);
            }

            Task<BackendApiClient.Response> task = recovering
                    ? api.get("/orders/by-request/" + orderRequestId).continueWithTask(t -> {
                        if (!t.isSuccessful()) throw t.getException();
                        return t.getResult().statusCode == 404
                                ? api.post("/orders", body.toString())
                                : Tasks.forResult(t.getResult());
                    })
                    : api.post("/orders", body.toString());

            task.addOnCompleteListener(done -> {
                if (binding == null || !isAdded()) return;
                setBusy(false);
                if (!done.isSuccessful() || done.getResult() == null) {
                    showToast("Chưa xác nhận được đơn hàng. Vui lòng thử lại.");
                    return;
                }
                BackendApiClient.Response res = done.getResult();
                if (!ok(res)) {
                    if ("INVALID_TOPUP_PHONE".equals(errorCode(res))) {
                        orderRequestId = null;
                        showToast("Số điện thoại nhận nạp không hợp lệ.");
                    } else if (res.statusCode == 409) {
                        orderRequestId = null;
                        showToast("Báo giá đã thay đổi. Vui lòng quay lại kiểm tra giá.");
                    } else {
                        showToast(errorMessage(res, "Không thể tạo đơn hàng"));
                    }
                    return;
                }
                try {
                    JSONObject order = new JSONObject(res.body);
                    resolvedBillId = order.getString("id");
                    createOrRecoverIntent();
                } catch (JSONException e) {
                    showToast("Chưa đọc được thông tin đơn hàng. Vui lòng thử lại.");
                }
            });
        } catch (JSONException e) {
            setBusy(false);
            showToast("Dữ liệu đơn hàng không hợp lệ.");
        }
    }

    private void createOrRecoverIntent() {
        if (busy || request == null) return;
        setBusy(true);
        boolean recovering = requestId != null;
        if (!recovering) {
            requestId = UUID.randomUUID().toString();
        }
        try {
            String postPath = intentPostPath(request.kind);
            String recoveryPath = intentRecoveryPath(request.kind);
            JSONObject body = buildIntentPayload(request, requestId);

            Task<BackendApiClient.Response> task = recovering
                    ? api.get(recoveryPath + requestId).continueWithTask(t -> {
                        if (!t.isSuccessful()) throw t.getException();
                        return t.getResult().statusCode == 404
                                ? api.post(postPath, body.toString())
                                : Tasks.forResult(t.getResult());
                    })
                    : api.post(postPath, body.toString());

            task.addOnCompleteListener(done -> {
                if (binding == null || !isAdded()) return;
                setBusy(false);
                if (!done.isSuccessful() || done.getResult() == null) {
                    showToast("Chưa xác nhận được yêu cầu. Bấm xác nhận để thử lại cùng mã.");
                    return;
                }
                BackendApiClient.Response res = done.getResult();
                if (!ok(res)) {
                    showToast(errorMessage(res, "Không thể khởi tạo giao dịch"));
                    return;
                }
                try {
                    JSONObject json = new JSONObject(res.body);
                    String newIntentId = json.getString("intentId");
                    if (!newIntentId.equals(intentId)) {
                        challengeId = null;
                        faceVerified = false;
                    }
                    intentId = newIntentId;
                    requestId = json.optString("requestId", requestId);
                    faceRequired = json.optBoolean("faceRequired", faceRequired);
                    inspectIntent(true);
                } catch (JSONException e) {
                    showToast("Chưa đọc được phản hồi giao dịch. Vui lòng thử lại.");
                }
            });
        } catch (JSONException e) {
            setBusy(false);
            showToast("Dữ liệu giao dịch không hợp lệ.");
        }
    }

    private String intentPostPath(@NonNull TransactionFlowRequest.Kind kind) {
        switch (kind) {
            case INTERNAL_TRANSFER: return "/transfers/intents";
            case EXTERNAL_TRANSFER: return "/external-transfers";
            case WITHDRAWAL: return "/money-requests/intents";
            case DEPOSIT: return "/topups/intents";
            case BILL_PAYMENT:
            case ORDER_PAYMENT:
            default: return "/payments/intents";
        }
    }

    private String intentRecoveryPath(@NonNull TransactionFlowRequest.Kind kind) {
        switch (kind) {
            case INTERNAL_TRANSFER: return "/transfers/by-request/";
            case EXTERNAL_TRANSFER:
            case WITHDRAWAL: return "/demo-movements/by-request/";
            case DEPOSIT: return "/topup-intents/by-request/";
            case BILL_PAYMENT:
            case ORDER_PAYMENT:
            default: return "/payment-intents/by-request/";
        }
    }

    private JSONObject buildIntentPayload(@NonNull TransactionFlowRequest req, @NonNull String reqId)
            throws JSONException {
        JSONObject body = new JSONObject().put("requestId", reqId);
        switch (req.kind) {
            case INTERNAL_TRANSFER:
                body.put("sourceAccountId", req.sourceAccountId)
                        .put("amountMinor", req.amountMinor)
                        .put("currency", req.currency);
                if (req.targetNumber != null) body.put("destinationPhone", req.targetNumber);
                if (req.recipientAccountId != null) body.put("recipientAccountId", req.recipientAccountId);
                break;
            case EXTERNAL_TRANSFER:
                body.put("accountId", req.sourceAccountId)
                        .put("amountMinor", req.amountMinor)
                        .put("currency", req.currency)
                        .put("bankCode", req.bankCode)
                        .put("beneficiaryAccount", req.targetNumber);
                break;
            case WITHDRAWAL:
                body.put("accountId", req.sourceAccountId)
                        .put("amountMinor", req.amountMinor)
                        .put("currency", req.currency)
                        .put("type", "WITHDRAWAL");
                break;
            case DEPOSIT:
                body.put("accountId", req.sourceAccountId)
                        .put("amountMinor", req.amountMinor)
                        .put("currency", req.currency);
                break;
            case BILL_PAYMENT:
            case ORDER_PAYMENT:
            default:
                body.put("billId", resolvedBillId);
                body.put("paymentMethod", selectedPaymentMethod);
                if ("NIMBUS_ACCOUNT".equals(selectedPaymentMethod) && selectedPaymentAccountId != null)
                    body.put("sourceAccountId", selectedPaymentAccountId);
                break;
        }
        return body;
    }

    private void inspectIntent(boolean promptOtpIfReady) {
        if (intentId == null || completed || binding == null) return;
        setBusy(true);
        api.get("/intents/" + intentId).addOnCompleteListener(task -> {
            if (binding == null || !isAdded()) return;
            setBusy(false);
            if (!task.isSuccessful() || task.getResult() == null || !ok(task.getResult())) {
                showToast("Chưa kiểm tra được trạng thái giao dịch. Vui lòng thử lại.");
                return;
            }
            try {
                JSONObject result = new JSONObject(task.getResult().body);
                if (isBillOrOrderPayment()) {
                    String recoveredRequestId = result.optString("requestId", "");
                    if (!recoveredRequestId.isEmpty()) requestId = recoveredRequestId;
                    String recoveredMethod = result.optString("paymentMethod", "");
                    if (!recoveredMethod.isEmpty()) selectedPaymentMethod = recoveredMethod;
                    if ("NIMBUS_ACCOUNT".equals(selectedPaymentMethod)) {
                        String recoveredAccountId = result.optString("sourceAccountId", result.optString("sourceId", ""));
                        if (!recoveredAccountId.isEmpty()) selectedPaymentAccountId = recoveredAccountId;
                    }
                }
                authoritativeFee = result.optLong("feeMinor", authoritativeFee);
                providerReference = result.optString("providerReference", providerReference);
                faceRequired = result.optBoolean("faceRequired", faceRequired);
                faceVerified = "VERIFIED".equalsIgnoreCase(result.optString("faceStatus"));
                String status = result.optString("status");
                String challengeStatus = result.optString("challengeStatus");

                if ("COMMITTED".equalsIgnoreCase(status)) {
                    finishTransaction();
                    return;
                }

                if ("FAILED".equalsIgnoreCase(status) || "CANCELLED".equalsIgnoreCase(status)
                        || "EXPIRED".equalsIgnoreCase(status)) {
                    commitPending = false;
                    forgetPending();
                    renderConfirmationStep();
                    showToast("Giao dịch chưa hoàn tất hoặc đã hết hạn. Vui lòng thử lại.");
                    return;
                }

                if ("COMMIT_PENDING".equalsIgnoreCase(status)) {
                    commitPending = true;
                    renderConfirmationStep();
                    handleCommitPendingState(result);
                    return;
                }

                commitPending = false;
                renderConfirmationStep();

                if (faceRequired && !faceVerified) {
                    showToast("Giao dịch cần xác minh khuôn mặt trước khi nhập OTP.");
                    return;
                }

                if ("CREATED".equalsIgnoreCase(status) && "VERIFIED".equalsIgnoreCase(challengeStatus)
                        && (!faceRequired || faceVerified)) {
                    commitTransaction();
                    return;
                }

                if (promptOtpIfReady) {
                    openOtpAndRequestCode();
                }
            } catch (JSONException e) {
                showToast("Chưa đọc được thông tin giao dịch.");
            }
        });
    }

    private void handleCommitPendingState(@NonNull JSONObject intentResult) {
        switch (request.kind) {
            case DEPOSIT:
                recoverTopupStatus(false);
                break;
            case EXTERNAL_TRANSFER:
            case WITHDRAWAL:
                demoReconcileRemaining = MAX_DEMO_RECONCILE_RETRIES;
                reconcileDemo();
                break;
            case BILL_PAYMENT:
            case ORDER_PAYMENT:
                showPendingCard("Giao dịch đang chờ xác nhận thanh toán.");
                break;
            case INTERNAL_TRANSFER:
            default:
                if ("STRIPE_TREASURY".equals(intentResult.optString("provider"))) {
                    commitTransaction();
                } else {
                    showPendingCard("Giao dịch đang được xử lý. Bấm kiểm tra trạng thái sau ít phút.");
                }
                break;
        }
    }

    private void verifyFace(byte[] image) {
        if (binding == null || !isAdded() || intentId == null) return;
        setBusy(true);
        api.verifyIntentFace(intentId, image).addOnCompleteListener(task -> {
            if (binding == null || !isAdded()) return;
            setBusy(false);
            if (!task.isSuccessful() || task.getResult() == null) {
                faceError("Không nhận được kết quả xác minh. Vui lòng thử lại.");
            } else if (!ok(task.getResult())) {
                faceError(errorMessage(task.getResult(), "Xác minh khuôn mặt thất bại"));
            } else {
                faceVerified = true;
                renderConfirmationStep();
                inspectIntent(true);
            }
        });
    }

    private void faceError(String message) {
        if (binding == null || !isAdded()) return;
        setBusy(false);
        showToast(message);
    }

    private void openOtpAndRequestCode() {
        if (binding == null || !isAdded() || intentId == null) return;
        if (otpDialog == null) {
            otpDialog = new OtpBottomSheetDialog(requireContext(), new OtpBottomSheetDialog.Listener() {
                @Override public void onSubmitOtp(@NonNull String code) { verifyOtp(code); }
                @Override public void onResendRequested() { requestOtp(); }
                @Override public void onDismissRequested() { cancelOtpTimer(); dismissOtpDialog(); }
            });
        }
        otpDialog.setStatusMessage(null);
        otpDialog.clearDigits();
        otpDialog.show();
        requestOtp();
    }

    private void requestOtp() {
        if (intentId == null || commitPending || (faceRequired && !faceVerified)) return;
        if (otpDialog != null) {
            otpDialog.setBusy(true);
            otpDialog.setStatusMessage(null);
        }
        api.post("/intents/" + intentId + "/otp", "{}").addOnCompleteListener(task -> {
            if (binding == null || !isAdded()) return;
            if (otpDialog != null) otpDialog.setBusy(false);
            if (!task.isSuccessful() || task.getResult() == null) {
                startOtpCountdown();
                showToast("Không thể xác thực giao dịch. Vui lòng thử lại.");
                return;
            }
            BackendApiClient.Response response = task.getResult();
            if (!ok(response)) {
                showToast(errorMessage(response, "Không thể xác thực giao dịch."));
                return;
            }
            try {
                JSONObject result = new JSONObject(response.body);
                if (!"DEMO_DISPLAY_CODE".equals(result.optString("method"))) {
                    throw new IllegalStateException("Unsupported OTP method");
                }
                challengeId = result.getString("challengeId");
                String code = result.getString("code");
                if (!code.matches("[0-9]{6}")) {
                    throw new IllegalStateException("Invalid OTP code");
                }
                if (otpDialog != null) otpDialog.setDemoCode(code);
                startOtpCountdown();
            } catch (Exception e) {
                showToast("Không thể xác thực giao dịch. Vui lòng thử lại.");
            }
        });
    }

    private void startOtpCountdown() {
        cancelOtpTimer();
        if (otpDialog != null) {
            otpDialog.setCountdownText("Gửi lại sau 00:30", false);
        }
        otpTimer = new CountDownTimer(30_000L, 1000L) {
            @Override
            public void onTick(long millisUntilFinished) {
                if (binding == null || !isAdded() || otpDialog == null) return;
                otpDialog.setCountdownText(String.format(Locale.getDefault(),
                        "Gửi lại sau 00:%02d", millisUntilFinished / 1000L), false);
            }

            @Override
            public void onFinish() {
                if (binding == null || !isAdded() || otpDialog == null) return;
                otpDialog.setCountdownText("Gửi lại mã OTP", true);
            }
        }.start();
    }

    private void verifyOtp(@NonNull String code) {
        if (intentId == null || challengeId == null || busy) return;
        setBusy(true);
        if (otpDialog != null) {
            otpDialog.setBusy(true);
            otpDialog.setStatusMessage(null);
        }
        api.verifyIntentOtp(new OtpVerification(intentId, challengeId, code)).addOnCompleteListener(task -> {
            if (binding == null || !isAdded()) return;
            setBusy(false);
            if (!task.isSuccessful() || task.getResult() == null || task.getResult().statusCode >= 500) {
                if (otpDialog != null) otpDialog.setBusy(false);
                commitPending = true;
                dismissOtpDialog();
                showPendingCard("Chưa nhận được phản hồi xác thực. Bấm kiểm tra trạng thái.");
                inspectIntent(false);
                return;
            }
            BackendApiClient.Response response = task.getResult();
            if (ok(response)) {
                cancelOtpTimer();
                dismissOtpDialog();
                rememberPending();
                commitTransaction();
                return;
            }
            if (otpDialog != null) otpDialog.setBusy(false);
            if ("OTP_USED".equals(errorCode(response))) {
                dismissOtpDialog();
                inspectIntent(false);
            } else {
                showToast(errorMessage(response, "Không thể xác thực giao dịch."));
            }
        });
    }

    private void commitTransaction() {
        if (completed || intentId == null || request == null) return;
        rememberPending();
        commitPending = true;
        setBusy(true);
        switch (request.kind) {
            case INTERNAL_TRANSFER:
                commitInternalTransfer();
                break;
            case EXTERNAL_TRANSFER:
            case WITHDRAWAL:
                demoReconcileRemaining = MAX_DEMO_RECONCILE_RETRIES;
                commitDemoMovement();
                break;
            case DEPOSIT:
                commitTopupPayment();
                break;
            case BILL_PAYMENT:
            case ORDER_PAYMENT:
            default:
                commitBillOrOrderPayment();
                break;
        }
    }

    private void commitInternalTransfer() {
        api.post("/transfers/" + intentId + "/commit", "{}").addOnCompleteListener(task -> {
            if (binding == null || !isAdded()) return;
            setBusy(false);
            if (otpDialog != null) otpDialog.setBusy(false);
            if (!task.isSuccessful() || task.getResult() == null || task.getResult().statusCode >= 500) {
                dismissOtpDialog();
                showPendingCard("Giao dịch vẫn đang được xử lý. Bấm kiểm tra trạng thái sau ít phút.");
                return;
            }
            BackendApiClient.Response res = task.getResult();
            if (ok(res)) {
                try {
                    JSONObject json = new JSONObject(res.body);
                    providerReference = json.optString("providerReference", providerReference);
                    String status = json.optString("status");
                    if ("SETTLED".equalsIgnoreCase(status) || "COMMITTED".equalsIgnoreCase(status)) {
                        finishTransaction();
                        return;
                    }
                    if ("FAILED".equalsIgnoreCase(status)) {
                        commitPending = false;
                        forgetPending();
                        dismissOtpDialog();
                        renderConfirmationStep();
                        showToast("Giao dịch chưa hoàn tất. Số dư chưa thay đổi.");
                        return;
                    }
                } catch (JSONException ignored) { }
                dismissOtpDialog();
                showPendingCard("Chưa đọc được kết quả. Bấm kiểm tra trạng thái.");
                return;
            }
            handleCommitError(res, "Không thể hoàn tất chuyển tiền");
        });
    }

    private void commitDemoMovement() {
        api.post("/demo-movements/" + intentId + "/commit", "{}").addOnCompleteListener(task -> {
            if (binding == null || !isAdded()) return;
            setBusy(false);
            if (otpDialog != null) otpDialog.setBusy(false);
            if (!task.isSuccessful() || task.getResult() == null || task.getResult().statusCode >= 500) {
                dismissOtpDialog();
                showPendingCard("Giao dịch đang được đối soát. Bấm kiểm tra trạng thái.");
                return;
            }
            BackendApiClient.Response res = task.getResult();
            if (ok(res)) {
                handleDemoOutcome(res.body);
                return;
            }
            handleCommitError(res, "Không thể hoàn tất giao dịch");
        });
    }

    private void reconcileDemo() {
        if (intentId == null || completed || binding == null) return;
        setBusy(true);
        api.post("/demo-movements/" + intentId + "/reconcile", "{}").addOnCompleteListener(task -> {
            if (binding == null || !isAdded()) return;
            setBusy(false);
            if (!task.isSuccessful() || task.getResult() == null || !ok(task.getResult())) {
                showPendingCard("Chưa cập nhật được trạng thái giao dịch. Bấm kiểm tra lại.");
                return;
            }
            handleDemoOutcome(task.getResult().body);
        });
    }

    private void handleDemoOutcome(@NonNull String responseBody) {
        try {
            JSONObject result = new JSONObject(responseBody);
            providerReference = result.optString("providerReference", providerReference);
            String state = result.optString("status");
            if ("COMMITTED".equalsIgnoreCase(state)) {
                finishTransaction();
                return;
            }
            if ("FAILED".equalsIgnoreCase(state) || "CANCELLED".equalsIgnoreCase(state)) {
                commitPending = false;
                forgetPending();
                dismissOtpDialog();
                renderConfirmationStep();
                showToast("Giao dịch chưa hoàn tất. Số dư chưa thay đổi.");
                return;
            }
            commitPending = "COMMIT_PENDING".equalsIgnoreCase(state);
            if (commitPending) {
                dismissOtpDialog();
                showPendingCard("Giao dịch đang được đối soát…");
                if (demoReconcileRemaining > 0) {
                    demoReconcileRemaining--;
                    handler.postDelayed(this::reconcileDemo, DEMO_RECONCILE_DELAY_MS);
                } else {
                    showPendingCard("Giao dịch đang được xử lý. Bấm kiểm tra trạng thái sau ít phút.");
                }
            }
        } catch (JSONException e) {
            dismissOtpDialog();
            showPendingCard("Chưa cập nhật được trạng thái giao dịch. Bấm kiểm tra lại.");
        }
    }

    private void commitTopupPayment() {
        if (paymentSheet == null || requestId == null || request.sourceAccountId == null) {
            setBusy(false);
            dismissOtpDialog();
            commitPending = false;
            forgetPending();
            renderConfirmationStep();
            showToast("Thanh toán không thành công.");
            return;
        }
        try {
            String body = new JSONObject()
                    .put("accountId", request.sourceAccountId)
                    .put("requestId", requestId)
                    .toString();
            api.post("/topups", body).addOnCompleteListener(task -> {
                if (binding == null || !isAdded()) return;
                setBusy(false);
                if (otpDialog != null) otpDialog.setBusy(false);
                if (!task.isSuccessful() || task.getResult() == null || !ok(task.getResult())) {
                    commitPending = false;
                    forgetPending();
                    dismissOtpDialog();
                    renderConfirmationStep();
                    showToast("Thanh toán không thành công.");
                    return;
                }
                try {
                    JSONObject result = new JSONObject(task.getResult().body);
                    if ("SUCCEEDED".equalsIgnoreCase(result.optString("status"))) {
                        finishTransaction();
                        return;
                    }
                    String clientSecret = result.optString("clientSecret", "");
                    if (clientSecret.isEmpty()) {
                        dismissOtpDialog();
                        recoverTopupStatus(false);
                        return;
                    }
                    dismissOtpDialog();
                    presentStripePaymentSheet(clientSecret, result);
                } catch (JSONException e) {
                    dismissOtpDialog();
                    showPendingCard("Phản hồi thanh toán không hợp lệ. Bấm kiểm tra lại.");
                }
            });
        } catch (JSONException e) {
            setBusy(false);
            if (otpDialog != null) otpDialog.setBusy(false);
            showToast("Dữ liệu nạp tiền không hợp lệ.");
        }
    }

    private void commitBillOrOrderPayment() {
        if (resolvedBillId == null || requestId == null) {
            setBusy(false);
            if (otpDialog != null) otpDialog.setBusy(false);
            showToast("Thiếu thông tin hóa đơn để thanh toán.");
            return;
        }
        try {
            JSONObject paymentBody = new JSONObject()
                    .put("billId", resolvedBillId)
                    .put("requestId", requestId)
                    .put("paymentMethod", selectedPaymentMethod);
            if ("NIMBUS_ACCOUNT".equals(selectedPaymentMethod)) paymentBody.put("sourceAccountId", selectedPaymentAccountId);
            String body = paymentBody.toString();
            api.post("/payments", body).continueWithTask(task -> {
                BackendApiClient.Response response = task.isSuccessful() ? task.getResult() : null;
                if (response != null && (response.statusCode < 500 || "SETUP_REQUIRED".equals(errorCode(response)))) {
                    return Tasks.forResult(response);
                }
                return api.post("/payments", body);
            }).addOnCompleteListener(task -> {
                if (binding == null || !isAdded()) return;
                setBusy(false);
                if (otpDialog != null) otpDialog.setBusy(false);
                if (!task.isSuccessful() || task.getResult() == null || task.getResult().statusCode >= 500) {
                    dismissOtpDialog();
                    commitPending = false;
                    forgetPending();
                    renderConfirmationStep();
                    showToast("Thanh toán không thành công.");
                    return;
                }
                BackendApiClient.Response res = task.getResult();
                if (ok(res)) {
                    try {
                        JSONObject json = new JSONObject(res.body);
                        String status = json.optString("status", "");
                        if ("SUCCEEDED".equalsIgnoreCase(status) || "SETTLED".equalsIgnoreCase(status)) {
                            finishTransaction();
                            return;
                        }
                        if ("FAILED".equalsIgnoreCase(status)) {
                            commitPending = false;
                            forgetPending();
                            dismissOtpDialog();
                            renderConfirmationStep();
                            showToast("Thanh toán không thành công.");
                            return;
                        }
                        String clientSecret = json.optString("clientSecret", "");
                        if (!json.isNull("clientSecret") && !clientSecret.isEmpty()) {
                            if (paymentSheet == null) {
                                dismissOtpDialog();
                                commitPending = false;
                                forgetPending();
                                renderConfirmationStep();
                                showToast("Thanh toán không thành công.");
                                return;
                            }
                            dismissOtpDialog();
                            presentStripePaymentSheet(clientSecret, json);
                            return;
                        }
                    } catch (JSONException ignored) { }
                    dismissOtpDialog();
                    inspectIntent(false);
                    return;
                }
                handleCommitError(res, "Thanh toán không thành công.");
            });
        } catch (JSONException e) {
            setBusy(false);
            if (otpDialog != null) otpDialog.setBusy(false);
            showToast("Không thể tạo yêu cầu thanh toán.");
        }
    }

    private void presentStripePaymentSheet(@NonNull String clientSecret, @NonNull JSONObject responseJson)
            throws JSONException {
        PaymentSheet.Configuration.Builder configuration = new PaymentSheet.Configuration.Builder("Nimbus Bank");
        if (!responseJson.isNull("customerId") && !responseJson.isNull("customerSessionClientSecret")) {
            configuration.customer(PaymentSheet.CustomerConfiguration.Companion.createWithCustomerSession(
                    responseJson.getString("customerId"),
                    responseJson.getString("customerSessionClientSecret")));
        }
        showPendingCard("Đang mở cổng thanh toán thẻ…");
        paymentSheet.presentWithPaymentIntent(clientSecret, configuration.build());
    }

    private void onPaymentSheetResult(@NonNull PaymentSheetResult result) {
        if (!isAdded() || binding == null) return;
        setBusy(false);
        commitPending = true;
        if (result instanceof PaymentSheetResult.Completed) {
            showPendingCard("Đang chờ xác nhận thanh toán…");
            stripePollsRemaining = MAX_STRIPE_POLL_RETRIES;
            if (request.kind == TransactionFlowRequest.Kind.DEPOSIT) {
                recoverTopupStatus(true);
            } else {
                pollPaymentIntentUntilSettled();
            }
        } else if (result instanceof PaymentSheetResult.Canceled) {
            showPendingCard("Bạn đã đóng màn thanh toán. Bấm kiểm tra hoặc thử lại.");
        } else if (result instanceof PaymentSheetResult.Failed) {
            commitPending = false;
            forgetPending();
            renderConfirmationStep();
            showToast("Thanh toán không thành công.");
        }
    }

    private void recoverTopupStatus(boolean keepPolling) {
        if (intentId == null || binding == null || !isAdded()) return;
        api.get("/topups/" + intentId).addOnCompleteListener(task -> {
            if (binding == null || !isAdded()) return;
            if (!task.isSuccessful() || task.getResult() == null || !ok(task.getResult())) {
                showPendingCard("Chưa cập nhật được trạng thái nạp tiền. Bấm kiểm tra lại.");
                return;
            }
            try {
                JSONObject result = new JSONObject(task.getResult().body);
                String state = result.optString("status");
                if ("SUCCEEDED".equalsIgnoreCase(state)) {
                    finishTransaction();
                } else if ("FAILED".equalsIgnoreCase(state) || "CANCELLED".equalsIgnoreCase(state)) {
                    commitPending = false;
                    forgetPending();
                    renderConfirmationStep();
                    showToast("Thanh toán không thành công.");
                } else {
                    commitPending = true;
                    showPendingCard("Đang chờ xác nhận thanh toán. Số dư sẽ cập nhật sau khi hoàn tất.");
                    if (keepPolling && stripePollsRemaining-- > 0) {
                        handler.postDelayed(() -> recoverTopupStatus(true), STRIPE_POLL_DELAY_MS);
                    }
                }
            } catch (JSONException e) {
                showPendingCard("Không đọc được trạng thái nạp tiền.");
            }
        });
    }

    private void pollPaymentIntentUntilSettled() {
        if (intentId == null || binding == null || !isAdded() || completed) return;
        api.get("/intents/" + intentId).addOnCompleteListener(task -> {
            if (binding == null || !isAdded()) return;
            if (!task.isSuccessful() || task.getResult() == null || !ok(task.getResult())) {
                showPendingCard("Chưa cập nhật được kết quả thanh toán. Bấm kiểm tra lại.");
                return;
            }
            try {
                JSONObject intent = new JSONObject(task.getResult().body);
                providerReference = intent.optString("providerReference", providerReference);
                authoritativeFee = intent.optLong("feeMinor", authoritativeFee);
                String state = intent.optString("status");
                if ("COMMITTED".equalsIgnoreCase(state)) {
                    finishTransaction();
                } else if ("FAILED".equalsIgnoreCase(state) || "CANCELLED".equalsIgnoreCase(state)) {
                    commitPending = false;
                    forgetPending();
                    renderConfirmationStep();
                    showToast("Thanh toán không thành công.");
                } else {
                    commitPending = true;
                    showPendingCard("Đang chờ xác nhận thanh toán…");
                    if (stripePollsRemaining-- > 0) {
                        handler.postDelayed(this::pollPaymentIntentUntilSettled, STRIPE_POLL_DELAY_MS);
                    }
                }
            } catch (JSONException e) {
                showPendingCard("Chưa đọc được trạng thái thanh toán.");
            }
        });
    }

    private void resumePendingTransaction() {
        if (busy || intentId == null || request == null) return;
        if (request.kind == TransactionFlowRequest.Kind.DEPOSIT) {
            commitTopupPayment();
        } else if (request.kind == TransactionFlowRequest.Kind.EXTERNAL_TRANSFER
                || request.kind == TransactionFlowRequest.Kind.WITHDRAWAL) {
            demoReconcileRemaining = MAX_DEMO_RECONCILE_RETRIES;
            reconcileDemo();
        } else if (request.kind == TransactionFlowRequest.Kind.BILL_PAYMENT
                || request.kind == TransactionFlowRequest.Kind.ORDER_PAYMENT) {
            stripePollsRemaining = MAX_STRIPE_POLL_RETRIES;
            commitBillOrOrderPayment();
        } else {
            inspectIntent(false);
        }
    }

    private void handleCommitError(@NonNull BackendApiClient.Response response, @NonNull String fallback) {
        commitPending = false;
        forgetPending();
        String code = errorCode(response);
        if ("FACE_REQUIRED".equals(code) || "STEP_UP_UNAVAILABLE".equals(code)) {
            faceRequired = true;
            faceVerified = false;
        }
        dismissOtpDialog();
        renderConfirmationStep();
        boolean cardPayment = request != null && (request.kind == TransactionFlowRequest.Kind.BILL_PAYMENT
                || request.kind == TransactionFlowRequest.Kind.ORDER_PAYMENT);
        showToast(cardPayment ? "Thanh toán không thành công." : errorMessage(response, fallback));
    }

    private void finishTransaction() {
        forgetPending();
        commitPending = false;
        completed = true;
        if (completedAtMillis <= 0L) {
            completedAtMillis = System.currentTimeMillis();
        }
        renderSuccessStep();
    }

    private void showPendingCard(@NonNull String message) {
        if (binding == null) return;
        binding.pendingStatusCard.setVisibility(View.VISIBLE);
        binding.pendingStatusText.setText(message);
        updateConfirmButtonState();
    }

    private void showSecurityInfoDialog() {
        new ThemedDialogBuilder(requireContext())
                .setTitle("Bảo mật giao dịch")
                .setMessage("Giao dịch được bảo vệ bằng mã hóa bảo mật và xác thực qua Nimbus Smart OTP. "
                        + "Các giao dịch giá trị cao sẽ yêu cầu thêm bước xác minh khuôn mặt.")
                .setPositiveButton("Đã hiểu", null)
                .show();
    }

    private void handleBackNavigation() {
        if (completed) {
            navigateHome();
            return;
        }
        if (busy) return;
        if (commitPending) {
            showPendingCard("Giao dịch đang chờ hoàn tất. Vui lòng kiểm tra trạng thái trước khi thoát.");
            showToast("Giao dịch đang chờ hoàn tất. Không thể hủy lúc này.");
            return;
        }
        if (intentId != null) {
            setBusy(true);
            api.post("/intents/" + intentId + "/cancel", "{}").addOnCompleteListener(task -> {
                if (binding == null || !isAdded()) return;
                setBusy(false);
                forgetPending();
                intentId = null;
                Navigation.findNavController(requireView()).navigateUp();
            });
            return;
        }
        Navigation.findNavController(requireView()).navigateUp();
    }

    private void navigateHome() {
        if (binding == null || !isAdded()) return;
        Navigation.findNavController(requireView()).popBackStack(R.id.customerHome, false);
    }

    private void copyToClipboard(@NonNull String value) {
        ClipboardManager clipboard =
                (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("transaction_id", value));
            showToast("Đã sao chép mã giao dịch");
        }
    }

    private void shareReceipt(@NonNull String shareText) {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, shareText);
        startActivity(Intent.createChooser(share, "Chia sẻ biên lai giao dịch"));
    }

    private SharedPreferences pendingPreferences() {
        return requireContext().getSharedPreferences(PREFS_PENDING_TRANSFER, Context.MODE_PRIVATE);
    }

    private String pendingKey() {
        return (customerUid == null ? "guest" : customerUid) + ":" + request.kind.name();
    }

    private void rememberPending() {
        if (intentId == null || request == null) return;
        storedPendingCommit = pendingPreferences().edit()
                .putString(pendingKey(), intentId)
                .commit();
    }

    private void forgetPending() {
        if (!storedPendingCommit || request == null) return;
        String key = pendingKey();
        SharedPreferences prefs = pendingPreferences();
        if (intentId != null && intentId.equals(prefs.getString(key, null))) {
            prefs.edit().remove(key).apply();
        }
        storedPendingCommit = false;
    }

    private void setBusy(boolean busy) {
        this.busy = busy;
        updateConfirmButtonState();
    }

    private void updateConfirmButtonState() {
        if (binding == null) return;
        boolean enabled = !busy && !completed;
        if (isBillOrOrderPayment() && "NIMBUS_ACCOUNT".equals(selectedPaymentMethod))
            enabled = enabled && selectedPaymentAccountId != null && selectedPaymentAvailableMinor >= request.amountMinor;
        binding.btnConfirmSubmit.setEnabled(enabled);
        binding.btnFaceStepUp.setEnabled(enabled);
        binding.btnPendingAction.setEnabled(enabled);
    }

    private void cancelOtpTimer() {
        if (otpTimer != null) {
            otpTimer.cancel();
            otpTimer = null;
        }
    }

    private void dismissOtpDialog() {
        if (otpDialog != null) {
            otpDialog.dismiss();
            otpDialog = null;
        }
    }

    private void showToast(@NonNull String message) {
        if (getContext() != null) {
            Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static boolean ok(@Nullable BackendApiClient.Response response) {
        return response != null && response.statusCode >= 200 && response.statusCode < 300;
    }

    @NonNull
    private static String errorCode(@Nullable BackendApiClient.Response response) {
        if (response == null || response.body == null) return "";
        try {
            return new JSONObject(response.body).optString("error", "");
        } catch (Exception ignored) {
            return "";
        }
    }

    @NonNull
    private static String errorMessage(@Nullable BackendApiClient.Response response, @NonNull String fallback) {
        String code = errorCode(response);
        switch (code) {
            case "INVALID_DESTINATION": return "Không tìm thấy tài khoản nhận đang hoạt động cho số điện thoại này.";
            case "RECIPIENT_CHANGED": return "Thông tin người nhận đã thay đổi. Quay lại để tra cứu lại số điện thoại.";
            case "STRIPE_SANDBOX_ACCOUNT_REQUIRED": return "Tài khoản chưa sẵn sàng cho giao dịch này.";
            case "TOP_UP_REQUIRES_CARD": return "Vui lòng thanh toán bằng thẻ để nạp tiền.";
            case "CURRENCY_NOT_SUPPORTED": return "Luồng này chỉ hỗ trợ tài khoản VND.";
            case "SETUP_REQUIRED": return "Thanh toán không thành công.";
            case "INVALID_OTP": return "Mã xác nhận không đúng. Vui lòng kiểm tra lại.";
            case "OTP_EXPIRED": return "Mã xác nhận đã hết hạn. Tạo mã mới để tiếp tục.";
            case "OTP_LOCKED": return "Đã nhập sai quá 5 lần, giao dịch bị khóa. Quay lại và tạo yêu cầu mới.";
            case "OTP_USED": return "Mã đã được xác minh. Đang kiểm tra trạng thái giao dịch.";
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
            case "PAYMENT_IN_PROGRESS": return "Hóa đơn đang có giao dịch chờ xử lý. Bấm kiểm tra trạng thái.";
            case "IDEMPOTENCY_CONFLICT": return "Yêu cầu này đã được gửi trước đó. Kiểm tra trạng thái rồi thử lại.";
            default: return fallback + ". Vui lòng thử lại sau.";
        }
    }

    @Override
    public void onDestroyView() {
        cancelOtpTimer();
        dismissOtpDialog();
        handler.removeCallbacksAndMessages(null);
        binding = null;
        busy = false;
        super.onDestroyView();
    }
}

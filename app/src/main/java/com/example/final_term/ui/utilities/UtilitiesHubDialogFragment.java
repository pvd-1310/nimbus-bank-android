package com.example.final_term.ui.utilities;

import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;

import com.example.final_term.R;
import com.example.final_term.BuildConfig;
import com.example.final_term.core.FinalTermApplication;
import com.example.final_term.data.BackendApiClient;
import com.example.final_term.data.ReadSummaryFormatter;
import com.example.final_term.databinding.DialogUtilitiesHubBinding;
import com.example.final_term.domain.DomainDtos.OtpVerification;
import com.example.final_term.ui.ekyc.FaceCamera;
import com.stripe.android.PaymentConfiguration;
import com.stripe.android.paymentsheet.PaymentSheet;
import com.stripe.android.paymentsheet.PaymentSheetResult;
import com.stripe.android.paymentsheet.PaymentSheetResultCallback;
import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.UUID;

public class UtilitiesHubDialogFragment extends DialogFragment {
    private DialogUtilitiesHubBinding binding;
    private BackendApiClient api;
    private PaymentSheet paymentSheet;
    private final Handler settlementHandler = new Handler(Looper.getMainLooper());
    private int settlementPollsRemaining;
    private final Runnable settlementPoll = this::pollForSettlement;

    private void pollForSettlement() {
        if (isAdded() && binding != null && commitPending && settlementPollsRemaining > 0) {
            settlementPollsRemaining--;
            recoverPaymentAndIntent();
        }
    }

    // Checkout State
    private String currentBillId;
    private String currentServiceName = "Hóa đơn dịch vụ";
    private String currentReference = "";
    private String requestId;
    private String intentId;
    private String challengeId;
    private boolean faceRequired;
    private boolean faceVerified;
    private boolean commitPending;
    private boolean busy;
    private boolean completed;

    // Authoritative intent fields from backend
    private long authoritativeAmount;
    private long authoritativeFee;
    private String authoritativeCurrency = "VND";
    private String authoritativeTarget = "";

    private long selectedTopupAmount = 20000;
    private CountDownTimer otpTimer;

    private final FaceCamera faceCamera = new FaceCamera(this, this::verifyFace, this::faceError);

    public static UtilitiesHubDialogFragment newInstance(@Nullable String billId) {
        UtilitiesHubDialogFragment fragment = new UtilitiesHubDialogFragment();
        if (billId != null) {
            Bundle args = new Bundle();
            args.putString("billId", billId);
            fragment.setArguments(args);
        }
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        faceCamera.restore(savedInstanceState);
        if (savedInstanceState != null) {
            currentBillId = savedInstanceState.getString("currentBillId");
            currentServiceName = savedInstanceState.getString("currentServiceName", "Hóa đơn dịch vụ");
            currentReference = savedInstanceState.getString("currentReference", "");
            requestId = savedInstanceState.getString("requestId");
            intentId = savedInstanceState.getString("intentId");
            challengeId = savedInstanceState.getString("challengeId");
            faceRequired = savedInstanceState.getBoolean("faceRequired");
            faceVerified = savedInstanceState.getBoolean("faceVerified");
            commitPending = savedInstanceState.getBoolean("commitPending");
            authoritativeAmount = savedInstanceState.getLong("authoritativeAmount");
            authoritativeFee = savedInstanceState.getLong("authoritativeFee");
            authoritativeCurrency = savedInstanceState.getString("authoritativeCurrency", "VND");
            authoritativeTarget = savedInstanceState.getString("authoritativeTarget", "");
            selectedTopupAmount = savedInstanceState.getLong("selectedTopupAmount", 20000);
        } else if (getArguments() != null) {
            currentBillId = getArguments().getString("billId");
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        faceCamera.save(outState);
        outState.putString("currentBillId", currentBillId);
        outState.putString("currentServiceName", currentServiceName);
        outState.putString("currentReference", currentReference);
        outState.putString("requestId", requestId);
        outState.putString("intentId", intentId);
        outState.putString("challengeId", challengeId);
        outState.putBoolean("faceRequired", faceRequired);
        outState.putBoolean("faceVerified", faceVerified);
        outState.putBoolean("commitPending", commitPending);
        outState.putLong("authoritativeAmount", authoritativeAmount);
        outState.putLong("authoritativeFee", authoritativeFee);
        outState.putString("authoritativeCurrency", authoritativeCurrency);
        outState.putString("authoritativeTarget", authoritativeTarget);
        outState.putLong("selectedTopupAmount", selectedTopupAmount);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = DialogUtilitiesHubBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        FinalTermApplication app = (FinalTermApplication) requireActivity().getApplication();
        api = app.getAppContainer().getBackendApiClient();
        if (!BuildConfig.STRIPE_PUBLISHABLE_KEY.isEmpty()) {
            PaymentConfiguration.init(requireContext(), BuildConfig.STRIPE_PUBLISHABLE_KEY);
            paymentSheet = new PaymentSheet.Builder(this::onPaymentSheetResult).build(this);
        }

        binding.closeDialogBtn.setOnClickListener(v -> dismiss());

        // Category clicks
        binding.utilElectricity.setOnClickListener(v -> {
            binding.topupFormContainer.setVisibility(View.GONE);
            binding.lookupTitle.setText("Tra cứu hóa đơn điện (EVN)");
            binding.customerCodeInput.setHint("Nhập mã khách hàng (VD: PE01000123456)");
        });

        binding.utilWater.setOnClickListener(v -> {
            binding.topupFormContainer.setVisibility(View.GONE);
            binding.lookupTitle.setText("Tra cứu hóa đơn nước sạch (Sawaco)");
            binding.customerCodeInput.setHint("Nhập mã danh bộ (VD: 0102030405)");
        });

        binding.utilMobile.setOnClickListener(v -> {
            binding.topupFormContainer.setVisibility(View.VISIBLE);
            binding.lookupTitle.setText("Nạp tiền điện thoại trực tiếp");
            binding.customerCodeInput.setHint("Nhập số điện thoại cần nạp");
        });

        binding.utilInternet.setOnClickListener(v -> {
            binding.topupFormContainer.setVisibility(View.GONE);
            binding.lookupTitle.setText("Thanh toán cước Internet & Dịch vụ");
            binding.customerCodeInput.setHint("Nhập mã hợp đồng / mã đơn hàng");
        });

        // Topup chips
        setupTopupChips();

        // Topup proceed button
        binding.btnProceedTopup.setOnClickListener(v -> proceedTopupOrder());

        // Lookup button
        binding.lookupButton.setOnClickListener(v -> handleLookup());

        // Checkout controls
        binding.checkoutBtnCancelEdit.setOnClickListener(v -> cancelIntentForEdit());
        binding.checkoutBtnFace.setOnClickListener(v -> {
            if (busy) return;
            faceCamera.open();
        });
        binding.checkoutOtpTimer.setOnClickListener(v -> requestOtp());
        binding.checkoutBtnVerifyOtp.setOnClickListener(v -> verifyOtp());
        binding.checkoutBtnRecover.setOnClickListener(v -> recoverPaymentAndIntent(true, true));
        binding.receiptBtnDone.setOnClickListener(v -> dismiss());

        // Load owned bills from backend
        loadOwnedBills();

        // If restoring in middle of checkout
        if (intentId != null) {
            showCheckoutView();
            recoverPaymentAndIntent();
        } else if (currentBillId != null) {
            startCheckout(currentBillId, currentServiceName, currentReference.isEmpty() ? currentBillId : currentReference);
        }
    }

    private void setupTopupChips() {
        binding.chipTopup20k.setOnClickListener(v -> selectTopupChip(20000, binding.chipTopup20k));
        binding.chipTopup50k.setOnClickListener(v -> selectTopupChip(50000, binding.chipTopup50k));
        binding.chipTopup100k.setOnClickListener(v -> selectTopupChip(100000, binding.chipTopup100k));
        binding.chipTopup200k.setOnClickListener(v -> selectTopupChip(200000, binding.chipTopup200k));
    }

    private void selectTopupChip(long amount, TextView selectedView) {
        selectedTopupAmount = amount;
        TextView[] chips = {binding.chipTopup20k, binding.chipTopup50k, binding.chipTopup100k, binding.chipTopup200k};
        for (TextView chip : chips) {
            if (chip == selectedView) {
                chip.setBackgroundResource(R.drawable.navy_button_pill);
                chip.setTextColor(ContextCompat.getColor(requireContext(), R.color.white));
            } else {
                chip.setBackgroundResource(R.drawable.glass_pill_button);
                chip.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink));
            }
        }
    }

    private void loadOwnedBills() {
        if (api == null) {
            binding.billsEmptyOrLoadingText.setText("Backend API chưa được khởi tạo.");
            return;
        }
        binding.billsEmptyOrLoadingText.setText("Đang tải danh sách hóa đơn từ hệ thống…");
        api.get("/bills").addOnCompleteListener(task -> {
            if (!isAdded() || binding == null) return;
            if (!task.isSuccessful()) {
                Exception error = task.getException();
                String reason = error == null ? "không rõ" : error.getClass().getSimpleName();
                if (error != null && error.getMessage() != null
                        && error.getMessage().contains("BACKEND_AUTH_REQUIRED")) {
                    reason = "chưa đăng nhập Firebase";
                }
                binding.billsEmptyOrLoadingText.setText("Không gọi được backend (" + reason + ").");
                return;
            }
            BackendApiClient.Response response = task.getResult();
            if (response == null || response.statusCode != 200) {
                binding.billsEmptyOrLoadingText.setText("Backend trả lỗi HTTP "
                        + (response == null ? "không có phản hồi" : response.statusCode) + ".");
                return;
            }
            try {
                JSONObject json = new JSONObject(task.getResult().body);
                JSONArray items = json.optJSONArray("items");
                binding.billsListContainer.removeAllViews();
                int unpaidCount = 0;
                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject bill = items.getJSONObject(i);
                        String status = bill.optString("status", "UNPAID");
                        if ("UNPAID".equalsIgnoreCase(status)) {
                            unpaidCount++;
                            View billView = createBillItemView(bill);
                            binding.billsListContainer.addView(billView);
                        }
                    }
                }
                if (unpaidCount == 0) {
                    TextView empty = new TextView(requireContext());
                    empty.setText("Không có hóa đơn hoặc đơn hàng chưa thanh toán.");
                    empty.setPadding(12, 12, 12, 12);
                    empty.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink_muted));
                    binding.billsListContainer.addView(empty);
                }
            } catch (Exception e) {
                binding.billsEmptyOrLoadingText.setText("Không thể phân tích dữ liệu hóa đơn.");
            }
        });
    }

    private View createBillItemView(JSONObject bill) {
        String id = bill.optString("id");
        String invoiceRef = bill.optString("invoiceRef", id);
        long amountMinor = bill.optLong("amountMinor", 0);
        String currency = bill.optString("currency", "VND");
        String provider = bill.optString("provider", "Dịch vụ");

        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setBackgroundResource(R.drawable.glass_panel_card);
        card.setPadding(14, 14, 14, 14);
        card.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = 8;
        card.setLayoutParams(params);

        LinearLayout textCol = new LinearLayout(requireContext());
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView title = new TextView(requireContext());
        title.setText(provider + " - " + invoiceRef);
        title.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink));
        title.setTextSize(13);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        textCol.addView(title);

        TextView amt = new TextView(requireContext());
        amt.setText(ReadSummaryFormatter.money(amountMinor) + " " + currency);
        amt.setTextColor(ContextCompat.getColor(requireContext(), R.color.navy));
        amt.setTextSize(14);
        amt.setTypeface(null, android.graphics.Typeface.BOLD);
        textCol.addView(amt);

        card.addView(textCol);

        MaterialButton payBtn = new MaterialButton(requireContext());
        payBtn.setText("Thanh toán");
        payBtn.setTextSize(12);
        payBtn.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.navy));
        payBtn.setTextColor(ContextCompat.getColor(requireContext(), R.color.white));
        payBtn.setCornerRadius(14);
        payBtn.setOnClickListener(v -> startCheckout(id, provider, invoiceRef));
        card.addView(payBtn);

        return card;
    }

    private void proceedTopupOrder() {
        String phone = binding.topupPhoneInput.getText() == null ? "" : binding.topupPhoneInput.getText().toString().trim();
        if (phone.isEmpty()) {
            binding.topupPhoneInput.setError("Vui lòng nhập số điện thoại");
            return;
        }
        setBusy(true);
        checkoutStatus("Đang tạo đơn nạp tiền điện thoại…");
        // Fetch catalog to find topup product or fallback
        api.get("/catalog").addOnCompleteListener(task -> {
            if (!isAdded() || binding == null) return;
            String productId = "local_demo_topup";
            if (task.isSuccessful() && task.getResult() != null && task.getResult().statusCode == 200) {
                try {
                    JSONObject cat = new JSONObject(task.getResult().body);
                    JSONArray items = cat.optJSONArray("items");
                    if (items != null) {
                        for (int i = 0; i < items.length(); i++) {
                            JSONObject item = items.getJSONObject(i);
                            if ("TOPUP".equalsIgnoreCase(item.optString("serviceType"))) {
                                productId = item.optString("id");
                                break;
                            }
                        }
                    }
                } catch (Exception ignored) { }
            }
            int quantity = (int) Math.max(1, selectedTopupAmount / 20000);
            try {
                String reqId = UUID.randomUUID().toString();
                JSONObject orderBody = new JSONObject()
                        .put("requestId", reqId)
                        .put("productId", productId)
                        .put("quantity", quantity)
                        .put("customerDetails", "Nạp ĐT " + phone + " · " + ReadSummaryFormatter.money(selectedTopupAmount));

                api.post("/orders", orderBody.toString()).addOnCompleteListener(orderTask -> {
                    if (!isAdded() || binding == null) return;
                    setBusy(false);
                    if (orderTask.isSuccessful() && orderTask.getResult() != null
                            && orderTask.getResult().statusCode >= 200 && orderTask.getResult().statusCode < 300) {
                        try {
                            JSONObject orderResult = new JSONObject(orderTask.getResult().body);
                            String billId = orderResult.getString("id");
                            startCheckout(billId, "Nạp điện thoại (" + phone + ")", "Mã đơn nạp: " + billId);
                        } catch (Exception e) {
                            checkoutStatus("Không thể phân tích đơn hàng nạp điện thoại.");
                        }
                    } else {
                        checkoutStatus("Không thể tạo đơn nạp tiền (HTTP " +
                                (orderTask.getResult() == null ? -1 : orderTask.getResult().statusCode) + ").");
                    }
                });
            } catch (Exception e) {
                setBusy(false);
                checkoutStatus("Lỗi cấu trúc dữ liệu đơn nạp tiền.");
            }
        });
    }

    private void handleLookup() {
        String code = binding.customerCodeInput.getText() == null ? "" : binding.customerCodeInput.getText().toString().trim();
        if (code.isEmpty()) {
            binding.customerCodeInput.setError("Vui lòng nhập mã tra cứu");
            return;
        }
        binding.lookupResultText.setVisibility(View.VISIBLE);
        binding.lookupResultText.setText("Đang tra cứu từ hệ thống backend…");
        setBusy(true);

        api.get("/bills").addOnCompleteListener(task -> {
            if (!isAdded() || binding == null) return;
            setBusy(false);
            if (task.isSuccessful() && task.getResult() != null && task.getResult().statusCode == 200) {
                try {
                    JSONObject json = new JSONObject(task.getResult().body);
                    JSONArray items = json.optJSONArray("items");
                    if (items != null) {
                        for (int i = 0; i < items.length(); i++) {
                            JSONObject bill = items.getJSONObject(i);
                            String invoiceRef = bill.optString("invoiceRef", "");
                            String id = bill.optString("id", "");
                            if (invoiceRef.equalsIgnoreCase(code) || id.equalsIgnoreCase(code)) {
                                binding.lookupResultText.setText("Đã tìm thấy hóa đơn: " + invoiceRef + " (" +
                                        ReadSummaryFormatter.money(bill.optLong("amountMinor", 0)) + " " +
                                        bill.optString("currency", "VND") + ")");
                                startCheckout(id, bill.optString("provider", "Hóa đơn dịch vụ"), invoiceRef);
                                return;
                            }
                        }
                    }
                } catch (Exception ignored) { }
            }
            binding.lookupResultText.setText("Không tìm thấy kỳ hóa đơn chưa thanh toán cho mã: " + code +
                    "\n(Bạn có thể chọn một trong các hóa đơn chờ thanh toán trong danh sách trên)");
        });
    }

    // ============================================================
    // CHECKOUT FLOW
    // ============================================================
    private void startCheckout(String billId, String serviceTitle, String reference) {
        currentBillId = billId;
        currentServiceName = serviceTitle;
        currentReference = reference;
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
        }
        showCheckoutView();
        preparePaymentIntent();
    }

    private void showCheckoutView() {
        if (binding == null) return;
        binding.hubSelectionContainer.setVisibility(View.GONE);
        binding.checkoutContainer.setVisibility(View.VISIBLE);
        binding.hubMainTitle.setText("Thanh toán dịch vụ");
        binding.hubSubtitle.setText("Xác thực thông tin và hoàn tất giao dịch");
        binding.checkoutServiceTitle.setText(currentServiceName);
        binding.checkoutReferenceText.setText("Mã hóa đơn / tham chiếu: " + currentReference);
        renderCheckoutStep();
    }

    private void preparePaymentIntent() {
        if (currentBillId == null || busy) return;
        setBusy(true);
        checkoutStatus("Đang thiết lập giao dịch từ backend…");
        try {
            JSONObject body = new JSONObject()
                    .put("billId", currentBillId)
                    .put("requestId", requestId);

            api.post("/payments/intents", body.toString()).addOnCompleteListener(task -> {
                if (!isAdded() || binding == null) return;
                setBusy(false);
                if (!task.isSuccessful()) {
                    checkoutStatus("Không kết nối được backend để khởi tạo giao dịch.");
                    return;
                }
                if (!ok(task.getResult())) {
                    checkoutStatus(errorMessage(task.getResult(), "Không thể khởi tạo giao dịch thanh toán"));
                    return;
                }
                try {
                    JSONObject result = new JSONObject(task.getResult().body);
                    String preparedIntentId = result.getString("intentId");
                    if (!preparedIntentId.equals(intentId)) {
                        challengeId = null;
                        faceVerified = false;
                    }
                    intentId = preparedIntentId;
                    requestId = result.optString("requestId", requestId);
                    faceRequired = result.optBoolean("faceRequired", false);
                    recoverPaymentAndIntent();
                } catch (Exception e) {
                    checkoutStatus("Phản hồi backend không hợp lệ.");
                }
            });
        } catch (Exception e) {
            setBusy(false);
            checkoutStatus("Dữ liệu thanh toán không hợp lệ.");
        }
    }

    private void renderCheckoutStep() {
        if (binding == null) return;
        // Display authoritative price, fee, currency from backend
        binding.checkoutAmountText.setText(ReadSummaryFormatter.money(authoritativeAmount) + " " + authoritativeCurrency);
        binding.checkoutFeeText.setText(authoritativeFee == 0 ? "Miễn phí (0 " + authoritativeCurrency + ")" :
                ReadSummaryFormatter.money(authoritativeFee) + " " + authoritativeCurrency);
        binding.checkoutTotalText.setText(ReadSummaryFormatter.money(authoritativeAmount + authoritativeFee) + " " + authoritativeCurrency);
        binding.checkoutIntentIdText.setText("Intent ID: " + (intentId == null ? "Đang tạo..." : intentId));

        boolean needFace = faceRequired && !faceVerified && !commitPending;
        binding.checkoutFaceStepContainer.setVisibility(needFace ? View.VISIBLE : View.GONE);

        boolean canOtp = (!faceRequired || faceVerified) && !commitPending && !completed;
        binding.checkoutOtpStepContainer.setVisibility(canOtp ? View.VISIBLE : View.GONE);
        binding.checkoutBtnVerifyOtp.setEnabled(!busy && challengeId != null);

        binding.checkoutCommitContainer.setVisibility(commitPending ? View.VISIBLE : View.GONE);
        binding.checkoutPendingBanner.setVisibility(commitPending ? View.VISIBLE : View.GONE);
        binding.checkoutBtnRecover.setVisibility(commitPending ? View.VISIBLE : View.GONE);
        binding.checkoutBtnCommitPay.setVisibility(View.GONE);
    }

    private void verifyFace(byte[] image) {
        if (binding == null || intentId == null) return;
        setBusy(true);
        checkoutStatus("Đang đối chiếu khuôn mặt với hồ sơ…");
        api.verifyIntentFace(intentId, image).addOnCompleteListener(task -> {
            if (!isAdded() || binding == null) return;
            setBusy(false);
            if (!task.isSuccessful()) faceError("Không nhận được kết quả xác minh. Vui lòng thử lại.");
            else if (!ok(task.getResult())) faceError(errorMessage(task.getResult(), "Xác minh khuôn mặt thất bại"));
            else {
                faceVerified = true;
                checkoutStatus("Xác minh khuôn mặt thành công.");
                renderCheckoutStep();
                requestOtp();
            }
        });
    }

    private void faceError(String message) {
        if (binding == null) return;
        setBusy(false);
        checkoutStatus(message);
    }

    private void requestOtp() {
        if (busy || intentId == null || commitPending || (faceRequired && !faceVerified)) return;
        setBusy(true);
        checkoutStatus("Đang tạo mã xác nhận demo…");
        api.post("/intents/" + intentId + "/otp", "{}").addOnCompleteListener(task -> {
            if (!isAdded() || binding == null) return;
            setBusy(false);
            if (!task.isSuccessful() || task.getResult() == null) {
                startOtpTimer();
                checkoutStatus("Chưa nhận được mã. Vui lòng chờ để tạo mã mới.");
                return;
            }
            BackendApiClient.Response response = task.getResult();
            if (!ok(response)) {
                checkoutStatus(errorMessage(response, "Không thể tạo mã xác nhận"));
                return;
            }
            try {
                JSONObject res = new JSONObject(response.body);
                challengeId = res.getString("challengeId");
                binding.checkoutDemoCode.setText(res.getString("code"));
                binding.checkoutOtpInput.setText("");
                startOtpTimer();
                renderCheckoutStep();
                checkoutStatus("Nhập mã demo đang hiển thị để xác nhận thanh toán.");
            } catch (Exception e) {
                checkoutStatus("Phản hồi mã OTP không hợp lệ.");
            }
        });
    }

    private void startOtpTimer() {
        if (otpTimer != null) otpTimer.cancel();
        binding.checkoutOtpTimer.setClickable(false);
        otpTimer = new CountDownTimer(30000, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                if (binding != null) {
                    binding.checkoutOtpTimer.setText(String.format(Locale.getDefault(),
                            "Tạo mã mới sau 00:%02d", millisUntilFinished / 1000));
                }
            }
            @Override
            public void onFinish() {
                if (binding != null) {
                    binding.checkoutOtpTimer.setText("Tạo mã mới");
                    binding.checkoutOtpTimer.setClickable(true);
                    binding.checkoutOtpTimer.setOnClickListener(v -> requestOtp());
                }
            }
        }.start();
    }

    private void verifyOtp() {
        if (busy || intentId == null || challengeId == null) return;
        String code = binding.checkoutOtpInput.getText() == null ? "" : binding.checkoutOtpInput.getText().toString().trim();
        if (!code.matches("[0-9]{6}")) {
            checkoutStatus("Nhập mã xác nhận gồm 6 chữ số.");
            return;
        }
        setBusy(true);
        checkoutStatus("Đang kiểm tra mã xác nhận…");
        api.verifyIntentOtp(new OtpVerification(intentId, challengeId, code)).addOnCompleteListener(task -> {
            if (!isAdded() || binding == null) return;
            if (!task.isSuccessful() || task.getResult() == null || task.getResult().statusCode >= 500) {
                commitPending = true;
                recoverPaymentAndIntent();
            } else if (ok(task.getResult())) {
                setBusy(false);
                try {
                    if (new JSONObject(task.getResult().body).optBoolean("authorized")) {
                        commitPayment();
                        return;
                    }
                } catch (org.json.JSONException ignored) {
                    // Read the intent state if the verification response is incomplete.
                }
                recoverPaymentAndIntent();
            } else {
                setBusy(false);
                String err = errorCode(task.getResult());
                if ("OTP_USED".equals(err)) recoverPaymentAndIntent();
                else checkoutStatus(errorMessage(task.getResult(), "Mã xác nhận bị từ chối"));
            }
        });
    }

    private void commitPayment() {
        if (busy || completed || currentBillId == null || requestId == null) return;
        if (paymentSheet == null) {
            checkoutStatus("Thiếu STRIPE_PUBLISHABLE_KEY (pk_test_...) trong local.properties. Thêm key rồi cài lại bản debug.");
            return;
        }
        commitPending = true;
        setBusy(true);
        renderCheckoutStep();
        checkoutStatus("Đang tiến hành thanh toán & ghi sổ…");

        try {
            JSONObject body = new JSONObject()
                    .put("billId", currentBillId)
                    .put("requestId", requestId);

            api.post("/payments", body.toString()).addOnCompleteListener(task -> {
                if (!isAdded() || binding == null) return;
                setBusy(false);
                if (!task.isSuccessful() || task.getResult() == null || task.getResult().statusCode >= 500) {
                    checkoutStatus("Chưa có kết quả cuối cùng từ đối tác. Bấm kiểm tra lại (COMMIT_PENDING).");
                    renderCheckoutStep();
                    return;
                }
                BackendApiClient.Response response = task.getResult();
                if (ok(response)) {
                    try {
                        JSONObject p = new JSONObject(response.body);
                        String status = p.optString("status", "");
                        if ("SUCCEEDED".equalsIgnoreCase(status)) {
                            finishPaymentSuccess();
                            return;
                        }
                        String clientSecret = p.optString("clientSecret", "");
                        if (!clientSecret.isEmpty()) {
                            renderCheckoutStep();
                            checkoutStatus("Nhập thông tin thẻ test trong Stripe để hoàn tất thanh toán.");
                            paymentSheet.presentWithPaymentIntent(clientSecret,
                                    new PaymentSheet.Configuration.Builder("Nimbus Bank").build());
                            return;
                        }
                    } catch (Exception ignored) { }
                    // If COMMIT_PENDING or other gateway state, recover authoritative settlement
                    recoverPaymentAndIntent();
                    return;
                }
                commitPending = false;
                String code = errorCode(response);
                if ("PAYMENT_IN_PROGRESS".equals(code) || "INVALID_AMOUNT".equals(code)) {
                    preparePaymentIntent();
                    return;
                }
                if ("OTP_REQUIRED".equals(code) || "OTP_EXPIRED".equals(code)) {
                    recoverPaymentAndIntent(false, false);
                    return;
                }
                renderCheckoutStep();
                checkoutStatus(errorMessage(response, "Thanh toán bị từ chối"));
            });
        } catch (Exception e) {
            setBusy(false);
            checkoutStatus("Không thể tạo yêu cầu ghi sổ thanh toán.");
        }
    }

    private void onPaymentSheetResult(@NonNull PaymentSheetResult result) {
        if (!isAdded() || binding == null) return;
        setBusy(false);
        commitPending = true;
        renderCheckoutStep();
        if (result instanceof PaymentSheetResult.Completed) {
            checkoutStatus("Stripe đã xác nhận thẻ. Đang đợi webhook cập nhật hóa đơn…");
            settlementPollsRemaining = 20;
            recoverPaymentAndIntent();
        } else if (result instanceof PaymentSheetResult.Canceled) {
            checkoutStatus("Bạn đã đóng màn thanh toán. Bấm Tiếp tục thanh toán để thử lại.");
        } else if (result instanceof PaymentSheetResult.Failed) {
            PaymentSheetResult.Failed failed = (PaymentSheetResult.Failed) result;
            String message = failed.getError().getLocalizedMessage();
            checkoutStatus("Stripe chưa hoàn tất thanh toán" +
                    (message == null || message.trim().isEmpty() ? ". Bạn có thể thử lại." : ": " + message));
        }
        renderCheckoutStep();
    }

    private void recoverPaymentAndIntent() {
        recoverPaymentAndIntent(false, true);
    }

    private void recoverPaymentAndIntent(boolean resumePendingPayment, boolean submitVerifiedOtp) {
        if (intentId == null || completed) return;
        setBusy(true);
        checkoutStatus("Đang khôi phục và kiểm tra kết quả giao dịch…");

        api.get("/intents/" + intentId).addOnCompleteListener(iTask -> {
            if (!isAdded() || binding == null) return;
            setBusy(false);
            if (!iTask.isSuccessful() || iTask.getResult() == null || !ok(iTask.getResult())) {
                commitPending = true;
                renderCheckoutStep();
                checkoutStatus("Chưa nhận được kết quả cuối cùng. Vui lòng bấm kiểm tra lại.");
                return;
            }
            try {
                JSONObject intent = new JSONObject(iTask.getResult().body);
                String state = intent.getString("status");
                authoritativeAmount = intent.optLong("amountMinor", authoritativeAmount);
                authoritativeFee = intent.optLong("feeMinor", authoritativeFee);
                authoritativeCurrency = intent.optString("currency", authoritativeCurrency);
                JSONObject tgt = intent.optJSONObject("target");
                if (tgt != null) authoritativeTarget = tgt.optString("id", authoritativeTarget);

                faceRequired = intent.optBoolean("faceRequired", faceRequired);
                faceVerified = "VERIFIED".equalsIgnoreCase(intent.optString("faceStatus"));
                String challenge = intent.optString("challengeStatus");

                if ("COMMITTED".equalsIgnoreCase(state)) {
                    finishPaymentSuccess();
                    return;
                }
                if ("FAILED".equalsIgnoreCase(state) || "CANCELLED".equalsIgnoreCase(state)) {
                    commitPending = false;
                    renderCheckoutStep();
                    checkoutStatus("Giao dịch thanh toán thất bại hoặc đã hủy.");
                    return;
                }
                if ("COMMIT_PENDING".equalsIgnoreCase(state)) {
                    commitPending = true;
                    renderCheckoutStep();
                    if (resumePendingPayment && settlementPollsRemaining == 0) {
                        commitPayment();
                        return;
                    }
                    checkoutStatus(settlementPollsRemaining > 0
                            ? "Đang chờ Stripe xác nhận kết quả…"
                            : "Bấm Tiếp tục thanh toán để mở lại giao dịch Stripe.");
                    if (settlementPollsRemaining > 0) {
                        settlementHandler.removeCallbacks(settlementPoll);
                        settlementHandler.postDelayed(settlementPoll, 1500);
                    }
                    return;
                }

                commitPending = false;
                renderCheckoutStep();
                if ("EXPIRED".equalsIgnoreCase(state)) {
                    checkoutStatus("Giao dịch đã hết hạn. Bấm Hủy / Sửa để tạo lại.");
                } else if (submitVerifiedOtp && "CREATED".equalsIgnoreCase(state)
                        && "VERIFIED".equalsIgnoreCase(challenge)
                        && (!faceRequired || faceVerified)) {
                    commitPayment();
                } else if ("REQUIRED".equalsIgnoreCase(challenge)) {
                    requestOtp();
                } else if ("EXPIRED".equalsIgnoreCase(challenge)) {
                    checkoutStatus("Mã xác nhận đã hết hạn (OTP_EXPIRED). Bấm tạo mã mới.");
                } else if ("LOCKED".equalsIgnoreCase(challenge)) {
                    checkoutStatus("Giao dịch bị khóa do nhập sai quá 5 lần (OTP_LOCKED). Cần hủy và tạo lại.");
                } else if (faceRequired && !faceVerified) {
                    checkoutStatus("Xác minh khuôn mặt để tiếp tục thanh toán.");
                } else if ("AWAITING_CODE".equalsIgnoreCase(challenge) && challengeId != null) {
                    checkoutStatus("Nhập mã demo đang hiển thị để xác nhận thanh toán.");
                } else {
                    checkoutStatus("Bấm tạo mã mới để tiếp tục xác thực thanh toán.");
                }
            } catch (Exception e) {
                commitPending = true;
                renderCheckoutStep();
                checkoutStatus("Chưa đọc được dữ liệu phục hồi. Bấm kiểm tra lại.");
            }
        });
    }

    private void cancelIntentForEdit() {
        if (busy || commitPending) return;
        if (intentId == null) {
            returnToHub();
            return;
        }
        setBusy(true);
        checkoutStatus("Đang hủy yêu cầu cũ trên backend…");
        api.post("/intents/" + intentId + "/cancel", "{}").addOnCompleteListener(task -> {
            if (!isAdded() || binding == null) return;
            setBusy(false);
            if (!task.isSuccessful() || task.getResult() == null || !ok(task.getResult())) {
                checkoutStatus("Chưa hủy được yêu cầu trên backend. Kiểm tra kết nối rồi thử lại.");
                return;
            }
            intentId = null;
            requestId = null;
            challengeId = null;
            faceVerified = false;
            commitPending = false;
            returnToHub();
        });
    }

    private void returnToHub() {
        if (binding == null) return;
        binding.checkoutContainer.setVisibility(View.GONE);
        binding.hubSelectionContainer.setVisibility(View.VISIBLE);
        binding.hubMainTitle.setText("Tiện ích & Thanh toán");
        binding.hubSubtitle.setText("Thanh toán hóa đơn thiết yếu, nạp tiền điện thoại & dịch vụ");
        loadOwnedBills();
    }

    private void finishPaymentSuccess() {
        completed = true;
        commitPending = false;
        if (binding == null) return;
        binding.checkoutFaceStepContainer.setVisibility(View.GONE);
        binding.checkoutOtpStepContainer.setVisibility(View.GONE);
        binding.checkoutCommitContainer.setVisibility(View.GONE);
        binding.checkoutSuccessContainer.setVisibility(View.VISIBLE);
        binding.receiptAmountDisplay.setText(ReadSummaryFormatter.money(authoritativeAmount + authoritativeFee) + " " + authoritativeCurrency);
        binding.receiptDetailsText.setText("Dịch vụ: " + currentServiceName + "\nTham chiếu: " + currentReference +
                "\nMã giao dịch: " + intentId + "\nTrạng thái: ĐÃ QUYẾT TOÁN THÀNH CÔNG (SETTLED)");
        checkoutStatus("");
    }

    private void checkoutStatus(String msg) {
        if (binding != null) {
            binding.checkoutStatusText.setText(msg);
        }
    }

    private void setBusy(boolean busy) {
        this.busy = busy;
        if (binding != null) {
            binding.btnProceedTopup.setEnabled(!busy);
            binding.lookupButton.setEnabled(!busy);
            binding.checkoutBtnFace.setEnabled(!busy);
            binding.checkoutBtnVerifyOtp.setEnabled(!busy && challengeId != null);
            binding.checkoutBtnCommitPay.setEnabled(!busy);
            binding.checkoutBtnRecover.setEnabled(!busy);
        }
    }

    private boolean ok(BackendApiClient.Response response) {
        return response != null && response.statusCode >= 200 && response.statusCode < 300;
    }

    private String errorCode(BackendApiClient.Response response) {
        if (response == null) return "";
        try { return new JSONObject(response.body).optString("error", ""); }
        catch (Exception ignored) { return ""; }
    }

    private String errorMessage(BackendApiClient.Response response, String fallback) {
        String code = errorCode(response);
        switch (code) {
            case "INVALID_OTP": return "Mã xác nhận không đúng. Vui lòng kiểm tra lại.";
            case "OTP_EXPIRED": return "Mã xác nhận đã hết hạn (OTP_EXPIRED). Tạo mã mới.";
            case "OTP_LOCKED": return "Đã nhập sai quá 5 lần, giao dịch bị khóa (OTP_LOCKED). Hủy để tạo lại.";
            case "OTP_USED": return "Mã đã được sử dụng. Đang kiểm tra trạng thái giao dịch.";
            case "RATE_LIMITED": return "Thao tác quá nhanh (RATE_LIMITED). Vui lòng đợi trong giây lát.";
            case "EXPIRED":
            case "INTENT_CHANGED": return "Giao dịch đã hết hạn hoặc bị thay đổi. Cần tạo lại giao dịch mới.";
            case "FACE_REQUIRED": return "Giao dịch yêu cầu đối chiếu khuôn mặt.";
            case "STEP_UP_UNAVAILABLE":
            case "FACE_SETUP_REQUIRED": return "Máy chủ chưa sẵn sàng xác minh khuôn mặt.";
            case "FACE_MISMATCH": return "Khuôn mặt không khớp với hồ sơ đã duyệt.";
            case "FACE_NOT_FOUND":
            case "MULTIPLE_FACES":
            case "FACE_LOW_QUALITY": return "Chụp rõ một khuôn mặt trong khung hình, đủ ánh sáng.";
            case "IDEMPOTENCY_CONFLICT": return "Trùng lặp mã yêu cầu với nội dung khác.";
            default: return code.isEmpty() ? fallback + " (HTTP " + (response == null ? -1 : response.statusCode) + ")." :
                    fallback + " (" + code + ").";
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() != null && getDialog().getWindow() != null) {
            getDialog().getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            getDialog().getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
    }

    @Override
    public void onDestroyView() {
        if (otpTimer != null) {
            otpTimer.cancel();
            otpTimer = null;
        }
        settlementHandler.removeCallbacks(settlementPoll);
        binding = null;
        busy = false;
        completed = false;
        super.onDestroyView();
    }
}

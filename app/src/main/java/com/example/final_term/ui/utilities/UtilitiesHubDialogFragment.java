package com.example.final_term.ui.utilities;

import android.graphics.Rect;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.text.Editable;
import android.text.TextWatcher;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;

import com.google.android.gms.tasks.Tasks;
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
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public class UtilitiesHubDialogFragment extends DialogFragment {
    private com.example.final_term.ui.common.TransactionSheet authorizationSheet;
    private com.example.final_term.ui.transaction.OtpBottomSheetDialog sharedOtp;
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
    private boolean checkoutConfirmed;

    // Authoritative intent fields from backend
    private long authoritativeAmount;
    private long authoritativeFee;
    private String authoritativeCurrency = "VND";
    private String authoritativeTarget = "";

    private long selectedTopupAmount = 20000;
    private String selectedTopupCarrier;
    private String selectedTopupCarrierName;
    private CountDownTimer otpTimer;
    private boolean preparingPreviously;
    private String pendingOrder;
    private String activeCatalogCategory = "FLIGHT";
    private int catalogRequestVersion;
    private boolean checkoutOnly;

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

    public static UtilitiesHubDialogFragment newCategoryInstance(String category) {
        UtilitiesHubDialogFragment fragment = new UtilitiesHubDialogFragment();
        Bundle args = new Bundle();
        args.putString("openCategory", category);
        fragment.setArguments(args);
        return fragment;
    }

    public static UtilitiesHubDialogFragment newTopupInstance() {
        UtilitiesHubDialogFragment fragment = new UtilitiesHubDialogFragment();
        Bundle args = new Bundle();
        args.putBoolean("openTopup", true);
        fragment.setArguments(args);
        return fragment;
    }

    public static UtilitiesHubDialogFragment newCheckoutInstance(String billId, @Nullable String sourceAccountId) {
        UtilitiesHubDialogFragment fragment = new UtilitiesHubDialogFragment();
        Bundle args = new Bundle();
        args.putString("billId", billId);
        args.putString("sourceAccountId", sourceAccountId);
        args.putBoolean("checkoutOnly", true);
        fragment.setArguments(args);
        return fragment;
    }

    public static UtilitiesHubDialogFragment newOrderCheckoutInstance(String category, String productId,
                                                                       @Nullable String customerDetails,
                                                                       @Nullable String sourceAccountId) {
        UtilitiesHubDialogFragment fragment = new UtilitiesHubDialogFragment();
        Bundle args = new Bundle();
        args.putString("orderCategory", category);
        args.putString("productId", productId);
        args.putString("orderCustomerDetails", customerDetails);
        args.putString("sourceAccountId", sourceAccountId);
        args.putBoolean("checkoutOnly", true);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        faceCamera.restore(savedInstanceState);
        if (savedInstanceState != null) {
            checkoutOnly = savedInstanceState.getBoolean("checkoutOnly");
            checkoutConfirmed = savedInstanceState.getBoolean("checkoutConfirmed");
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
            if (savedInstanceState.getBoolean("simulatedPayment")) {
                requestId = null;
                intentId = null;
                challengeId = null;
                faceRequired = false;
                faceVerified = false;
                commitPending = false;
            }
            pendingOrder = savedInstanceState.getString("pendingOrder");
            preparingPreviously = requestId != null;
            selectedTopupAmount = savedInstanceState.getLong("selectedTopupAmount", 20000);
            selectedTopupCarrier = savedInstanceState.getString("selectedTopupCarrier");
            selectedTopupCarrierName = savedInstanceState.getString("selectedTopupCarrierName");
        } else if (getArguments() != null) {
            currentBillId = getArguments().getString("billId");
            checkoutOnly = getArguments().getBoolean("checkoutOnly", false);
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        faceCamera.save(outState);
        outState.putString("currentBillId", currentBillId);
        outState.putBoolean("checkoutOnly", checkoutOnly);
        outState.putBoolean("checkoutConfirmed", checkoutConfirmed);
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
        outState.putString("pendingOrder", pendingOrder);
        outState.putLong("selectedTopupAmount", selectedTopupAmount);
        outState.putString("selectedTopupCarrier", selectedTopupCarrier);
        outState.putString("selectedTopupCarrierName", selectedTopupCarrierName);
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
        faceCamera.attach();
        FinalTermApplication app = (FinalTermApplication) requireActivity().getApplication();
        api = app.getAppContainer().getBackendApiClient();
        if (BuildConfig.STRIPE_PUBLISHABLE_KEY.startsWith("pk_test_")) {
            PaymentConfiguration.init(requireContext(), BuildConfig.STRIPE_PUBLISHABLE_KEY);
            paymentSheet = new PaymentSheet.Builder(this::onPaymentSheetResult).build(this);
        }

        binding.closeDialogBtn.setOnClickListener(v -> dismiss());
        binding.utilityBackButton.setOnClickListener(v -> returnToUtilities());
        setupUtilitySearch();
        binding.utilAll.setOnClickListener(v -> openCatalog());
        binding.pendingOrderButton.setOnClickListener(v -> recoverOrder());
        binding.orderHistoryButton.setOnClickListener(v -> showOrders());
        binding.pendingOrderButton.setVisibility(pendingOrder == null ? View.GONE : View.VISIBLE);

        binding.utilElectricity.setOnClickListener(v -> showBillLookup(
                "Tiền điện", "Mã khách hàng điện"));
        binding.utilWater.setOnClickListener(v -> showBillLookup(
                "Tiền nước", "Mã khách hàng nước"));
        binding.utilMobile.setOnClickListener(v -> {
            showUtilityDetail("Nạp điện thoại");
            binding.topupFormContainer.setVisibility(View.VISIBLE);
            scrollToSection(binding.topupFormContainer);
        });
        binding.utilFlight.setOnClickListener(v -> openCatalogCategory("FLIGHT"));
        binding.utilMovie.setOnClickListener(v -> openCatalogCategory("MOVIE"));
        binding.utilHotel.setOnClickListener(v -> openCatalogCategory("HOTEL"));
        binding.utilShopping.setOnClickListener(v -> openCatalogCategory("ECOMMERCE"));

        // Topup chips
        setupTopupChips();
        renderTopupCarrier();
        binding.topupCarrierPicker.setOnClickListener(v -> chooseTopupCarrier());

        // Topup proceed button
        binding.btnProceedTopup.setOnClickListener(v -> proceedTopupOrder());

        // Lookup button
        binding.lookupButton.setOnClickListener(v -> handleLookup());

        // Checkout controls
        binding.checkoutBtnCancelEdit.setOnClickListener(v -> cancelIntentForEdit());
        binding.checkoutConfirmButton.setOnClickListener(v -> {
            checkoutConfirmed = true;
            renderCheckoutStep();
            if (faceRequired && !faceVerified) checkoutStatus("Xác minh khuôn mặt để tiếp tục thanh toán.");
            else requestOtp();
        });
        binding.checkoutBtnFace.setOnClickListener(v -> {
            if (busy) return;
            faceCamera.open();
        });
        binding.checkoutOtpTimer.setOnClickListener(v -> requestOtp());
        binding.checkoutOtpClose.setOnClickListener(v -> cancelIntentForEdit());
        binding.checkoutBtnVerifyOtp.setOnClickListener(v -> verifyOtp());
        binding.checkoutBtnRecover.setOnClickListener(v -> {
            if (intentId == null) preparePaymentIntent();
            else recoverPaymentAndIntent(true, true);
        });
        binding.receiptBtnDone.setOnClickListener(v -> dismiss());

        if (checkoutOnly) {
            binding.hubSelectionContainer.setVisibility(View.GONE);
            binding.checkoutContainer.setVisibility(View.VISIBLE);
            binding.hubMainTitle.setText("Thanh toán");
            checkoutStatus("Đang chuẩn bị thông tin thanh toán...");
        }

        if (getArguments() != null) {
            if (getArguments().getBoolean("openTopup")) binding.utilMobile.performClick();
            String openCategory = getArguments().getString("openCategory");
            if (openCategory != null) openCatalogCategory(openCategory);
        }

        if (!checkoutOnly) loadOwnedBills();

        // If restoring in middle of checkout
        String orderCategory = getArguments() == null ? null : getArguments().getString("orderCategory");
        String productId = getArguments() == null ? null : getArguments().getString("productId");
        if (intentId != null) {
            showCheckoutView();
            recoverPaymentAndIntent();
        } else if (orderCategory != null && productId != null) {
            if (pendingOrder != null) recoverOrder();
            else createCheckoutOrder(orderCategory, productId, getArguments().getString("orderCustomerDetails"));
        } else if (currentBillId != null) {
            if (requestId != null) {
                showCheckoutView();
                preparePaymentIntent();
            } else lookupBill(currentBillId);
        }
    }

    private void showBillLookup(String title, String hint) {
        showUtilityDetail(title);
        binding.topupFormContainer.setVisibility(View.GONE);
        binding.lookupFormContainer.setVisibility(View.VISIBLE);
        binding.lookupTitle.setText("Nhập mã khách hàng");
        binding.customerCodeInput.setHint(hint);
        binding.customerCodeInput.setText("");
        binding.hubStatusText.setVisibility(View.GONE);
        binding.lookupResultText.setVisibility(View.GONE);
        scrollToSection(binding.lookupFormContainer);
    }

    private void showUtilityDetail(String title) {
        binding.hubMainTitle.setText(title);
        binding.utilityBackButton.setVisibility(View.VISIBLE);
        binding.utilitySearchContainer.setVisibility(View.GONE);
        binding.utilityIntroCard.setVisibility(View.GONE);
        binding.utilityGridHeading.setVisibility(View.GONE);
        binding.utilityGridRowOne.setVisibility(View.GONE);
        binding.utilityGridRowTwo.setVisibility(View.GONE);
        binding.utilityBillsHeading.setVisibility(View.GONE);
        binding.pendingOrderButton.setVisibility(View.GONE);
        binding.orderHistoryButton.setVisibility(View.GONE);
        binding.billsListContainer.setVisibility(View.GONE);
    }

    private void returnToUtilities() {
        catalogRequestVersion++;
        binding.hubMainTitle.setText("Tiện ích & Thanh toán");
        binding.utilityBackButton.setVisibility(View.GONE);
        binding.utilitySearchContainer.setVisibility(View.VISIBLE);
        binding.utilityIntroCard.setVisibility(View.VISIBLE);
        binding.utilityGridHeading.setVisibility(View.VISIBLE);
        binding.utilityGridRowOne.setVisibility(View.VISIBLE);
        binding.utilityGridRowTwo.setVisibility(View.VISIBLE);
        binding.utilityBillsHeading.setVisibility(View.VISIBLE);
        binding.pendingOrderButton.setVisibility(pendingOrder == null ? View.GONE : View.VISIBLE);
        binding.orderHistoryButton.setVisibility(View.VISIBLE);
        binding.billsListContainer.setVisibility(View.VISIBLE);
        binding.topupFormContainer.setVisibility(View.GONE);
        binding.lookupFormContainer.setVisibility(View.GONE);
        binding.lookupResultText.setVisibility(View.GONE);
        binding.hubStatusText.setVisibility(View.GONE);
        binding.utilitySearchInput.setText("");
        loadOwnedBills();
    }

    private void setupUtilitySearch() {
        binding.utilitySearchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().trim().toLowerCase(Locale.ROOT);
                filterUtilityTile(binding.utilElectricity, "điện electricity", query);
                filterUtilityTile(binding.utilWater, "nước water", query);
                filterUtilityTile(binding.utilMobile, "nạp điện thoại nạp đt mobile topup", query);
                filterUtilityTile(binding.utilFlight, "vé máy bay flight", query);
                filterUtilityTile(binding.utilMovie, "vé phim movie", query);
                filterUtilityTile(binding.utilHotel, "khách sạn hotel", query);
                filterUtilityTile(binding.utilShopping, "mua sắm ecommerce", query);
                filterUtilityTile(binding.utilAll, "khác tất cả", query);
            }
            @Override public void afterTextChanged(Editable s) { }
        });
    }

    private void filterUtilityTile(View tile, String terms, String query) {
        tile.setVisibility(query.isEmpty() || terms.contains(query) ? View.VISIBLE : View.GONE);
    }

    private void scrollToSection(View section) {
        var scroll = binding.getRoot();
        scroll.post(() -> {
            if (binding == null || binding.getRoot() != scroll || !section.isShown()) return;
            Rect bounds = new Rect();
            section.getDrawingRect(bounds);
            scroll.offsetDescendantRectToMyCoords(section, bounds);
            scroll.smoothScrollTo(0, bounds.top);
        });
    }

    private void setupTopupChips() {
        long[] amounts = {20000, 50000, 100000, 200000};
        TextView[] chips = {binding.chipTopup20k, binding.chipTopup50k, binding.chipTopup100k, binding.chipTopup200k};
        for (int i = 0; i < amounts.length; i++) {
            long amount = amounts[i];
            TextView chip = chips[i];
            chip.setOnClickListener(v -> selectTopupChip(amount, chip));
            if (amount == selectedTopupAmount) selectTopupChip(amount, chip);
        }
    }

    private void selectTopupChip(long amount, TextView selectedView) {
        if (busy) return;
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
        binding.billsEmptyOrLoadingText.setVisibility(View.VISIBLE);
        if (api == null) {
            binding.billsEmptyOrLoadingText.setText("Chưa thể tải hóa đơn. Vui lòng thử lại.");
            return;
        }
        binding.billsEmptyOrLoadingText.setText("Đang tải danh sách hóa đơn từ hệ thống…");
        api.get("/bills").addOnCompleteListener(task -> {
            if (!isAdded() || binding == null) return;
            if (!task.isSuccessful()) {
                binding.billsEmptyOrLoadingText.setText("Không thể tải hóa đơn. Kiểm tra kết nối rồi thử lại.");
                return;
            }
            BackendApiClient.Response response = task.getResult();
            if (response == null || response.statusCode != 200) {
                binding.billsEmptyOrLoadingText.setText("Không thể tải hóa đơn. Vui lòng thử lại sau.");
                return;
            }
            try {
                JSONObject json = new JSONObject(task.getResult().body);
                JSONArray items = json.optJSONArray("items");
                for (int item = binding.billsListContainer.getChildCount() - 1; item >= 0; item--) {
                    View child = binding.billsListContainer.getChildAt(item);
                    if (child != binding.billsEmptyOrLoadingText) binding.billsListContainer.removeViewAt(item);
                }
                int unpaidCount = 0;
                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject bill = items.getJSONObject(i);
                        String status = bill.optString("status", "UNPAID");
                        if ("UNPAID".equalsIgnoreCase(status)
                                && (bill.isNull("quoteExpiresAt") || bill.optLong("quoteExpiresAt") > System.currentTimeMillis())) {
                            unpaidCount++;
                            View billView = createBillItemView(bill);
                            binding.billsListContainer.addView(billView);
                        }
                    }
                }
                if (unpaidCount == 0) {
                    binding.billsEmptyOrLoadingText.setText("Không có hóa đơn hoặc đơn hàng chưa thanh toán.");
                } else {
                    binding.billsEmptyOrLoadingText.setVisibility(View.GONE);
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
        String serviceType = bill.optString("serviceType", "");

        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setBackgroundResource(R.drawable.glass_panel_card);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(8);
        card.setLayoutParams(params);

        android.widget.ImageView icon = new android.widget.ImageView(requireContext());
        int iconResource = "ELECTRICITY".equalsIgnoreCase(serviceType)
                ? R.drawable.ic_utility_electricity
                : "WATER".equalsIgnoreCase(serviceType) ? R.drawable.ic_utility_water
                : "TOPUP".equalsIgnoreCase(serviceType) ? R.drawable.ic_phone_topup
                : R.drawable.ic_receipt;
        icon.setImageResource(iconResource);
        icon.setPadding(10, 10, 10, 10);
        icon.setBackgroundResource(R.drawable.circle_action_button);
        icon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.navy));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(40), dp(40));
        iconParams.setMarginEnd(dp(12));
        card.addView(icon, iconParams);

        LinearLayout textCol = new LinearLayout(requireContext());
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView title = new TextView(requireContext());
        title.setText(serviceLabel(serviceType.isEmpty() ? "Dịch vụ" : serviceType)
                + " · " + invoiceRef
                + (bill.isNull("dueAt") ? "" : " · Hạn " + android.text.format.DateFormat.getDateFormat(requireContext())
                        .format(new java.util.Date(bill.optLong("dueAt")))));
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
        payBtn.setOnClickListener(v -> startCheckout(bill, ""));
        card.addView(payBtn);

        return card;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void proceedTopupOrder() {
        if (pendingOrder != null) {
            recoverOrder();
            return;
        }
        String phone = binding.topupPhoneInput.getText() == null ? "" : binding.topupPhoneInput.getText().toString().trim();
        if (!phone.matches("0[0-9]{9}")) {
            binding.topupPhoneInput.setError("Nhập số điện thoại gồm 10 chữ số, bắt đầu bằng 0.");
            return;
        }
        binding.topupPhoneInput.setError(null);
        if (selectedTopupCarrier == null) {
            binding.topupCarrierPicker.setError("Vui lòng chọn nhà mạng.");
            return;
        }
        loadCatalog("TOPUP", products -> {
            JSONObject product = carriersForAmount(products).get(selectedTopupCarrier);
            if (product == null) {
                selectedTopupCarrier = null;
                selectedTopupCarrierName = null;
                renderTopupCarrier();
                binding.topupCarrierPicker.setError("Mệnh giá này không có ở nhà mạng đã chọn.");
                return;
            }
            createOrderForProduct(product, phone);
        });
    }

    private void chooseTopupCarrier() {
        loadCatalog("TOPUP", products -> {
            Map<String, JSONObject> carriers = carriersForAmount(products);
            if (carriers.isEmpty()) {
                binding.topupCarrierPicker.setError("Chưa có nhà mạng cho mệnh giá này.");
                return;
            }
            List<String> networks = new ArrayList<>(carriers.keySet());
            List<String> names = new ArrayList<>();
            for (String network : networks) names.add(carriers.get(network).optString("name", network));
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Chọn nhà mạng")
                    .setItems(names.toArray(new String[0]), (dialog, index) -> {
                        selectedTopupCarrier = networks.get(index);
                        selectedTopupCarrierName = names.get(index);
                        renderTopupCarrier();
                    })
                    .show();
        });
    }

    private Map<String, JSONObject> carriersForAmount(List<JSONObject> products) {
        Map<String, JSONObject> carriers = new LinkedHashMap<>();
        for (JSONObject product : products) {
            String network = product.optString("network");
            if (!product.isNull("network") && !network.isEmpty() && "VND".equals(product.optString("currency"))
                    && product.optLong("amountMinor") == selectedTopupAmount) {
                carriers.putIfAbsent(network, product);
            }
        }
        return carriers;
    }

    private void renderTopupCarrier() {
        binding.topupCarrierPicker.setText(selectedTopupCarrierName == null ? "Chọn nhà mạng" : selectedTopupCarrierName);
        binding.topupCarrierPicker.setError(null);
    }

    private void openCatalog() {
        if (busy) return;
        if (pendingOrder != null) {
            recoverOrder();
            return;
        }
        CharSequence[] names = {
                getText(R.string.utility_electricity), getText(R.string.utility_water),
                getText(R.string.utility_mobile),
                getText(R.string.utility_flight), getText(R.string.utility_movie),
                getText(R.string.utility_hotel), getText(R.string.utility_shopping),
                getText(R.string.utility_internet)
        };
        View[] shortcuts = {
                binding.utilElectricity, binding.utilWater, binding.utilMobile,
                binding.utilFlight, binding.utilMovie, binding.utilHotel, binding.utilShopping
        };
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.utility_all)
                .setItems(names, (dialog, index) -> {
                    if (index < shortcuts.length) shortcuts[index].performClick();
                    else showBillLookup("Thanh toán cước Internet & Dịch vụ", "Nhập mã hợp đồng / mã đơn hàng");
                })
                .show();
    }

    private void openCatalogCategory(String category) {
        activeCatalogCategory = category;
        int requestVersion = ++catalogRequestVersion;
        showUtilityDetail(catalogTitle(category));
        hubStatus("Đang tải lựa chọn...");
        if (pendingOrder != null) recoverOrder();
        else loadCatalog(category, products -> {
            if (requestVersion == catalogRequestVersion && binding.utilityBackButton.getVisibility() == View.VISIBLE) {
                showCatalogProducts(products);
            }
        });
    }

    private String catalogTitle(String category) {
        switch (category) {
            case "FLIGHT": return "Vé máy bay";
            case "MOVIE": return "Vé xem phim";
            case "HOTEL": return "Khách sạn";
            default: return "Mua sắm";
        }
    }

    private void hubStatus(String message) {
        if (checkoutOnly && binding.hubSelectionContainer.getVisibility() != View.VISIBLE) {
            checkoutStatus(message);
            return;
        }
        binding.hubStatusText.setVisibility(View.VISIBLE);
        binding.hubStatusText.setText(message);
    }

    private void createCheckoutOrder(String category, String productId, @Nullable String customerDetails) {
        checkoutStatus("Đang tải thông tin dịch vụ...");
        loadCatalog(category, products -> {
            JSONObject selected = null;
            for (JSONObject product : products) {
                if (productId.equals(product.optString("id"))) {
                    selected = product;
                    break;
                }
            }
            if (selected == null) {
                checkoutStatus("Dịch vụ này hiện không còn khả dụng. Quay lại và chọn dịch vụ khác.");
                return;
            }
            if ("TOPUP".equals(category)) createOrderForProduct(selected, customerDetails);
            else createOrderForProduct(selected, null);
        });
    }

    private void loadCatalog(String category, Consumer<List<JSONObject>> onLoaded) {
        if (busy) return;
        setBusy(true);
        hubStatus("Đang tải danh mục…");
        api.get("/catalog?limit=100&serviceType=" + category).addOnCompleteListener(task -> {
            if (binding == null || !isAdded()) return;
            setBusy(false);
            try {
                if (!task.isSuccessful() || !ok(task.getResult())) throw new JSONException("Unavailable");
                JSONArray items = new JSONObject(task.getResult().body).getJSONArray("items");
                List<JSONObject> products = new ArrayList<>();
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    if (item.optBoolean("demo")) products.add(item);
                }
                binding.lookupResultText.setVisibility(View.GONE);
                binding.hubStatusText.setVisibility(View.GONE);
                onLoaded.accept(products);
            } catch (JSONException error) {
                hubStatus("Không tải được danh mục. Vui lòng thử lại.");
            }
        });
    }

    private void showCatalogProducts(List<JSONObject> products) {
        if (products.isEmpty()) {
            hubStatus("Chưa có sản phẩm phù hợp.");
            return;
        }
        android.widget.ScrollView listScroll = new android.widget.ScrollView(requireContext());
        LinearLayout list = new LinearLayout(requireContext());
        list.setOrientation(LinearLayout.VERTICAL);
        List<View> optionCards = new ArrayList<>();
        int inset = (int) (16 * getResources().getDisplayMetrics().density);
        list.setPadding(inset, inset / 2, inset, inset / 2);
        listScroll.addView(list);
        for (JSONObject product : products) {
            LinearLayout card = new LinearLayout(requireContext());
            card.setOrientation(LinearLayout.VERTICAL);
            card.setFocusable(true);
            card.setClickable(true);
            card.setBackgroundResource(R.drawable.glass_panel_card);
            card.setPadding(inset, inset, inset, inset);
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardParams.bottomMargin = inset / 2;
            card.setLayoutParams(cardParams);

            TextView name = new TextView(requireContext());
            name.setText(product.optString("name", "Dịch vụ"));
            name.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink));
            name.setTextSize(15);
            name.setTypeface(null, android.graphics.Typeface.BOLD);
            card.addView(name);

            TextView amount = new TextView(requireContext());
            amount.setText(ReadSummaryFormatter.money(product.optLong("amountMinor"))
                    + " " + product.optString("currency", "VND"));
            amount.setTextColor(ContextCompat.getColor(requireContext(), R.color.navy));
            amount.setTextSize(14);
            LinearLayout.LayoutParams amountParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            amountParams.topMargin = inset / 3;
            card.addView(amount, amountParams);
            list.addView(card);
            optionCards.add(card);
        }
        androidx.appcompat.app.AlertDialog chooser = new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(catalogTitle(activeCatalogCategory))
                .setView(listScroll)
                .setNegativeButton("Đóng", null)
                .show();
        for (int i = 0; i < optionCards.size(); i++) {
            final JSONObject product = products.get(i);
            optionCards.get(i).setOnClickListener(v -> {
                chooser.dismiss();
                createOrderForProduct(product, null);
            });
        }
    }

    private void createOrderForProduct(JSONObject item, @Nullable String phone) {
        if (busy || pendingOrder != null) return;
        String customerDetails = phone == null ? "Dịch vụ trải nghiệm · " + item.optString("name") : phone;
        try {
            pendingOrder = new JSONObject()
                    .put("requestId", UUID.randomUUID().toString())
                    .put("productId", item.getString("id")).put("quantity", 1)
                    .put("expectedAmountMinor", item.getLong("amountMinor"))
                    .put("expectedCurrency", item.getString("currency"))
                    .put("customerDetails", customerDetails).toString();
            submitOrder();
        } catch (JSONException error) {
            hubStatus("Dữ liệu báo giá không hợp lệ.");
        }
    }

    private void recoverOrder() {
        if (busy || pendingOrder == null) return;
        setBusy(true);
        hubStatus("Đang tra cứu đơn cũ…");
        try {
            String id = new JSONObject(pendingOrder).getString("requestId");
            api.get("/orders/by-request/" + id).addOnCompleteListener(task -> {
                if (binding == null) return;
                setBusy(false);
                if (task.isSuccessful() && task.getResult().statusCode == 404) submitOrder();
                else if (task.isSuccessful() && ok(task.getResult())) openOrderInvoice(task.getResult());
                else hubStatus("Chưa tra cứu được đơn cũ. Hãy kiểm tra lại.");
            });
        } catch (JSONException error) {
            setBusy(false);
            clearPendingOrder();
            hubStatus("Không đọc được mã đơn cũ.");
        }
    }

    private void submitOrder() {
        setBusy(true);
        binding.pendingOrderButton.setVisibility(View.VISIBLE);
        hubStatus("Đang tạo đơn hàng…");
        api.post("/orders", pendingOrder).addOnCompleteListener(task -> {
            if (binding == null) return;
            setBusy(false);
            if (task.isSuccessful() && ok(task.getResult())) {
                openOrderInvoice(task.getResult());
            } else if (task.isSuccessful() && "INVALID_TOPUP_PHONE".equals(errorCode(task.getResult()))) {
                clearPendingOrder();
                binding.utilMobile.performClick();
                hubStatus("Nhập lại số điện thoại gồm 10 chữ số, bắt đầu bằng 0.");
            } else if (task.isSuccessful() && task.getResult().statusCode == 409) {
                clearPendingOrder();
                hubStatus("Báo giá đã thay đổi. Mở lại danh mục để kiểm tra giá.");
            } else hubStatus("Chưa xác nhận được đơn. Bấm kiểm tra đơn đang tạo để dùng lại mã cũ.");
        });
    }

    private void clearPendingOrder() {
        pendingOrder = null;
        binding.pendingOrderButton.setVisibility(View.GONE);
    }

    private void openOrderInvoice(BackendApiClient.Response response) {
        try {
            JSONObject order = new JSONObject(response.body);
            String id = order.getString("id");
            String details = order.optString("customerDetails", "");
            if ("TOPUP".equals(order.optString("serviceType")) && !details.isEmpty()) {
                details = "Số điện thoại nhận nạp: " + details;
            }
            clearPendingOrder();
            lookupBill(id, details);
        } catch (JSONException error) {
            hubStatus("Chưa đọc được mã đơn. Bấm kiểm tra đơn đang tạo.");
        }
    }

    private void showOrders() {
        if (busy) return;
        setBusy(true);
        hubStatus("Đang tra cứu đơn hàng…");
        api.get("/orders?limit=100").addOnCompleteListener(task -> {
            if (binding == null || !isAdded()) return;
            setBusy(false);
            try {
                if (!task.isSuccessful() || !ok(task.getResult())) throw new JSONException("Unavailable");
                var items = new JSONObject(task.getResult().body).getJSONArray("items");
                var rows = new java.util.ArrayList<String>();
                for (int i = 0; i < items.length(); i++) {
                    JSONObject order = items.getJSONObject(i);
                    String fulfillment = order.getString("fulfillmentStatus");
                    rows.add(order.optString("customerDetails") + "\n"
                            + "Thanh toán: " + paymentStatusLabel(order.optString("paymentStatus"))
                            + " · Trạng thái: " + fulfillmentStatusLabel(fulfillment));
                }
                hubStatus(rows.isEmpty() ? "Chưa có đơn hàng." : "Đã cập nhật danh sách đơn hàng.");
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("Đơn hàng")
                        .setItems(rows.toArray(new String[0]), (dialog, index) -> {
                            JSONObject order = items.optJSONObject(index);
                            if (order != null) lookupBill(order.optString("id"));
                        })
                        .setNegativeButton("Đóng", null).show();
            } catch (JSONException error) {
                hubStatus("Không tải được đơn hàng. Vui lòng thử lại.");
            }
        });
    }

    private void handleLookup() {
        String code = binding.customerCodeInput.getText() == null ? "" : binding.customerCodeInput.getText().toString().trim();
        if (code.isEmpty()) {
            binding.customerCodeInput.setError("Vui lòng nhập mã tra cứu");
            return;
        }
        lookupBill(code);
    }

    private void lookupBill(String code) {
        lookupBill(code, "");
    }

    private void lookupBill(String code, String orderDetails) {
        if (busy) return;
        setBusy(true);
        hubStatus("Đang tra cứu hóa đơn…");
        api.get("/bills/lookup/" + android.net.Uri.encode(code)).addOnCompleteListener(task -> {
            if (binding == null || !isAdded()) return;
            setBusy(false);
            try {
                if (task.isSuccessful() && task.getResult().statusCode == 404) {
                    hubStatus("Không tìm thấy hóa đơn thuộc tài khoản này.");
                    return;
                }
                if (!task.isSuccessful() || !ok(task.getResult())) throw new JSONException("Unavailable");
                JSONObject bill = new JSONObject(task.getResult().body);
                if (!"UNPAID".equals(bill.getString("status"))) {
                    hubStatus("Hóa đơn " + bill.optString("invoiceRef") + " · "
                            + billStatusLabel(bill.getString("status")));
                    return;
                }
                startCheckout(bill, orderDetails);
            } catch (JSONException error) {
                hubStatus("Không tra cứu được hóa đơn. Hãy kiểm tra mã hoặc thử lại.");
            }
        });
    }

    // ============================================================
    // CHECKOUT FLOW
    // ============================================================
    private void startCheckout(JSONObject bill, String orderDetails) {
        if (busy || binding == null) return;
        if (!bill.optBoolean("stripePending") && !bill.isNull("quoteExpiresAt")
                && bill.optLong("quoteExpiresAt") <= System.currentTimeMillis()) {
            hubStatus("Báo giá đã hết hạn. Nếu đã gửi thanh toán, kiểm tra kết quả của đơn cũ.");
            return;
        }
        currentBillId = bill.optString("id");
        currentServiceName = serviceLabel(bill.optString("serviceType", bill.optString("provider")));
        if (!orderDetails.isEmpty()) currentServiceName += "\n" + orderDetails;
        currentReference = bill.optString("invoiceRef", currentBillId);
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
        binding.checkoutConfirmationCard.setVisibility(View.VISIBLE);
        binding.checkoutSuccessContainer.setVisibility(View.GONE);
        binding.hubMainTitle.setText("Thanh toán dịch vụ");
        binding.hubSubtitle.setVisibility(View.VISIBLE);
        binding.hubSubtitle.setText("Thanh toán bằng thẻ");
        binding.checkoutServiceTitle.setText(currentServiceName);
        binding.checkoutReferenceText.setText("Mã hóa đơn / tham chiếu: " + currentReference);
        renderCheckoutStep();
    }

    private String serviceLabel(String value) {
        switch (value.toUpperCase(Locale.ROOT)) {
            case "ELECTRICITY": return "Hóa đơn tiền điện";
            case "WATER": return "Hóa đơn tiền nước";
            case "TOPUP": return "Nạp điện thoại";
            case "FLIGHT": return "Vé máy bay";
            case "MOVIE": return "Vé xem phim";
            case "HOTEL": return "Đặt khách sạn";
            case "ECOMMERCE": return "Mua sắm";
            default: return value;
        }
    }

    private void preparePaymentIntent() {
        if (currentBillId == null || busy) return;
        setBusy(true);
        checkoutStatus("Đang chuẩn bị thanh toán…");
        try {
            JSONObject body = new JSONObject()
                    .put("billId", currentBillId)
                    .put("requestId", requestId);
            Bundle arguments = getArguments();
            if (arguments != null) body.put("sourceAccountId", arguments.getString("sourceAccountId"));

            String prepare = "/payments/intents";
            String recovery = "/payment-intents/by-request/" + requestId;
            com.google.android.gms.tasks.Task<BackendApiClient.Response> preparation;
            if (preparingPreviously) {
                preparation = api.get(recovery).continueWithTask(task -> {
                    if (!task.isSuccessful()) throw task.getException();
                    return task.getResult().statusCode == 404 ? api.post(prepare, body.toString())
                            : com.google.android.gms.tasks.Tasks.forResult(task.getResult());
                });
            } else preparation = api.post(prepare, body.toString());
            preparingPreviously = true;
            preparation.addOnCompleteListener(task -> {
                if (!isAdded() || binding == null) return;
                setBusy(false);
                if (!task.isSuccessful()) {
                    binding.checkoutCommitContainer.setVisibility(View.VISIBLE);
                    binding.checkoutBtnRecover.setVisibility(View.VISIBLE);
                    binding.checkoutBtnRecover.setText("Tra cứu yêu cầu cũ");
                    checkoutStatus("Chưa xác nhận yêu cầu. Bấm tra cứu để dùng lại mã cũ.");
                    return;
                }
                if (!ok(task.getResult())) {
                    if ("PAYMENT_IN_PROGRESS".equals(errorCode(task.getResult()))) {
                        showPaymentConflict();
                        return;
                    }
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
                    checkoutStatus("Không thể chuẩn bị thanh toán. Vui lòng thử lại.");
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
        boolean quoted = authoritativeAmount > 0;
        binding.checkoutAmountText.setText(quoted ? ReadSummaryFormatter.formatCurrency(authoritativeAmount, authoritativeCurrency) : "—");
        binding.checkoutFeeText.setText(!quoted ? "—" : authoritativeFee == 0 ? "Miễn phí"
                : ReadSummaryFormatter.formatCurrency(authoritativeFee, authoritativeCurrency));
        binding.checkoutTotalText.setText(quoted
                ? ReadSummaryFormatter.formatCurrency(authoritativeAmount + authoritativeFee, authoritativeCurrency) : "—");
        binding.checkoutConfirmationAmount.setText(quoted
                ? ReadSummaryFormatter.formatCurrency(authoritativeAmount + authoritativeFee, authoritativeCurrency) : "—");
        binding.checkoutIntentIdText.setVisibility(View.GONE);

        binding.checkoutConfirmButton.setVisibility(quoted && !checkoutConfirmed && !commitPending && !completed
                ? View.VISIBLE : View.GONE);
        boolean needFace = checkoutConfirmed && quoted && faceRequired && !faceVerified && !commitPending;
        binding.checkoutFaceStepContainer.setVisibility(needFace ? View.VISIBLE : View.GONE);

        boolean canOtp = checkoutConfirmed && quoted && intentId != null && (!faceRequired || faceVerified) && !commitPending && !completed;
        if (!canOtp) dismissSharedOtp();
        if (canOtp && authorizationSheet == null) {
            authorizationSheet = new com.example.final_term.ui.common.TransactionSheet(requireContext(), binding.checkoutOtpStepContainer);
        } else if (!canOtp && authorizationSheet != null) {
            authorizationSheet.close();
            authorizationSheet = null;
        }
        binding.checkoutBtnVerifyOtp.setEnabled(!busy && challengeId != null);

        binding.checkoutCommitContainer.setVisibility(commitPending ? View.VISIBLE : View.GONE);
        binding.checkoutPendingBanner.setVisibility(commitPending ? View.VISIBLE : View.GONE);
        binding.checkoutBtnRecover.setVisibility(commitPending && !busy ? View.VISIBLE : View.GONE);
        binding.checkoutBtnRecover.setText("Tiếp tục / kiểm tra thanh toán");
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
        if (sharedOtp == null) {
            sharedOtp = new com.example.final_term.ui.transaction.OtpBottomSheetDialog(requireContext(), new com.example.final_term.ui.transaction.OtpBottomSheetDialog.Listener() {
                @Override public void onSubmitOtp(@NonNull String code) { binding.checkoutOtpInput.setText(code); verifyOtp(); }
                @Override public void onResendRequested() { requestOtp(); }
                @Override public void onDismissRequested() { dismissSharedOtp(); cancelIntentForEdit(); }
            });
        }
        sharedOtp.show();
        setBusy(true);
        checkoutStatus("Đang tạo mã xác nhận…");
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
                if (!"DEMO_DISPLAY_CODE".equals(res.getString("method"))) throw new IllegalStateException();
                String code = res.getString("code");
                if (!code.matches("[0-9]{6}")) throw new IllegalStateException();
                challengeId = res.getString("challengeId");
                binding.checkoutDemoCode.setText("Mã OTP demo đã được điền tự động");
                binding.checkoutOtpInput.setText(code);
                if (sharedOtp != null) sharedOtp.setDemoCode(code);
                startOtpTimer();
                renderCheckoutStep();
                checkoutStatus("Mã OTP demo đã được điền. Bấm xác nhận để tiếp tục.");
            } catch (Exception e) {
                checkoutStatus("Phản hồi mã OTP không hợp lệ.");
            }
        });
    }

    private void startOtpTimer() {
        if (otpTimer != null) otpTimer.cancel();
        if (sharedOtp != null) sharedOtp.setCountdownText("Gửi lại sau 00:30", false);
        binding.checkoutOtpTimer.setClickable(false);
        otpTimer = new CountDownTimer(30000, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                if (binding != null) {
                    if (sharedOtp != null) sharedOtp.setCountdownText(String.format(Locale.getDefault(), "Gửi lại sau 00:%02d", millisUntilFinished / 1000), false);
                    binding.checkoutOtpTimer.setText(String.format(Locale.getDefault(),
                            "Tạo mã mới sau 00:%02d", millisUntilFinished / 1000));
                }
            }
            @Override
            public void onFinish() {
                if (binding != null) {
                    if (sharedOtp != null) sharedOtp.setCountdownText("Gửi lại mã OTP", true);
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
                recoverPaymentAndIntent(true, true);
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
                recoverPaymentAndIntent(true, true);
            } else {
                setBusy(false);
                String err = errorCode(task.getResult());
                if ("OTP_USED".equals(err)) recoverPaymentAndIntent(true, true);
                else checkoutStatus(errorMessage(task.getResult(), "Mã xác nhận bị từ chối"));
            }
        });
    }

    private void commitPayment() {
        if (busy || completed || currentBillId == null || requestId == null) return;
        commitPending = true;
        setBusy(true);
        renderCheckoutStep();
        checkoutStatus("Đang mở thanh toán thẻ…");

        try {
            String body = new JSONObject()
                    .put("billId", currentBillId)
                    .put("requestId", requestId)
                    .toString();

            api.post("/payments", body).continueWithTask(task -> {
                BackendApiClient.Response response = task.isSuccessful() ? task.getResult() : null;
                if (response != null && (response.statusCode < 500 || "SETUP_REQUIRED".equals(errorCode(response)))) {
                    return Tasks.forResult(response);
                }
                // Reuse the same request ID if the first Stripe request may have reached the backend.
                return api.post("/payments", body);
            }).addOnCompleteListener(task -> {
                if (!isAdded() || binding == null) return;
                setBusy(false);
                if (!task.isSuccessful() || task.getResult() == null || task.getResult().statusCode >= 500) {
                    checkoutStatus(errorMessage(task.isSuccessful() ? task.getResult() : null,
                            "Chưa có kết quả thanh toán. Bấm kiểm tra lại"));
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
                        if ("FAILED".equals(status)) {
                            commitPending = false;
                            checkoutStatus("Thanh toán chưa hoàn tất. Số dư chưa thay đổi.");
                            renderCheckoutStep();
                            return;
                        }
                        String clientSecret = p.optString("clientSecret", "");
                        if (!p.isNull("clientSecret") && !clientSecret.isEmpty()) {
                            if (paymentSheet == null) {
                                checkoutStatus("Thanh toán thẻ chưa được cấu hình.");
                                return;
                            }
                            PaymentSheet.Configuration.Builder configuration =
                                    new PaymentSheet.Configuration.Builder("Nimbus Bank");
                            if (!p.isNull("customerId") && !p.isNull("customerSessionClientSecret")) {
                                configuration.customer(PaymentSheet.CustomerConfiguration.Companion
                                        .createWithCustomerSession(p.getString("customerId"),
                                                p.getString("customerSessionClientSecret")));
                            }
                            checkoutStatus("Chọn thẻ đã lưu hoặc nhập thông tin thẻ để thanh toán.");
                            paymentSheet.presentWithPaymentIntent(clientSecret, configuration.build());
                            return;
                        }
                    } catch (Exception ignored) { }
                    // If COMMIT_PENDING or other gateway state, recover authoritative settlement
                    recoverPaymentAndIntent();
                    return;
                }
                commitPending = false;
                String code = errorCode(response);
                if ("PAYMENT_IN_PROGRESS".equals(code)) {
                    showPaymentConflict();
                    return;
                }
                if ("INVALID_AMOUNT".equals(code)) {
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
            renderCheckoutStep();
            checkoutStatus("Không thể tạo yêu cầu ghi sổ thanh toán.");
        }
    }

    private void onPaymentSheetResult(@NonNull PaymentSheetResult result) {
        if (!isAdded() || binding == null) return;
        setBusy(false);
        commitPending = true;
        renderCheckoutStep();
        if (result instanceof PaymentSheetResult.Completed) {
            checkoutStatus("Đang chờ xác nhận thanh toán…");
            settlementPollsRemaining = 20;
            recoverPaymentAndIntent();
        } else if (result instanceof PaymentSheetResult.Canceled) {
            checkoutStatus("Bạn đã đóng màn thanh toán. Bấm Tiếp tục thanh toán để thử lại.");
        } else if (result instanceof PaymentSheetResult.Failed) {
            PaymentSheetResult.Failed failed = (PaymentSheetResult.Failed) result;
            String message = failed.getError().getLocalizedMessage();
            checkoutStatus("Thanh toán chưa hoàn tất" +
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
                            ? "Đang chờ xác nhận thanh toán…"
                            : "Bấm Tiếp tục thanh toán để hoàn tất giao dịch.");
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
                    if (checkoutConfirmed && (!faceRequired || faceVerified)) requestOtp();
                    else checkoutStatus("Kiểm tra thông tin và xác nhận để tiếp tục.");
                } else if ("EXPIRED".equalsIgnoreCase(challenge)) {
                    checkoutStatus("Mã xác nhận đã hết hạn. Bấm tạo mã mới.");
                } else if ("LOCKED".equalsIgnoreCase(challenge)) {
                    checkoutStatus("Giao dịch bị khóa do nhập sai quá 5 lần. Cần hủy và tạo lại.");
                } else if (faceRequired && !faceVerified) {
                    checkoutStatus("Xác minh khuôn mặt để tiếp tục thanh toán.");
                } else if ("AWAITING_CODE".equalsIgnoreCase(challenge) && challengeId != null) {
                    checkoutStatus("Kiểm tra mã OTP demo và bấm xác nhận để tiếp tục.");
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
            if (checkoutOnly) dismiss(); else returnToHub();
            return;
        }
        setBusy(true);
        checkoutStatus("Đang hủy yêu cầu cũ…");
        api.post("/intents/" + intentId + "/cancel", "{}").addOnCompleteListener(task -> {
            if (!isAdded() || binding == null) return;
            setBusy(false);
            if (!task.isSuccessful() || task.getResult() == null || !ok(task.getResult())) {
                checkoutStatus("Chưa hủy được yêu cầu. Kiểm tra kết nối rồi thử lại.");
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
        if (authorizationSheet != null) { authorizationSheet.close(); authorizationSheet = null; }
        if (checkoutOnly) {
            dismiss();
            return;
        }
        catalogRequestVersion++;
        if (otpTimer != null) {
            otpTimer.cancel();
            otpTimer = null;
        }
        binding.checkoutContainer.setVisibility(View.GONE);
        binding.hubSelectionContainer.setVisibility(View.VISIBLE);
        binding.hubMainTitle.setText("Tiện ích & Thanh toán");
        binding.utilityBackButton.setVisibility(View.GONE);
        binding.utilitySearchContainer.setVisibility(View.VISIBLE);
        binding.utilityIntroCard.setVisibility(View.VISIBLE);
        binding.utilityGridHeading.setVisibility(View.VISIBLE);
        binding.utilityGridRowOne.setVisibility(View.VISIBLE);
        binding.utilityGridRowTwo.setVisibility(View.VISIBLE);
        binding.utilityBillsHeading.setVisibility(View.VISIBLE);
        binding.pendingOrderButton.setVisibility(pendingOrder == null ? View.GONE : View.VISIBLE);
        binding.orderHistoryButton.setVisibility(View.VISIBLE);
        binding.billsListContainer.setVisibility(View.VISIBLE);
        binding.topupFormContainer.setVisibility(View.GONE);
        binding.lookupFormContainer.setVisibility(View.GONE);
        binding.hubStatusText.setVisibility(View.GONE);
        binding.utilitySearchInput.setText("");
        currentBillId = null;
        requestId = null;
        intentId = null;
        challengeId = null;
        checkoutConfirmed = false;
        faceRequired = false;
        faceVerified = false;
        commitPending = false;
        completed = false;
        preparingPreviously = false;
        authoritativeAmount = 0;
        authoritativeFee = 0;
        authoritativeTarget = "";
        binding.hubSubtitle.setVisibility(View.GONE);
        loadOwnedBills();
    }

    private void showPaymentConflict() {
        returnToHub();
        hubStatus("Hóa đơn đang có giao dịch chờ xử lý. Chọn lại hóa đơn để kiểm tra kết quả.");
    }

    private void finishPaymentSuccess() {
        completed = true;
        commitPending = false;
        if (binding == null) return;
        if (authorizationSheet != null) { authorizationSheet.close(); authorizationSheet = null; }
        binding.checkoutConfirmationCard.setVisibility(View.GONE);
        binding.checkoutFaceStepContainer.setVisibility(View.GONE);
        binding.checkoutOtpStepContainer.setVisibility(View.GONE);
        binding.checkoutCommitContainer.setVisibility(View.GONE);
        binding.checkoutSuccessContainer.setVisibility(View.VISIBLE);
        binding.checkoutSuccessTitle.setText("Thanh toán thành công");
        binding.receiptAmountDisplay.setText(ReadSummaryFormatter.formatCurrency(authoritativeAmount + authoritativeFee, authoritativeCurrency));
        binding.receiptDetailsText.setText("Dịch vụ: " + currentServiceName + "\nTham chiếu: " + currentReference +
                "\nMã giao dịch: " + (intentId == null ? requestId : intentId) +
                "\nThanh toán đã được xác nhận. Kiểm tra đơn hàng để xem trạng thái thực hiện.");
        checkoutStatus("");
    }

    private void checkoutStatus(String msg) {
        if (sharedOtp != null && sharedOtp.isShowing()) sharedOtp.setStatusMessage(msg);
        if (binding != null) {
            binding.checkoutOtpStatus.setText(msg);
            binding.checkoutStatusText.setText(msg);
        }
    }

    private void setBusy(boolean busy) {
        this.busy = busy;
        if (sharedOtp != null) sharedOtp.setBusy(busy);
        if (binding != null) {
            binding.btnProceedTopup.setEnabled(!busy);
            binding.topupCarrierPicker.setEnabled(!busy);
            binding.pendingOrderButton.setEnabled(!busy);
            binding.orderHistoryButton.setEnabled(!busy);
            binding.lookupButton.setEnabled(!busy);
            binding.checkoutBtnFace.setEnabled(!busy);
            binding.checkoutBtnVerifyOtp.setEnabled(!busy && challengeId != null);
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
            case "SETUP_REQUIRED": return "Thanh toán bằng thẻ hiện chưa khả dụng. Vui lòng thử lại sau.";
            case "INVALID_OTP": return "Mã xác nhận không đúng. Vui lòng kiểm tra lại.";
            case "OTP_EXPIRED": return "Mã xác nhận đã hết hạn. Tạo mã mới.";
            case "OTP_LOCKED": return "Đã nhập sai quá 5 lần, giao dịch bị khóa. Hủy để tạo lại.";
            case "OTP_USED": return "Mã đã được sử dụng. Đang kiểm tra trạng thái giao dịch.";
            case "RATE_LIMITED": return "Bạn thao tác quá nhanh. Vui lòng đợi trong giây lát.";
            case "EXPIRED":
            case "INTENT_CHANGED": return "Giao dịch đã hết hạn hoặc bị thay đổi. Cần tạo lại giao dịch mới.";
            case "FACE_REQUIRED": return "Giao dịch yêu cầu đối chiếu khuôn mặt.";
            case "STEP_UP_UNAVAILABLE":
            case "FACE_SETUP_REQUIRED": return "Chưa thể xác minh khuôn mặt. Vui lòng thử lại sau.";
            case "FACE_MISMATCH": return "Khuôn mặt không khớp với hồ sơ đã duyệt.";
            case "FACE_NOT_FOUND":
            case "MULTIPLE_FACES":
            case "FACE_LOW_QUALITY": return "Chụp rõ một khuôn mặt trong khung hình, đủ ánh sáng.";
            case "IDEMPOTENCY_CONFLICT": return "Yêu cầu này đã được gửi trước đó. Kiểm tra trạng thái rồi thử lại.";
            default: return fallback + ". Vui lòng thử lại sau.";
        }
    }

    private static String paymentStatusLabel(String status) {
        if ("SUCCEEDED".equalsIgnoreCase(status) || "PAID".equalsIgnoreCase(status)) return "Đã thanh toán";
        if ("FAILED".equalsIgnoreCase(status)) return "Thanh toán thất bại";
        if ("CANCELLED".equalsIgnoreCase(status)) return "Đã hủy";
        return "Đang chờ thanh toán";
    }

    private static String fulfillmentStatusLabel(String status) {
        if ("FULFILLED".equalsIgnoreCase(status) || "COMPLETED".equalsIgnoreCase(status)) return "Đã hoàn tất";
        if ("FAILED".equalsIgnoreCase(status)) return "Chưa hoàn tất";
        return "Đang xử lý";
    }

    private static String billStatusLabel(String status) {
        if ("PAID".equalsIgnoreCase(status) || "SETTLED".equalsIgnoreCase(status)) return "Đã thanh toán";
        if ("CANCELLED".equalsIgnoreCase(status)) return "Đã hủy";
        if ("PROCESSING".equalsIgnoreCase(status) || "PAYMENT_PENDING".equalsIgnoreCase(status)) return "Đang xử lý";
        return "Chưa thanh toán";
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() != null && getDialog().getWindow() != null) {
            getDialog().getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            );
            getDialog().getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
    }

    @Override
    public void onDestroyView() {
        dismissSharedOtp();
        if (authorizationSheet != null) { authorizationSheet.close(); authorizationSheet = null; }
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

    private void dismissSharedOtp() {
        if (sharedOtp != null) { sharedOtp.dismiss(); sharedOtp = null; }
    }
}

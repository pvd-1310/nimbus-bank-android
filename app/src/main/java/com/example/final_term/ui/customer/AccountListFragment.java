package com.example.final_term.ui.customer;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.example.final_term.R;
import com.example.final_term.auth.AuthState;
import com.example.final_term.auth.UserRole;
import com.example.final_term.core.FinalTermApplication;
import com.example.final_term.data.FirestoreReadRepository;
import com.example.final_term.data.ReadModelResult;
import com.example.final_term.data.ReadModels;
import com.example.final_term.data.ReadSummaryFormatter;
import com.example.final_term.databinding.FragmentAccountListBinding;
import com.example.final_term.ui.common.HatchedProgressBar;
import com.example.final_term.ui.common.SignatureBarChartView;
import com.example.final_term.ui.utilities.UtilitiesHubDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class AccountListFragment extends Fragment {
    private FragmentAccountListBinding binding;
    private FinalTermApplication application;
    private String uid;
    private UserRole role;
    private List<ReadModels.AccountSummary> accounts;
    private List<ReadModels.NotificationSummary> notifications;
    private List<ReadModels.TransactionSummary> transactions;
    private ReadModelResult.Status accountStatus;
    private String accountMessage;
    private int activeNavItem = R.id.nav_accounts;
    private boolean isCardLocked = false;
    private String pendingProvisionKey;
    private String pendingProvisionRequestId;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentAccountListBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        application = (FinalTermApplication) requireActivity().getApplication();

        Bundle args = getArguments();
        if (args != null && args.containsKey("activeNavItem")) {
            activeNavItem = args.getInt("activeNavItem", activeNavItem);
            if (Navigation.findNavController(view).getCurrentBackStackEntry() != null) {
                Navigation.findNavController(view).getCurrentBackStackEntry()
                        .getSavedStateHandle().set("activeNavItem", activeNavItem);
            }
        } else if (Navigation.findNavController(view).getCurrentBackStackEntry() != null) {
            Integer savedItem = Navigation.findNavController(view).getCurrentBackStackEntry()
                    .getSavedStateHandle().get("activeNavItem");
            if (savedItem != null) {
                activeNavItem = savedItem;
            }
        }

        if (Navigation.findNavController(view).getCurrentBackStackEntry() != null) {
            Navigation.findNavController(view).getCurrentBackStackEntry()
                    .getSavedStateHandle()
                    .<Integer>getLiveData("activeNavItem")
                    .observe(getViewLifecycleOwner(), item -> {
                        if (item != null && item != activeNavItem) {
                            activeNavItem = item;
                            updateScreenTitle();
                            renderAll();
                            if (binding != null && binding.scrollView != null) {
                                binding.scrollView.scrollTo(0, 0);
                            }
                        }
                    });
        }

        binding.backButton.setOnClickListener(v -> Navigation.findNavController(v).navigateUp());
        binding.retryButton.setOnClickListener(v -> load());
        binding.notificationButton.setOnClickListener(v -> {
            showNotificationsDialog();
        });

        application.getAppContainer().getAuthRepository().observeAuthState()
                .observe(getViewLifecycleOwner(), authState -> {
                    if (authState != null && authState.getStatus() == AuthState.Status.SIGNED_IN && authState.getUser() != null) {
                        uid = authState.getUser().getUid();
                        role = authState.getUser().getRole();
                        updateScreenTitle();
                        load();
                    }
                });
    }

    private void updateScreenTitle() {
        if (role == UserRole.OFFICER) {
            binding.titleText.setText(activeNavItem == R.id.nav_wallet ? "Khách hàng" : "Duyệt & Tài khoản");
        } else {
            binding.titleText.setText(activeNavItem == R.id.nav_wallet ? "Ví" : "Tài khoản & Thẻ");
        }
    }

    private void load() {
        if (uid == null) return;
        FirestoreReadRepository repository = application.getAppContainer().getReadRepository();
        if (role == UserRole.OFFICER) {
            repository.observeAllAccountsForOfficer().observe(getViewLifecycleOwner(), this::renderAccounts);
            repository.observeAllTransactionsForOfficer().observe(getViewLifecycleOwner(), this::renderTransactions);
        } else {
            repository.observeAccounts(uid).observe(getViewLifecycleOwner(), this::renderAccounts);
            repository.observeTransactions(uid).observe(getViewLifecycleOwner(), this::renderTransactions);
        }
        repository.observeNotifications(uid).observe(getViewLifecycleOwner(), this::renderNotifications);
    }

    private void renderAccounts(ReadModelResult<List<ReadModels.AccountSummary>> result) {
        if (result == null) return;
        accounts = result.getData();
        accountStatus = result.getStatus();
        accountMessage = result.getMessage();
        renderAll();
    }

    private void renderTransactions(ReadModelResult<List<ReadModels.TransactionSummary>> result) {
        if (result == null) return;
        transactions = result.getData();
        renderAll();
    }

    private void renderNotifications(ReadModelResult<List<ReadModels.NotificationSummary>> result) {
        if (result == null) return;
        notifications = result.getData();
        int unread = 0;
        if (notifications != null) {
            for (ReadModels.NotificationSummary n : notifications) if (!n.read) unread++;
        }
        binding.notificationDot.setVisibility(unread > 0 ? View.VISIBLE : View.GONE);
        renderAll();
    }

    private void renderAll() {
        if (binding == null) return;
        binding.statusText.setText(accountStatus == null ? "Đang tải dữ liệu…" : accountStatusMessage());
        binding.retryButton.setVisibility(accountStatus == ReadModelResult.Status.ERROR ? View.VISIBLE : View.GONE);
        binding.content.removeAllViews();

        if (role == UserRole.OFFICER) {
            if (activeNavItem == R.id.nav_wallet) {
                renderOfficerCustomers();
            } else {
                renderOfficerAccounts();
            }
        } else {
            if (activeNavItem == R.id.nav_wallet) {
                renderCustomerWallet();
            } else {
                renderCustomerCards();
            }
        }
    }

    // ==========================================
    // 1. CUSTOMER: TÀI KHOẢN & THẺ (Cards_Screen)
    // ==========================================
    private void renderCustomerCards() {
        ReadModels.AccountSummary checkingAccount = null;
        List<ReadModels.AccountSummary> otherAccounts = new ArrayList<>();

        if (accounts != null) {
            for (ReadModels.AccountSummary acc : accounts) {
                if ("CHECKING".equalsIgnoreCase(acc.type) && checkingAccount == null) {
                    checkingAccount = acc;
                } else {
                    otherAccounts.add(acc);
                }
            }
            if (checkingAccount == null && !accounts.isEmpty()) {
                checkingAccount = accounts.get(0);
                otherAccounts.remove(0);
            }
        }

        // Render Primary VISA Card Mockup
        View cardView = LayoutInflater.from(requireContext()).inflate(R.layout.view_visa_card, binding.content, false);
        TextView cardBalance = cardView.findViewById(R.id.card_balance_text);
        TextView cardMaskedNo = cardView.findViewById(R.id.card_masked_number);
        TextView cardHolder = cardView.findViewById(R.id.card_holder_name);
        TextView cardStatusBadge = cardView.findViewById(R.id.card_status_badge);

        if (checkingAccount != null) {
            cardBalance.setText(formatMoney(checkingAccount.balanceMinor, checkingAccount.currency));
            String last4 = checkingAccount.id.length() > 4 ? checkingAccount.id.substring(checkingAccount.id.length() - 4) : "8901";
            cardMaskedNo.setText("•••• •••• •••• " + last4);
            cardHolder.setText("NIMBUS PLATINUM");
            cardStatusBadge.setText(isCardLocked ? "ĐÃ KHÓA" : checkingAccount.status);
            cardStatusBadge.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(),
                    isCardLocked ? R.color.danger_bg : R.color.success_bg));
        } else {
            cardBalance.setText("— ₫");
            cardMaskedNo.setText("•••• •••• •••• 8901");
            cardHolder.setText("NIMBUS CARD");
            cardStatusBadge.setText(accountStatus == ReadModelResult.Status.LOADING ? "ĐANG TẢI" : "CHỜ CẤP");
        }
        binding.content.addView(cardView);

        // 4 Circular Card Action Buttons (NFC, Hạn mức, Khóa thẻ, Cài đặt)
        LinearLayout actionsRow = new LinearLayout(requireContext());
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        actionsRow.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        actionsRow.setPadding(0, dp(18), 0, dp(18));

        actionsRow.addView(createCircularAction("NFC Chạm", R.drawable.ic_nfc, v -> {
            Toast.makeText(requireContext(), "NFC thanh toán một chạm đã được kích hoạt trên thẻ.", Toast.LENGTH_SHORT).show();
        }));
        actionsRow.addView(createCircularAction("Hạn mức", R.drawable.ic_shield, v -> {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Hạn mức thẻ")
                    .setMessage("Hạn mức thanh toán trực tuyến: 50.000.000 ₫/ngày\nHạn mức rút tiền ATM: 20.000.000 ₫/ngày\n\nBạn có thể điều chỉnh hạn mức tại quầy giao dịch Nimbus Bank.")
                    .setPositiveButton("Đã hiểu", null)
                    .show();
        }));
        actionsRow.addView(createCircularAction(isCardLocked ? "Mở khóa" : "Khóa thẻ", R.drawable.ic_lock, v -> {
            isCardLocked = !isCardLocked;
            Toast.makeText(requireContext(), isCardLocked ? "Đã khóa thẻ tạm thời để bảo mật." : "Đã mở khóa thẻ thành công.", Toast.LENGTH_SHORT).show();
            renderAll();
        }));
        actionsRow.addView(createCircularAction("Cài đặt", R.drawable.ic_settings, v -> {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Cài đặt thẻ")
                    .setMessage("• Đổi mã PIN thẻ ATM\n• Bật/Tắt thanh toán quốc tế\n• Bật/Tắt thanh toán trực tuyến (E-commerce)")
                    .setPositiveButton("Đóng", null)
                    .show();
        }));
        binding.content.addView(actionsRow);

        // Full-width button: Chi tiết tài khoản
        final ReadModels.AccountSummary finalChecking = checkingAccount;
        MaterialButton detailBtn = new MaterialButton(requireContext());
        detailBtn.setText("Chi tiết tài khoản");
        detailBtn.setTextColor(ContextCompat.getColor(requireContext(), R.color.navy));
        detailBtn.setBackgroundResource(R.drawable.glass_pill_button);
        detailBtn.setCornerRadius(dp(18));
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        btnParams.bottomMargin = dp(24);
        detailBtn.setLayoutParams(btnParams);
        detailBtn.setOnClickListener(v -> {
            if (finalChecking != null) {
                openDetails(v, finalChecking);
            } else {
                Toast.makeText(requireContext(), "Chưa có tài khoản thanh toán.", Toast.LENGTH_SHORT).show();
            }
        });
        binding.content.addView(detailBtn);

        // Section: Tài khoản khác (Tiết kiệm, Vay thế chấp)
        if (!otherAccounts.isEmpty()) {
            TextView sectionTitle = new TextView(requireContext());
            sectionTitle.setText("Tài khoản tiết kiệm & Khoản vay");
            sectionTitle.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink));
            sectionTitle.setTextSize(17);
            sectionTitle.setTypeface(null, android.graphics.Typeface.BOLD);
            sectionTitle.setPadding(0, 0, 0, dp(12));
            binding.content.addView(sectionTitle);

            for (ReadModels.AccountSummary acc : otherAccounts) {
                View tile = createAccountTile(acc);
                binding.content.addView(tile);
            }
        }
    }

    // ==========================================
    // 2. CUSTOMER: VÍ & HẠN MỨC (Wallet_Screen)
    // ==========================================
    private void renderCustomerWallet() {
        // Monthly Limit Card with Hatched Progress Bar
        LinearLayout limitCard = new LinearLayout(requireContext());
        limitCard.setOrientation(LinearLayout.VERTICAL);
        limitCard.setBackgroundResource(R.drawable.glass_panel_card);
        limitCard.setElevation(dp(4));
        limitCard.setPadding(dp(18), dp(18), dp(18), dp(18));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(18);
        limitCard.setLayoutParams(cardParams);

        TextView limitHeader = new TextView(requireContext());
        limitHeader.setText("Hạn mức chi tiêu tháng");
        limitHeader.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink));
        limitHeader.setTextSize(14);
        limitHeader.setTypeface(null, android.graphics.Typeface.BOLD);
        limitCard.addView(limitHeader);

        TextView limitValue = new TextView(requireContext());
        limitValue.setText("6.400.000 / 10.000.000 ₫");
        limitValue.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink_muted));
        limitValue.setTextSize(13);
        limitValue.setPadding(0, dp(4), 0, 0);
        limitCard.addView(limitValue);

        HatchedProgressBar bar = new HatchedProgressBar(requireContext());
        bar.setProgress(0.64f);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(20));
        barParams.topMargin = dp(12);
        barParams.bottomMargin = dp(8);
        bar.setLayoutParams(barParams);
        limitCard.addView(bar);

        TextView limitNotice = new TextView(requireContext());
        limitNotice.setText("Đã sử dụng 64% hạn mức tháng. Hạn mức tự động làm mới vào ngày 01.");
        limitNotice.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink_muted));
        limitNotice.setTextSize(11);
        limitCard.addView(limitNotice);

        binding.content.addView(limitCard);

        // Activity Chart Card
        LinearLayout chartCard = new LinearLayout(requireContext());
        chartCard.setOrientation(LinearLayout.VERTICAL);
        chartCard.setBackgroundResource(R.drawable.glass_panel_card);
        chartCard.setElevation(dp(4));
        chartCard.setPadding(dp(18), dp(18), dp(18), dp(18));
        chartCard.setLayoutParams(cardParams);

        TextView chartTitle = new TextView(requireContext());
        chartTitle.setText("Thống kê thu nhập & chi tiêu tuần");
        chartTitle.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink));
        chartTitle.setTextSize(14);
        chartTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        chartCard.addView(chartTitle);

        SignatureBarChartView chart = new SignatureBarChartView(requireContext());
        LinearLayout.LayoutParams chartParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110));
        chartParams.topMargin = dp(12);
        chart.setLayoutParams(chartParams);
        chartCard.addView(chart);

        binding.content.addView(chartCard);

        // Upcoming Payments (Hóa đơn tiền điện, Internet)
        TextView upcomingHeader = new TextView(requireContext());
        upcomingHeader.setText("Hóa đơn sắp tới");
        upcomingHeader.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink));
        upcomingHeader.setTextSize(17);
        upcomingHeader.setTypeface(null, android.graphics.Typeface.BOLD);
        upcomingHeader.setPadding(0, 0, 0, dp(12));
        binding.content.addView(upcomingHeader);

        binding.content.addView(createUpcomingBillTile("Hóa đơn tiền điện EVN", "Hạn thanh toán: 28/11/2026", "420.000 ₫"));
        binding.content.addView(createUpcomingBillTile("Cước Internet VNPT Fiber", "Hạn thanh toán: 30/11/2026", "250.000 ₫"));
    }

    // ==========================================
    // 3. OFFICER: KHÁCH HÀNG (Customer Directory)
    // ==========================================
    private void renderOfficerCustomers() {
        MaterialButton addCustomerBtn = new MaterialButton(requireContext());
        addCustomerBtn.setText("+ Cấp mới hồ sơ khách hàng");
        addCustomerBtn.setTextColor(ContextCompat.getColor(requireContext(), R.color.white));
        addCustomerBtn.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.navy));
        addCustomerBtn.setCornerRadius(dp(18));
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        btnParams.bottomMargin = dp(18);
        addCustomerBtn.setLayoutParams(btnParams);
        addCustomerBtn.setOnClickListener(v -> showProvisionCustomerDialog());
        binding.content.addView(addCustomerBtn);

        TextView dirTitle = new TextView(requireContext());
        dirTitle.setText("Danh sách khách hàng & tài khoản quản lý");
        dirTitle.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink));
        dirTitle.setTextSize(16);
        dirTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        dirTitle.setPadding(0, 0, 0, dp(12));
        binding.content.addView(dirTitle);

        if (accounts != null && !accounts.isEmpty()) {
            for (ReadModels.AccountSummary acc : accounts) {
                LinearLayout card = new LinearLayout(requireContext());
                card.setOrientation(LinearLayout.VERTICAL);
                card.setBackgroundResource(R.drawable.glass_panel_card);
                card.setPadding(dp(16), dp(16), dp(16), dp(16));
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                p.bottomMargin = dp(12);
                card.setLayoutParams(p);

                String maskedOwner = acc.ownerUid.isEmpty() ? "demo_customer" :
                        (acc.ownerUid.length() > 6 ? acc.ownerUid.substring(0, 3) + "••••" + acc.ownerUid.substring(acc.ownerUid.length() - 3) : acc.ownerUid);

                TextView ownerText = new TextView(requireContext());
                ownerText.setText("Khách hàng: " + maskedOwner);
                ownerText.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink));
                ownerText.setTextSize(15);
                ownerText.setTypeface(null, android.graphics.Typeface.BOLD);
                card.addView(ownerText);

                TextView detailText = new TextView(requireContext());
                detailText.setText(Phase2Presentation.accountTitle(acc.type) + " · " + acc.id + "\nSố dư: " + formatMoney(acc.balanceMinor, acc.currency) + " · " + acc.status);
                detailText.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink_muted));
                detailText.setTextSize(13);
                detailText.setPadding(0, dp(4), 0, dp(8));
                card.addView(detailText);

                MaterialButton editBtn = new MaterialButton(requireContext());
                editBtn.setText("Đổi tên hồ sơ khách hàng");
                editBtn.setTextColor(ContextCompat.getColor(requireContext(), R.color.navy));
                editBtn.setBackgroundResource(R.drawable.glass_pill_button);
                editBtn.setCornerRadius(dp(14));
                editBtn.setOnClickListener(v -> showEditCustomerDialog(acc.ownerUid.isEmpty() ? "demo_customer" : acc.ownerUid));
                card.addView(editBtn);

                binding.content.addView(card);
            }
        } else {
            TextView empty = new TextView(requireContext());
            empty.setText("Chưa có danh sách khách hàng từ hệ thống.");
            empty.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink_muted));
            binding.content.addView(empty);
        }
    }

    // ==========================================
    // 4. OFFICER: DUYỆT & TÀI KHOẢN
    // ==========================================
    private void renderOfficerAccounts() {
        TextView queueTitle = new TextView(requireContext());
        queueTitle.setText("Hồ sơ eKYC & Tài khoản kiểm soát");
        queueTitle.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink));
        queueTitle.setTextSize(16);
        queueTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        queueTitle.setPadding(0, 0, 0, dp(12));
        binding.content.addView(queueTitle);

        if (accounts != null) {
            for (ReadModels.AccountSummary account : accounts) {
                String maskedOwner = account.ownerUid.isEmpty() ? "" :
                        "Khách hàng: " + (account.ownerUid.length() > 6 ? account.ownerUid.substring(0, 3) + "••••" : account.ownerUid) + "\n";
                TextView row = addLine(maskedOwner + Phase2Presentation.accountTitle(account.type) + "  •  " + account.status,
                        formatMoney(account.balanceMinor, account.currency) + "\nMã tài khoản: " + account.id);
                row.setBackgroundResource(R.drawable.glass_panel_card);
                row.setClickable(true);
                row.setFocusable(true);
                row.setContentDescription("Mở chi tiết " + Phase2Presentation.accountTitle(account.type));
                row.setOnClickListener(v -> openDetails(v, account));
            }
        }
    }

    private void hideKeyboard(View view) {
        if (view == null) return;
        InputMethodManager imm = (InputMethodManager) view.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    // Dialog: Provision New Customer
    private void showProvisionCustomerDialog() {
        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(10), dp(20), dp(10));
        layout.setFocusableInTouchMode(true);

        final EditText nameInput = new EditText(requireContext());
        nameInput.setHint("Họ và tên khách hàng");
        nameInput.setBackgroundResource(R.drawable.input_glass_field);
        nameInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        layout.addView(nameInput);

        final EditText emailInput = new EditText(requireContext());
        emailInput.setHint("Email khách hàng");
        emailInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        emailInput.setBackgroundResource(R.drawable.input_glass_field);
        emailInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(10);
        emailInput.setLayoutParams(p);
        layout.addView(emailInput);

        final EditText phoneInput = new EditText(requireContext());
        phoneInput.setHint("Số điện thoại Việt Nam 10 chữ số (VD: 0901234567)");
        phoneInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        phoneInput.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(10)});
        phoneInput.setBackgroundResource(R.drawable.input_glass_field);
        phoneInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams phoneParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        phoneParams.topMargin = dp(10);
        phoneInput.setLayoutParams(phoneParams);
        layout.addView(phoneInput);

        final EditText customerNoInput = new EditText(requireContext());
        customerNoInput.setHint("Mã khách hàng (nếu đã được cấp)");
        customerNoInput.setBackgroundResource(R.drawable.input_glass_field);
        customerNoInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams noParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noParams.topMargin = dp(10);
        customerNoInput.setLayoutParams(noParams);
        layout.addView(customerNoInput);

        final EditText addressInput = new EditText(requireContext());
        addressInput.setHint("Địa chỉ (tùy chọn)");
        addressInput.setBackgroundResource(R.drawable.input_glass_field);
        addressInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams addrParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addrParams.topMargin = dp(10);
        addressInput.setLayoutParams(addrParams);
        layout.addView(addressInput);

        ScrollView scrollView = new ScrollView(requireContext());
        scrollView.setFillViewport(true);
        scrollView.addView(layout);
        scrollView.setFocusableInTouchMode(true);

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Cấp mới tài khoản khách hàng")
                .setView(scrollView)
                .setPositiveButton("Tạo hồ sơ", null)
                .setNegativeButton("Hủy", null)
                .create();

        // Prevent accidental dismissal when tapping outside so entered data is not lost
        dialog.setCanceledOnTouchOutside(false);

        View.OnTouchListener hideKeyboardListener = (v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                View focus = dialog.getCurrentFocus();
                if (focus != null) {
                    hideKeyboard(focus);
                    focus.clearFocus();
                }
            }
            return false;
        };

        scrollView.setOnTouchListener(hideKeyboardListener);
        layout.setOnTouchListener(hideKeyboardListener);

        dialog.setOnShowListener(d -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().getDecorView().setOnTouchListener(hideKeyboardListener);
            }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(btn -> {
                hideKeyboard(dialog.getCurrentFocus());
                String name = nameInput.getText().toString().trim();
                String email = emailInput.getText().toString().trim();
                String phoneNumber = phoneInput.getText().toString().trim();
                String customerNo = customerNoInput.getText().toString().trim();
                String address = addressInput.getText().toString().trim();
                if (name.isEmpty() || email.isEmpty() || phoneNumber.isEmpty()) {
                    Toast.makeText(requireContext(), "Vui lòng nhập họ tên, email và số điện thoại.", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!phoneNumber.matches("0[0-9]{9}")) {
                    phoneInput.setError("Nhập đúng 10 chữ số, bắt đầu bằng 0");
                    Toast.makeText(requireContext(), "Số điện thoại Việt Nam phải có 10 chữ số, bắt đầu bằng 0.", Toast.LENGTH_SHORT).show();
                    return;
                }
                dialog.dismiss();
                try {
                    String identityKey = name + "|" + email.toLowerCase(Locale.ROOT) + "|" + phoneNumber
                            + "|" + customerNo + "|" + address;
                    if (!identityKey.equals(pendingProvisionKey)) {
                        pendingProvisionKey = identityKey;
                        pendingProvisionRequestId = UUID.randomUUID().toString();
                    }
                    JSONObject body = new JSONObject()
                            .put("requestId", pendingProvisionRequestId)
                            .put("fullName", name)
                            .put("email", email)
                            .put("phoneNumber", phoneNumber);
                    if (!customerNo.isEmpty()) body.put("customerNo", customerNo);
                    if (!address.isEmpty()) body.put("address", address);
                    application.getAppContainer().getBackendApiClient().post("/officer/customers", body.toString())
                            .addOnSuccessListener(resp -> {
                                if (resp.statusCode >= 200 && resp.statusCode < 300) {
                                    pendingProvisionKey = null;
                                    pendingProvisionRequestId = null;
                                    String accountId = "";
                                    try { accountId = new JSONObject(resp.body).optString("accountId", ""); }
                                    catch (Exception ignored) { }
                                    String createdMessage = "Đã tạo hồ sơ ONBOARDING" +
                                            (accountId.isEmpty() ? "." : " và tài khoản checking " + accountId + ".")
                                            + " Khách hàng cần đặt mật khẩu qua email trước khi đăng nhập.";
                                    application.getAppContainer().getAuthRepository().sendPasswordResetEmail(email)
                                            .addOnCompleteListener(reset -> {
                                                if (!isAdded()) return;
                                                String finalMessage = reset.isSuccessful() ? createdMessage
                                                        : createdMessage + " Email chưa gửi được; khách hàng có thể dùng mục Đặt/quên mật khẩu ở màn đăng nhập.";
                                                new MaterialAlertDialogBuilder(requireContext()).setTitle("Đã tiếp nhận khách hàng")
                                                        .setMessage(finalMessage).setPositiveButton("Đóng", null).show();
                                                load();
                                            });
                                } else {
                                    String error = "";
                                    try { error = new JSONObject(resp.body).optString("error", ""); }
                                    catch (Exception ignored) { }
                                    String message = "Không thể tạo hồ sơ (HTTP " + resp.statusCode + ").";
                                    if ("CUSTOMER_NUMBER_EXISTS".equals(error)) message = "Mã khách hàng đã tồn tại.";
                                    else if ("CONTACT_PHONE_EXISTS".equals(error)) message = "Số điện thoại liên hệ đã được sử dụng.";
                                    else if ("PROVISION_IDENTITY_FAILED".equals(error)) message = "Email hoặc số điện thoại đã được đăng ký, hoặc Firebase Auth chưa sẵn sàng.";
                                    else if ("IDEMPOTENCY_CONFLICT".equals(error)) message = "Yêu cầu tạo hồ sơ không khớp lần gửi trước; hãy kiểm tra dữ liệu đã nhập.";
                                    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
                                }
                            })
                            .addOnFailureListener(err -> Toast.makeText(requireContext(), "Chưa thể cấp mới: " + err.getMessage(), Toast.LENGTH_LONG).show());
                } catch (Exception e) {
                    Toast.makeText(requireContext(), "Lỗi: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        });

        dialog.show();
    }

    // Edit only customer fields accepted by the backend; identity and role fields remain protected.
    private void showEditCustomerDialog(String customerId) {
        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(10), dp(20), dp(10));
        layout.setFocusableInTouchMode(true);

        final EditText nameInput = new EditText(requireContext());
        nameInput.setHint("Họ tên mới (để trống nếu không đổi)");
        nameInput.setBackgroundResource(R.drawable.input_glass_field);
        nameInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        layout.addView(nameInput);

        final EditText emailInput = new EditText(requireContext());
        emailInput.setHint("Email liên hệ mới (tùy chọn)");
        emailInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        emailInput.setBackgroundResource(R.drawable.input_glass_field);
        emailInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        layout.addView(emailInput);

        final EditText phoneInput = new EditText(requireContext());
        phoneInput.setHint("Số điện thoại liên hệ mới, dạng +84... (tùy chọn)");
        phoneInput.setInputType(InputType.TYPE_CLASS_PHONE);
        phoneInput.setBackgroundResource(R.drawable.input_glass_field);
        phoneInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        layout.addView(phoneInput);

        final EditText addressInput = new EditText(requireContext());
        addressInput.setHint("Địa chỉ mới (tùy chọn)");
        addressInput.setBackgroundResource(R.drawable.input_glass_field);
        addressInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        layout.addView(addressInput);

        final EditText reasonInput = new EditText(requireContext());
        reasonInput.setHint("Lý do điều chỉnh (bắt buộc)");
        reasonInput.setBackgroundResource(R.drawable.input_glass_field);
        reasonInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(10);
        reasonInput.setLayoutParams(p);
        layout.addView(reasonInput);

        ScrollView scrollView = new ScrollView(requireContext());
        scrollView.setFillViewport(true);
        scrollView.addView(layout);
        scrollView.setFocusableInTouchMode(true);

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Cập nhật hồ sơ khách hàng")
                .setMessage("Thao tác này được ghi vào audit log của hệ thống ngân hàng.")
                .setView(scrollView)
                .setPositiveButton("Xác nhận", null)
                .setNegativeButton("Hủy", null)
                .create();

        dialog.setCanceledOnTouchOutside(false);

        View.OnTouchListener hideKeyboardListener = (v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                View focus = dialog.getCurrentFocus();
                if (focus != null) {
                    hideKeyboard(focus);
                    focus.clearFocus();
                }
            }
            return false;
        };

        scrollView.setOnTouchListener(hideKeyboardListener);
        layout.setOnTouchListener(hideKeyboardListener);

        dialog.setOnShowListener(d -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().getDecorView().setOnTouchListener(hideKeyboardListener);
            }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(btn -> {
                hideKeyboard(dialog.getCurrentFocus());
                String newName = nameInput.getText().toString().trim();
                String newEmail = emailInput.getText().toString().trim();
                String newPhone = phoneInput.getText().toString().trim();
                String newAddress = addressInput.getText().toString().trim();
                String reason = reasonInput.getText().toString().trim();
                if (newName.isEmpty() && newEmail.isEmpty() && newPhone.isEmpty() && newAddress.isEmpty()) {
                    Toast.makeText(requireContext(), "Nhập ít nhất một trường cần cập nhật.", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (reason.length() < 3) {
                    Toast.makeText(requireContext(), "Lý do cần có ít nhất 3 ký tự.", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!newEmail.isEmpty() && !android.util.Patterns.EMAIL_ADDRESS.matcher(newEmail).matches()) {
                    emailInput.setError("Email không hợp lệ");
                    return;
                }
                if (!newPhone.isEmpty() && !newPhone.matches("\\+[1-9][0-9]{7,14}")) {
                    phoneInput.setError("Dùng định dạng quốc tế, ví dụ +84901234567");
                    return;
                }
                dialog.dismiss();
                try {
                    JSONObject body = new JSONObject().put("reason", reason);
                    if (!newName.isEmpty()) body.put("fullName", newName);
                    if (!newEmail.isEmpty()) body.put("contactEmail", newEmail);
                    if (!newPhone.isEmpty()) body.put("contactPhone", newPhone);
                    if (!newAddress.isEmpty()) body.put("address", newAddress);
                    application.getAppContainer().getBackendApiClient().patch("/officer/customers/" + customerId, body.toString())
                            .addOnSuccessListener(resp -> {
                                if (resp.statusCode >= 200 && resp.statusCode < 300) {
                                    String summary = "Đã cập nhật hồ sơ.";
                                    try {
                                        JSONObject changes = new JSONObject(resp.body).optJSONObject("changes");
                                        if (changes != null) summary += "\n\nTrước:\n" + changes.optJSONObject("before")
                                                + "\n\nSau:\n" + changes.optJSONObject("after");
                                    } catch (Exception ignored) { }
                                    new MaterialAlertDialogBuilder(requireContext()).setTitle("Thành công")
                                            .setMessage(summary).setPositiveButton("Đóng", null).show();
                                    load();
                                } else {
                                    String error = "";
                                    try { error = new JSONObject(resp.body).optString("error", ""); }
                                    catch (Exception ignored) { }
                                    Toast.makeText(requireContext(), "Cập nhật thất bại (" + (error.isEmpty() ? "HTTP " + resp.statusCode : error) + ").", Toast.LENGTH_LONG).show();
                                }
                            })
                            .addOnFailureListener(err -> Toast.makeText(requireContext(), "Lỗi: " + err.getMessage(), Toast.LENGTH_LONG).show());
                } catch (Exception e) {
                    Toast.makeText(requireContext(), "Lỗi: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        });

        dialog.show();
    }

    private void showCustomerChangeHistory(String customerId) {
        application.getAppContainer().getBackendApiClient().get("/officer/customers/" + customerId + "/changes?limit=20")
                .addOnCompleteListener(task -> {
                    if (!isAdded()) return;
                    if (!task.isSuccessful() || task.getResult() == null || task.getResult().statusCode != 200) {
                        Toast.makeText(requireContext(), "Không tải được lịch sử thay đổi.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    try {
                        JSONArray items = new JSONObject(task.getResult().body).optJSONArray("items");
                        StringBuilder history = new StringBuilder();
                        if (items != null) for (int i = 0; i < items.length(); i++) {
                            JSONObject item = items.getJSONObject(i);
                            if (history.length() > 0) history.append("\n\n────────\n\n");
                            history.append("Thời điểm: ").append(item.optLong("createdAt"))
                                    .append("\nNgười thực hiện: ").append(item.optString("actorUid"))
                                    .append("\nLý do: ").append(item.optString("reason"))
                                    .append("\nTrước: ").append(item.optJSONObject("before"))
                                    .append("\nSau: ").append(item.optJSONObject("after"));
                        }
                        if (history.length() == 0) history.append("Chưa có thay đổi hồ sơ nào.");
                        new MaterialAlertDialogBuilder(requireContext()).setTitle("Lịch sử hồ sơ khách hàng")
                                .setMessage(history.toString()).setPositiveButton("Đóng", null).show();
                    } catch (Exception ignored) {
                        Toast.makeText(requireContext(), "Dữ liệu lịch sử không hợp lệ.", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private View createCircularAction(String label, int iconRes, View.OnClickListener listener) {
        LinearLayout item = new LinearLayout(requireContext());
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        item.setLayoutParams(p);

        ImageButton btn = new ImageButton(requireContext());
        btn.setImageResource(iconRes);
        btn.setColorFilter(ContextCompat.getColor(requireContext(), R.color.navy));
        btn.setBackgroundResource(R.drawable.circle_action_button);
        btn.setElevation(dp(3));
        btn.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(54), dp(54));
        btn.setLayoutParams(bp);
        btn.setOnClickListener(listener);
        item.addView(btn);

        TextView tv = new TextView(requireContext());
        tv.setText(label);
        tv.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink));
        tv.setTextSize(11);
        tv.setPadding(0, dp(6), 0, 0);
        item.addView(tv);

        return item;
    }

    private View createAccountTile(ReadModels.AccountSummary acc) {
        LinearLayout tile = new LinearLayout(requireContext());
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setBackgroundResource(R.drawable.glass_panel_card);
        tile.setPadding(dp(16), dp(16), dp(16), dp(16));
        tile.setClickable(true);
        tile.setFocusable(true);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(12);
        tile.setLayoutParams(p);

        TextView title = new TextView(requireContext());
        title.setText(Phase2Presentation.accountTitle(acc.type));
        title.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink));
        title.setTextSize(15);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        tile.addView(title);

        TextView balance = new TextView(requireContext());
        balance.setText(formatMoney(acc.balanceMinor, acc.currency));
        balance.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink));
        balance.setTextSize(20);
        balance.setPadding(0, dp(4), 0, dp(4));
        tile.addView(balance);

        TextView sub = new TextView(requireContext());
        sub.setText(Phase2Presentation.accountSubtitle(acc.type) + " · " + acc.status);
        sub.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink_muted));
        sub.setTextSize(12);
        tile.addView(sub);

        tile.setOnClickListener(v -> openDetails(v, acc));
        return tile;
    }

    private View createUpcomingBillTile(String title, String dueDate, String amount) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.glass_panel_card);
        row.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(10);
        row.setLayoutParams(p);

        LinearLayout textCol = new LinearLayout(requireContext());
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tcp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textCol.setLayoutParams(tcp);

        TextView t = new TextView(requireContext());
        t.setText(title);
        t.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink));
        t.setTextSize(14);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        textCol.addView(t);

        TextView d = new TextView(requireContext());
        d.setText(dueDate);
        d.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink_muted));
        d.setTextSize(12);
        textCol.addView(d);
        row.addView(textCol);

        TextView amt = new TextView(requireContext());
        amt.setText(amount);
        amt.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink));
        amt.setTextSize(14);
        amt.setTypeface(null, android.graphics.Typeface.BOLD);
        amt.setPadding(dp(8), 0, dp(12), 0);
        row.addView(amt);

        MaterialButton payBtn = new MaterialButton(requireContext());
        payBtn.setText("Trả");
        payBtn.setTextColor(ContextCompat.getColor(requireContext(), R.color.white));
        payBtn.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.navy));
        payBtn.setCornerRadius(dp(12));
        payBtn.setOnClickListener(v -> {
            UtilitiesHubDialogFragment dialog = new UtilitiesHubDialogFragment();
            dialog.show(getChildFragmentManager(), "PayBill");
        });
        row.addView(payBtn);

        return row;
    }

    private void showNotificationsDialog() {
        if (notifications == null || notifications.isEmpty()) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Thông báo")
                    .setMessage("Bạn không có thông báo mới.")
                    .setPositiveButton("Đóng", null)
                    .show();
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (ReadModels.NotificationSummary n : notifications) {
            sb.append("• ").append(n.title).append("\n").append(n.body).append("\n\n");
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Thông báo tài khoản")
                .setMessage(sb.toString().trim())
                .setPositiveButton("Đóng", null)
                .show();
    }

    private TextView addLine(String title, String detail) {
        TextView line = new TextView(requireContext());
        line.setText(title + (detail.isEmpty() ? "" : "\n" + detail));
        line.setTextColor(requireContext().getColor(R.color.ink));
        line.setTextSize(15);
        line.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(10);
        line.setLayoutParams(p);
        binding.content.addView(line);
        return line;
    }

    private String accountStatusMessage() {
        switch (accountStatus) {
            case LOADING: return "Đang tải dữ liệu…";
            case CACHED: return "Dữ liệu được lưu trong bộ nhớ đệm.";
            case ERROR: return accountMessage == null ? "Không thể tải dữ liệu." : accountMessage;
            case EMPTY: return "Chưa có tài khoản nào.";
            default: return "Dữ liệu tài khoản đã đồng bộ.";
        }
    }

    private void openDetails(View anchor, ReadModels.AccountSummary account) {
        Bundle args = new Bundle();
        args.putString("accountId", account.id);
        args.putString("accountType", account.type);
        int destination = "SAVINGS".equalsIgnoreCase(account.type) ? R.id.savingsDetail
                : "MORTGAGE".equalsIgnoreCase(account.type) ? R.id.mortgageDetail : R.id.accountDetail;
        Navigation.findNavController(anchor).navigate(destination, args);
    }

    private String formatMoney(long amount, String currency) {
        return NumberFormat.getIntegerInstance(new Locale("vi", "VN")).format(amount) + " " + currency;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}

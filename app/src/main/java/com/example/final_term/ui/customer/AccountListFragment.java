package com.example.final_term.ui.customer;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import android.graphics.drawable.GradientDrawable;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.HorizontalScrollView;
import android.widget.FrameLayout;
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
import com.example.final_term.ui.common.SignatureBarChartView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.text.DateFormat;
import java.util.UUID;

public final class AccountListFragment extends Fragment {
    private FragmentAccountListBinding binding;
    private FinalTermApplication application;
    private String uid;
    private UserRole role;
    private List<ReadModels.AccountSummary> accounts;
    private final List<JSONObject> customers = new ArrayList<>();
    private String customerCursor;
    private String directoryStatus = "Đang tải hồ sơ khách hàng…";
    private boolean directoryLoading;
    private boolean directoryError;
    private List<ReadModels.NotificationSummary> notifications;
    private List<ReadModels.TransactionSummary> transactions;
    private ReadModelResult.Status accountStatus;
    private String accountMessage;
    private int activeNavItem = R.id.nav_accounts;
    private String pendingProvisionKey;
    private String pendingProvisionRequestId;
    private Long walletBalanceMinor;
    private String walletCurrency = "VND";
    private boolean walletBillsLoading;
    private boolean walletBillsLoaded;
    private final List<JSONObject> walletUpcomingBills = new ArrayList<>();
    private final Map<String, JSONObject> cardSnapshots = new HashMap<>();
    private final Set<String> cardRequestsInFlight = new HashSet<>();
    private final Set<String> cardRequestsLoaded = new HashSet<>();
    private final Set<String> cardRequestsFailed = new HashSet<>();
    private String selectedCardAccountId;
    private float cardSwipeStartX;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        directoryLoading = false;
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

        androidx.navigation.NavController navController = Navigation.findNavController(view);
        androidx.navigation.NavBackStackEntry previousEntry = navController.getPreviousBackStackEntry();
        if (previousEntry != null && previousEntry.getDestination().getId() == R.id.customerHome) {
            activeNavItem = R.id.nav_accounts;
            if (navController.getCurrentBackStackEntry() != null) {
                navController.getCurrentBackStackEntry().getSavedStateHandle()
                        .set("activeNavItem", activeNavItem);
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
                            if (role == UserRole.OFFICER) load();
                            else renderAll();
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
                        String signedInUid = authState.getUser().getUid();
                        if (!java.util.Objects.equals(uid, signedInUid)) {
                            cardSnapshots.clear();
                            cardRequestsInFlight.clear();
                            cardRequestsLoaded.clear();
                            cardRequestsFailed.clear();
                            selectedCardAccountId = null;
                        }
                        uid = signedInUid;
                        role = authState.getUser().getRole();
                        updateScreenTitle();
                        if (role == UserRole.OFFICER && activeNavItem == R.id.nav_wallet
                                && Navigation.findNavController(view).getCurrentDestination() != null
                                && Navigation.findNavController(view).getCurrentDestination().getId() == R.id.accountList) {
                            Navigation.findNavController(view).navigate(R.id.officerCheckingAccounts);
                            return;
                        }
                        load();
                    }
                });
    }

    private void updateScreenTitle() {
        if (role == UserRole.OFFICER) {
            binding.titleText.setText(activeNavItem == R.id.nav_wallet ? "Khách hàng" : "Duyệt & Tài khoản");
        } else {
            binding.titleText.setText(activeNavItem == R.id.nav_wallet ? "Ví" : "Tài khoản");
        }
    }

    private void load() {
        if (uid == null) return;
        if (isCustomerDirectory()) { loadDirectory(null); return; }
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
        accountMessage = result.getStatus() == ReadModelResult.Status.CACHED
                ? "Đang hiển thị dữ liệu đã lưu trên thiết bị."
                : "Chưa thể tải danh sách tài khoản. Vui lòng thử lại.";
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
        if (isCustomerDirectory()) {
            binding.statusText.setText(directoryStatus);
            binding.retryButton.setVisibility(directoryError ? View.VISIBLE : View.GONE);
            binding.content.removeAllViews();
            renderOfficerCustomers();
            return;
        }
        binding.statusText.setText(accountStatus == null ? "Đang tải dữ liệu…" : accountStatusMessage());
        binding.retryButton.setVisibility(accountStatus == ReadModelResult.Status.ERROR ? View.VISIBLE : View.GONE);
        binding.content.removeAllViews();

        if (role == UserRole.OFFICER) {
            renderOfficerAccounts();
        } else if (activeNavItem == R.id.nav_wallet) {
            renderCustomerWallet();
        } else {
            renderCustomerCards();
        }
    }

    // ==========================================
    // 1. CUSTOMER: TÀI KHOẢN & THẺ (Cards_Screen)
    // ==========================================
    private void renderCustomerCards() {
        if (accounts == null) {
            TextView loading = walletText("Đang tải thẻ…", 14, R.color.ink_muted, false);
            loading.setGravity(Gravity.CENTER);
            loading.setPadding(0, dp(36), 0, dp(36));
            binding.content.addView(loading);
            return;
        }
        List<ReadModels.AccountSummary> cardAccounts = new ArrayList<>();
        List<ReadModels.AccountSummary> otherAccounts = new ArrayList<>();
        for (ReadModels.AccountSummary account : accounts) {
            if ("CLOSED".equalsIgnoreCase(account.status)) continue;
            if ("CHECKING".equalsIgnoreCase(account.type) && "ACTIVE".equalsIgnoreCase(account.status)) {
                cardAccounts.add(account);
            } else if (!"CHECKING".equalsIgnoreCase(account.type)) {
                otherAccounts.add(account);
            }
        }

        if (cardAccounts.isEmpty()) {
            LinearLayout empty = walletCard();
            empty.addView(walletText("Chưa có thẻ thanh toán", 18, R.color.ink, true));
            TextView hint = walletText("Thẻ sẽ xuất hiện tại đây khi tài khoản thanh toán được kích hoạt.",
                    13, R.color.ink_muted, false);
            hint.setPadding(0, dp(8), 0, 0);
            empty.addView(hint);
            binding.content.addView(empty);
        } else {
            int selectedIndex = findSelectedCardIndex(cardAccounts);
            ReadModels.AccountSummary selected = cardAccounts.get(selectedIndex);
            selectedCardAccountId = selected.id;
            FrameLayout stack = new FrameLayout(requireContext());
            stack.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(380)));
            if (cardAccounts.size() > 1) {
                View leftPeek = createCardBackplate(false);
                FrameLayout.LayoutParams leftParams = new FrameLayout.LayoutParams(dp(216), dp(330), Gravity.CENTER_VERTICAL | Gravity.START);
                leftParams.leftMargin = dp(8);
                stack.addView(leftPeek, leftParams);
                View rightPeek = createCardBackplate(true);
                FrameLayout.LayoutParams rightParams = new FrameLayout.LayoutParams(dp(216), dp(330), Gravity.CENTER_VERTICAL | Gravity.END);
                rightParams.rightMargin = dp(8);
                stack.addView(rightPeek, rightParams);
            }
            View card = createPaymentCard(selected);
            FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(dp(244), dp(356), Gravity.CENTER);
            stack.addView(card, cardParams);
            stack.setOnTouchListener((view, event) -> {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    cardSwipeStartX = event.getX();
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    float delta = event.getX() - cardSwipeStartX;
                    if (Math.abs(delta) > dp(44) && cardAccounts.size() > 1) {
                        int next = (selectedIndex + (delta < 0 ? 1 : -1) + cardAccounts.size()) % cardAccounts.size();
                        selectedCardAccountId = cardAccounts.get(next).id;
                        renderAll();
                    }
                    return true;
                }
                return true;
            });
            binding.content.addView(stack);
            if (cardAccounts.size() > 1) binding.content.addView(createCardPagination(cardAccounts, selectedIndex));

            MaterialButton details = new MaterialButton(requireContext());
            details.setText("Chi tiết thẻ  ›");
            details.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink));
            details.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.glass));
            details.setCornerRadius(dp(26));
            details.setOnClickListener(v -> openDetails(v, selected));
            LinearLayout.LayoutParams detailsParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
            detailsParams.setMargins(dp(4), dp(8), dp(4), dp(18));
            binding.content.addView(details, detailsParams);
        }

        if (!otherAccounts.isEmpty()) {
            TextView title = walletText("Tài khoản khác", 16, R.color.ink, true);
            title.setPadding(dp(2), dp(8), dp(2), dp(10));
            binding.content.addView(title);
            for (ReadModels.AccountSummary account : otherAccounts) binding.content.addView(createOtherAccountRow(account));
        }
    }

    private void loadStripeCard(ReadModels.AccountSummary account) {
        if (cardRequestsLoaded.contains(account.id) || !cardRequestsInFlight.add(account.id)) return;
        var current = binding;
        String requestedUid = uid;
        application.getAppContainer().getBackendApiClient()
                .get("/accounts/" + android.net.Uri.encode(account.id) + "/stripe-sandbox")
                .addOnCompleteListener(task -> {
                    cardRequestsInFlight.remove(account.id);
                    if (binding != current || !java.util.Objects.equals(uid, requestedUid)) return;
                    cardRequestsLoaded.add(account.id);
                    try {
                        if (task.isSuccessful() && task.getResult() != null && task.getResult().statusCode == 200) {
                            cardSnapshots.put(account.id, new JSONObject(task.getResult().body));
                        } else {
                            cardRequestsFailed.add(account.id);
                        }
                    } catch (Exception ignored) {
                        cardRequestsFailed.add(account.id);
                    }
                    renderAll();
                });
    }

    private int findSelectedCardIndex(List<ReadModels.AccountSummary> accountsForCards) {
        if (selectedCardAccountId != null) {
            for (int i = 0; i < accountsForCards.size(); i++) {
                if (selectedCardAccountId.equals(accountsForCards.get(i).id)) return i;
            }
        }
        return 0;
    }

    private View createCardBackplate(boolean right) {
        View plate = new View(requireContext());
        plate.setBackground(cardDrawable(0x99B9C4DC, 0x668D9CC0));
        plate.setAlpha(right ? 0.62f : 0.48f);
        plate.setRotation(right ? 7f : -7f);
        plate.setElevation(dp(5));
        return plate;
    }

    private View createPaymentCard(ReadModels.AccountSummary account) {
        JSONObject snapshot = cardSnapshots.get(account.id);
        JSONObject cardData = snapshot == null ? null : snapshot.optJSONObject("card");
        boolean loading = false;
        String state = snapshot == null ? "" : snapshot.optString("status", "");
        String brand = cardData == null ? "NIMBUS" : cardData.optString("brand", "Thẻ thanh toán").toUpperCase(Locale.ROOT);
        String last4 = cardData == null ? "" : cardData.optString("last4", "");
        String cardStatus = cardData == null ? "" : cardData.optString("status", "");

        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.TOP);
        card.setPadding(dp(20), dp(22), dp(20), dp(20));
        card.setBackground(cardDrawable(0xFF5E7096, 0xFFE4E9F2));
        card.setElevation(dp(18));
        card.setContentDescription("Thẻ thanh toán Nimbus" + (last4.isEmpty() ? "" : " kết thúc bằng " + last4));
        if (android.os.Build.VERSION.SDK_INT >= 21) card.setOutlineProvider(ViewOutlineProvider.BACKGROUND);

        LinearLayout top = new LinearLayout(requireContext());
        top.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout chip = new FrameLayout(requireContext());
        GradientDrawable chipShape = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFFFCE9B8, 0xFFE8C581, 0xFF9E7B34});
        chipShape.setCornerRadius(dp(7));
        chipShape.setStroke(dp(1), 0x99FFFFFF);
        chip.setBackground(chipShape);
        FrameLayout.LayoutParams vertical = new FrameLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER);
        vertical.leftMargin = dp(14);
        View chipDivider = new View(requireContext()); chipDivider.setBackgroundColor(0x665E4105); chip.addView(chipDivider, vertical);
        LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(dp(42), dp(32));
        top.addView(chip, chipParams);
        TextView brandText = walletText(brand, 16, R.color.white, true);
        brandText.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        brandText.setShadowLayer(dp(2), 0, dp(1), 0x66000000);
        LinearLayout.LayoutParams brandParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        top.addView(brandText, brandParams);
        card.addView(top);

        LinearLayout middle = new LinearLayout(requireContext());
        middle.setOrientation(LinearLayout.VERTICAL);
        middle.setGravity(Gravity.CENTER_VERTICAL);
        middle.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        if (!last4.isEmpty()) {
            TextView number = walletText("••••  ••••  ••••  " + last4, 16, R.color.white, true);
            number.setLetterSpacing(0.06f);
            number.setShadowLayer(dp(2), 0, dp(1), 0x55000000);
            middle.addView(number);
        } else {
            String stateMessage = loading || "pending".equalsIgnoreCase(state)
                    ? "Đang phát hành thẻ" : cardRequestsFailed.contains(account.id)
                    ? "Chưa có thẻ đã lưu" : "Chọn thẻ khi thanh toán";
            TextView cardState = walletText(stateMessage, 14, R.color.white, false);
            cardState.setShadowLayer(dp(2), 0, dp(1), 0x55000000);
            middle.addView(cardState);
        }
        card.addView(middle);

        LinearLayout bottom = new LinearLayout(requireContext());
        bottom.setGravity(Gravity.BOTTOM | Gravity.CENTER_VERTICAL);
        LinearLayout wordmark = new LinearLayout(requireContext());
        wordmark.setOrientation(LinearLayout.VERTICAL);
        TextView name = walletText("nimbus", 22, R.color.white, false);
        name.setLetterSpacing(-0.03f);
        TextView subtitle = walletText("THẺ THANH TOÁN", 9, R.color.white, true);
        subtitle.setAlpha(0.78f);
        wordmark.addView(name); wordmark.addView(subtitle); bottom.addView(wordmark);
        ImageView contactless = new ImageView(requireContext());
        contactless.setImageResource(R.drawable.ic_nav_payment);
        contactless.setColorFilter(ContextCompat.getColor(requireContext(), R.color.white));
        LinearLayout.LayoutParams contactParams = new LinearLayout.LayoutParams(dp(25), dp(25));
        contactParams.gravity = Gravity.BOTTOM;
        contactParams.leftMargin = dp(8);
        bottom.addView(contactless, contactParams);
        card.addView(bottom);

        String stateLabel = loading ? "Đang tải thông tin thẻ"
                : cardRequestsFailed.contains(account.id) ? "Chọn thẻ ở bước thanh toán"
                : cardData == null ? ("pending".equalsIgnoreCase(state) ? "Thẻ đang được phát hành"
                : "Chọn thẻ khi thanh toán") : formatCardStatus(cardStatus);
        TextView status = walletText(stateLabel, 12, R.color.ink_muted, false);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(7), 0, 0);
        card.addView(status);
        return card;
    }

    private GradientDrawable cardDrawable(int startColor, int endColor) {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{startColor, 0xFF8D9CC0, 0xFFB9C4DC, endColor, 0xFFF8FAFC});
        drawable.setCornerRadius(dp(28));
        drawable.setStroke(dp(1), 0xCCFFFFFF);
        return drawable;
    }

    private String formatCardStatus(String status) {
        if ("active".equalsIgnoreCase(status)) return "Thẻ đang hoạt động";
        if ("inactive".equalsIgnoreCase(status)) return "Thẻ chưa kích hoạt";
        if ("canceled".equalsIgnoreCase(status) || "cancelled".equalsIgnoreCase(status)) return "Thẻ đã hủy";
        if ("pending".equalsIgnoreCase(status)) return "Thẻ đang được phát hành";
        if ("paused".equalsIgnoreCase(status)) return "Thẻ tạm ngưng";
        return "Trạng thái thẻ chưa khả dụng";
    }

    private View createCardPagination(List<ReadModels.AccountSummary> cardAccounts, int selectedIndex) {
        LinearLayout dots = new LinearLayout(requireContext());
        dots.setGravity(Gravity.CENTER);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24));
        params.bottomMargin = dp(8);
        dots.setLayoutParams(params);
        for (int i = 0; i < cardAccounts.size(); i++) {
            final int index = i;
            View dot = new View(requireContext());
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.OVAL);
            shape.setColor(i == selectedIndex ? ContextCompat.getColor(requireContext(), R.color.white)
                    : 0x99FFFFFF);
            if (i == selectedIndex) shape.setStroke(dp(1), 0x33777777);
            dot.setBackground(shape);
            dot.setContentDescription("Thẻ " + (i + 1) + " trên " + cardAccounts.size());
            dot.setFocusable(true);
            dot.setClickable(true);
            dot.setOnClickListener(v -> { selectedCardAccountId = cardAccounts.get(index).id; renderAll(); });
            LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(i == selectedIndex ? dp(24) : dp(8), dp(8));
            dotParams.setMargins(dp(4), 0, dp(4), 0);
            dots.addView(dot, dotParams);
        }
        return dots;
    }

    private View createOtherAccountRow(ReadModels.AccountSummary account) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        row.setBackgroundResource(R.drawable.glass_panel_card);
        row.setClickable(true);
        row.setFocusable(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(10);
        row.setLayoutParams(params);
        LinearLayout text = new LinearLayout(requireContext());
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(walletText(Phase2Presentation.accountTitle(account.type), 14, R.color.ink, true));
        TextView state = walletText(accountStatusLabel(account.status), 12, R.color.ink_muted, false);
        state.setPadding(0, dp(4), 0, 0); text.addView(state);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageView arrow = new ImageView(requireContext());
        arrow.setImageResource(R.drawable.ic_chevron_right);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(20), dp(20)));
        row.setOnClickListener(v -> openDetails(v, account));
        return row;
    }

    private String accountStatusLabel(String status) {
        if ("ACTIVE".equalsIgnoreCase(status)) return "Đang hoạt động";
        if ("PENDING".equalsIgnoreCase(status)) return "Đang xử lý";
        if ("INACTIVE".equalsIgnoreCase(status)) return "Chưa kích hoạt";
        if ("CLOSED".equalsIgnoreCase(status)) return "Đã đóng";
        return "Cần kiểm tra trạng thái";
    }

    private void renderCustomerWallet() {
        ReadModels.AccountSummary checking = null;
        if (accounts != null) {
            for (ReadModels.AccountSummary account : accounts) {
                if ("CHECKING".equalsIgnoreCase(account.type) && "ACTIVE".equalsIgnoreCase(account.status)) {
                    checking = account;
                    break;
                }
            }
        }
        walletBalanceMinor = checking == null ? null
                : checking.availableMinor != null ? checking.availableMinor : checking.balanceMinor;
        walletCurrency = checking == null ? "VND" : checking.currency;
        if (!walletBillsLoaded && !walletBillsLoading) loadWalletBills();

        addWalletSectionTitle("Tài khoản thanh toán");
        LinearLayout balanceCard = walletCard();
        TextView label = walletText("Số dư khả dụng", 13, R.color.ink_muted, false);
        balanceCard.addView(label);
        TextView amount = walletText(checking == null ? "Chưa có tài khoản thanh toán"
                : walletBalanceMinor == null ? "—"
                : ReadSummaryFormatter.formatCurrency(walletBalanceMinor, walletCurrency), 27, R.color.ink, true);
        amount.setPadding(0, dp(8), 0, dp(4));
        balanceCard.addView(amount);
        if (checking != null) {
            TextView accountName = walletText("Tài khoản thanh toán mặc định", 12, R.color.ink_muted, false);
            balanceCard.addView(accountName);
        }
        binding.content.addView(balanceCard);

        addWalletSectionTitle("Hoạt động thu nhập · 7 ngày");
        LinearLayout activityCard = walletCard();
        long[] daily = new long[7];
        long thisWeek = 0;
        long previousWeek = 0;
        boolean hasPreviousWeekActivity = false;
        Calendar today = Calendar.getInstance();
        today.set(Calendar.HOUR_OF_DAY, 0); today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0); today.set(Calendar.MILLISECOND, 0);
        Calendar weekStart = (Calendar) today.clone();
        int dayOffset = (weekStart.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        weekStart.add(Calendar.DAY_OF_MONTH, -dayOffset);
        Calendar previousStart = (Calendar) weekStart.clone(); previousStart.add(Calendar.DAY_OF_MONTH, -7);
        Calendar nextWeekStart = (Calendar) weekStart.clone(); nextWeekStart.add(Calendar.DAY_OF_MONTH, 7);
        if (transactions != null) for (ReadModels.TransactionSummary transaction : transactions) {
            if (!"VND".equalsIgnoreCase(transaction.currency) || !transaction.isIncoming() || !("SETTLED".equalsIgnoreCase(transaction.status)
                    || "SUCCEEDED".equalsIgnoreCase(transaction.status)) || transaction.createdAt == null) continue;
            long timestamp = transaction.createdAt.toDate().getTime();
            if (timestamp >= previousStart.getTimeInMillis() && timestamp < weekStart.getTimeInMillis()) {
                previousWeek += transaction.amountMinor;
                hasPreviousWeekActivity = true;
            } else if (timestamp >= weekStart.getTimeInMillis() && timestamp < nextWeekStart.getTimeInMillis()) {
                Calendar transactionDay = Calendar.getInstance();
                transactionDay.setTimeInMillis(timestamp);
                int index = (transactionDay.get(Calendar.DAY_OF_WEEK) + 5) % 7;
                daily[index] += transaction.amountMinor;
                thisWeek += transaction.amountMinor;
            }
        }
        TextView weekTotal = walletText(ReadSummaryFormatter.formatCurrency(thisWeek, "VND"), 19, R.color.ink, true);
        activityCard.addView(weekTotal);
        if (hasPreviousWeekActivity && previousWeek > 0) {
            double change = ((double) thisWeek - previousWeek) * 100d / previousWeek;
            TextView comparison = walletText(String.format(Locale.getDefault(), "%+.0f%% so với tuần trước", change),
                    12, change >= 0 ? R.color.navy : R.color.ink_muted, false);
            comparison.setPadding(0, dp(3), 0, dp(8));
            activityCard.addView(comparison);
        }
        float[] chartValues = new float[7];
        long maxDaily = 0;
        for (long value : daily) maxDaily = Math.max(maxDaily, value);
        if (maxDaily > 0) for (int i = 0; i < daily.length; i++) chartValues[i] = (float) daily[i] / maxDaily;
        int selectedDay = Math.max(0, Math.min(6, (today.get(Calendar.DAY_OF_WEEK) + 5) % 7));
        SignatureBarChartView chart = new SignatureBarChartView(requireContext());
        chart.setValues(chartValues, selectedDay);
        LinearLayout.LayoutParams chartParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120));
        chartParams.topMargin = dp(8);
        activityCard.addView(chart, chartParams);
        binding.content.addView(activityCard);

        addWalletSectionTitle("Hóa đơn sắp đến hạn");
        if (walletUpcomingBills.isEmpty()) {
            TextView emptyBills = walletText(walletBillsLoading ? "Đang tải hóa đơn…" : "Không có hóa đơn sắp đến hạn.",
                    13, R.color.ink_muted, false);
            emptyBills.setPadding(dp(16), dp(8), dp(16), dp(16));
            binding.content.addView(emptyBills);
        } else {
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (JSONObject bill : walletUpcomingBills) row.addView(createUpcomingBillCard(bill));
            HorizontalScrollView horizontal = new HorizontalScrollView(requireContext());
            horizontal.setFillViewport(false);
            horizontal.setHorizontalScrollBarEnabled(false);
            horizontal.addView(row);
            binding.content.addView(horizontal);
        }
        MaterialButton bills = new MaterialButton(requireContext());
        bills.setText("Xem hóa đơn và tiện ích");
        bills.setOnClickListener(v -> androidx.navigation.Navigation.findNavController(v).navigate(R.id.utilitiesHub));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonParams.topMargin = dp(10);
        binding.content.addView(bills, buttonParams);
    }

    private void loadWalletBills() {
        walletBillsLoading = true;
        var current = binding;
        String requestedUid = uid;
        application.getAppContainer().getBackendApiClient().get("/bills").addOnCompleteListener(task -> {
            if (binding != current || !java.util.Objects.equals(uid, requestedUid)) return;
            walletBillsLoading = false;
            walletBillsLoaded = true;
            walletUpcomingBills.clear();
            try {
                if (!task.isSuccessful() || task.getResult().statusCode != 200) throw new org.json.JSONException("Unavailable");
                JSONArray items = new JSONObject(task.getResult().body).optJSONArray("items");
                long now = System.currentTimeMillis();
                if (items != null) for (int i = 0; i < items.length(); i++) {
                    JSONObject bill = items.getJSONObject(i);
                    long dueAt = bill.optLong("dueAt", 0L);
                    if ("UNPAID".equalsIgnoreCase(bill.optString("status")) && dueAt > now
                            && (bill.isNull("quoteExpiresAt") || bill.optLong("quoteExpiresAt") > now)) walletUpcomingBills.add(bill);
                }
                walletUpcomingBills.sort((a, b) -> Long.compare(a.optLong("dueAt"), b.optLong("dueAt")));
            } catch (Exception ignored) { walletUpcomingBills.clear(); }
            renderAll();
        });
    }

    private LinearLayout createUpcomingBillCard(JSONObject bill) {
        LinearLayout card = walletCard();
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(dp(230), ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.rightMargin = dp(10);
        card.setLayoutParams(cardParams);
        TextView provider = walletText(bill.optString("provider", "Hóa đơn"), 14, R.color.ink, true);
        card.addView(provider);
        String due = DateFormat.getDateInstance(DateFormat.MEDIUM, new Locale("vi", "VN"))
                .format(new Date(bill.optLong("dueAt")));
        TextView dueText = walletText("Đến hạn " + due, 12, R.color.ink_muted, false);
        dueText.setPadding(0, dp(5), 0, dp(8)); card.addView(dueText);
        card.addView(walletText(ReadSummaryFormatter.formatCurrency(bill.optLong("amountMinor"), bill.optString("currency", "VND")),
                16, R.color.ink, true));
        card.setClickable(true); card.setFocusable(true);
        card.setOnClickListener(v -> {
            String serviceType = bill.optString("serviceType", "ELECTRICITY");
            Bundle args = new Bundle();
            args.putString("billId", bill.optString("id"));
            args.putString("serviceType", serviceType);
            int destination = "WATER".equalsIgnoreCase(serviceType) ? R.id.waterBill
                    : "ELECTRICITY".equalsIgnoreCase(serviceType) ? R.id.electricityBill : R.id.otherUtilities;
            androidx.navigation.Navigation.findNavController(v).navigate(destination, args);
        });
        return card;
    }

    private void addWalletSectionTitle(String title) {
        TextView view = walletText(title, 16, R.color.ink, true);
        view.setPadding(dp(2), dp(14), dp(2), dp(9)); binding.content.addView(view);
    }

    private LinearLayout walletCard() {
        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.VERTICAL); card.setBackgroundResource(R.drawable.glass_panel_card);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(8); card.setLayoutParams(params); return card;
    }

    private TextView walletText(String value, float size, int color, boolean bold) {
        TextView text = new TextView(requireContext()); text.setText(value); text.setTextSize(size);
        text.setTextColor(ContextCompat.getColor(requireContext(), color));
        if (bold) text.setTypeface(null, android.graphics.Typeface.BOLD); return text;
    }

    private boolean isCustomerDirectory() {
        return role == UserRole.OFFICER && activeNavItem == R.id.nav_wallet;
    }

    private void loadDirectory(String cursor) {
        if (directoryLoading || binding == null) return;
        var current = binding;
        directoryLoading = true;
        directoryError = false;
        directoryStatus = "Đang tải hồ sơ khách hàng…";
        if (cursor == null) {
            customers.clear();
            customerCursor = null;
        }
        renderAll();
        String path = "/officer/customers?limit=25"
                + (cursor == null ? "" : "&cursor=" + android.net.Uri.encode(cursor));
        application.getAppContainer().getBackendApiClient().get(path).addOnCompleteListener(task -> {
            if (binding != current) return;
            directoryLoading = false;
            try {
                if (!task.isSuccessful() || task.getResult().statusCode != 200)
                    throw new org.json.JSONException("Unavailable");
                JSONObject page = new JSONObject(task.getResult().body);
                JSONArray items = page.getJSONArray("items");
                for (int i = 0; i < items.length(); i++) customers.add(items.getJSONObject(i));
                customerCursor = page.isNull("nextCursor") ? null : page.getString("nextCursor");
                directoryStatus = customers.isEmpty() ? "Chưa có hồ sơ khách hàng."
                        : "Hồ sơ đã tải: " + customers.size();
            } catch (org.json.JSONException error) {
                directoryError = true;
                directoryStatus = task.isSuccessful() && task.getResult().statusCode == 403
                        ? "Bạn chưa có quyền xem hồ sơ khách hàng." : "Không tải được hồ sơ khách hàng.";
            }
            renderAll();
        });
    }

    private void renderOfficerCustomers() {
        MaterialButton addCustomerBtn = new MaterialButton(requireContext());
        addCustomerBtn.setText("+ Cấp mới hồ sơ khách hàng");
        addCustomerBtn.setTextColor(ContextCompat.getColor(requireContext(), R.color.white));
        addCustomerBtn.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.navy));
        addCustomerBtn.setCornerRadius(dp(18));
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        btnParams.bottomMargin = dp(18);
        addCustomerBtn.setLayoutParams(btnParams);
        addCustomerBtn.setOnClickListener(v -> openOfficerCustomerForm(v, "create", ""));
        binding.content.addView(addCustomerBtn);

        for (JSONObject customer : customers) {
            String id = customer.optString("id");
            String uid = customer.isNull("uidRef") ? customer.optString("uid", id) : customer.optString("uidRef");
            String name = customer.optString("fullName", "").trim();
            MaterialButton row = new MaterialButton(requireContext());
            row.setText((name.isEmpty() ? customer.optString("customerNo", id) : name)
                    + " · " + customer.optString("kycStatus", "Chưa có eKYC"));
            row.setEnabled(!uid.isEmpty());
            row.setOnClickListener(v -> openOfficerCustomerForm(v, "edit", uid));
            binding.content.addView(row);
        }
        if (customerCursor != null) {
            MaterialButton next = new MaterialButton(requireContext());
            next.setText("Tải thêm khách hàng");
            next.setEnabled(!directoryLoading);
            next.setOnClickListener(v -> loadDirectory(customerCursor));
            binding.content.addView(next);
        }
    }

    private void openOfficerCustomerForm(View anchor, String mode, String customerId) {
        Bundle args = new Bundle();
        args.putString("mode", mode);
        args.putString("customerId", customerId);
        Navigation.findNavController(anchor).navigate(R.id.officerCustomerForm, args);
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
                        "Mã tài khoản: " + account.id);
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
                                    String message = "Không thể tạo hồ sơ. Vui lòng kiểm tra thông tin rồi thử lại.";
                                    if ("CUSTOMER_NUMBER_EXISTS".equals(error)) message = "Mã khách hàng đã tồn tại.";
                                    else if ("CONTACT_PHONE_EXISTS".equals(error)) message = "Số điện thoại liên hệ đã được sử dụng.";
                                    else if ("PROVISION_IDENTITY_FAILED".equals(error)) message = "Email hoặc số điện thoại đã được đăng ký. Kiểm tra thông tin rồi thử lại.";
                                    else if ("IDEMPOTENCY_CONFLICT".equals(error)) message = "Yêu cầu tạo hồ sơ không khớp lần gửi trước; hãy kiểm tra dữ liệu đã nhập.";
                                    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
                                }
                            })
                            .addOnFailureListener(err -> Toast.makeText(requireContext(), "Chưa thể tạo hồ sơ. Vui lòng thử lại.", Toast.LENGTH_LONG).show());
                } catch (Exception e) {
                    Toast.makeText(requireContext(), "Không thể tạo hồ sơ. Vui lòng kiểm tra thông tin rồi thử lại.", Toast.LENGTH_SHORT).show();
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
                .setMessage("Thao tác này sẽ được lưu vào lịch sử thay đổi của hệ thống ngân hàng.")
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
                                    Toast.makeText(requireContext(), "Không thể cập nhật hồ sơ. Vui lòng thử lại sau.", Toast.LENGTH_LONG).show();
                                }
                            })
                            .addOnFailureListener(err -> Toast.makeText(requireContext(), "Chưa thể cập nhật hồ sơ. Vui lòng thử lại.", Toast.LENGTH_LONG).show());
                } catch (Exception e) {
                    Toast.makeText(requireContext(), "Không thể cập nhật hồ sơ. Vui lòng thử lại.", Toast.LENGTH_SHORT).show();
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
        balance.setText(ReadSummaryFormatter.formatCurrency(acc.availableMinor != null ? acc.availableMinor : acc.balanceMinor, acc.currency));
        balance.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink));
        balance.setTextSize(20);
        balance.setPadding(0, dp(4), 0, dp(4));
        tile.addView(balance);

        TextView sub = new TextView(requireContext());
        sub.setText(acc.id + " · " + acc.status);
        sub.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink_muted));
        sub.setTextSize(12);
        tile.addView(sub);

        sub.setText("Sổ cái Nimbus · " + acc.id);
        tile.setOnClickListener(v -> openDetails(v, acc));
        return tile;
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

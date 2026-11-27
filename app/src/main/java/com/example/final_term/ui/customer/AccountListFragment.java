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
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.example.final_term.BuildConfig;
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
import com.example.final_term.ui.common.ThemedDialogBuilder;
import com.example.final_term.ui.ekyc.FaceScanDialogFragment;
import com.stripe.android.PaymentConfiguration;
import com.stripe.android.paymentsheet.PaymentSheet;
import com.stripe.android.paymentsheet.PaymentSheetResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;
import java.text.NumberFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private int selectedCardSlot = 0;
    private boolean balanceVisible = false;
    private boolean accountNumberVisible = false;
    private boolean stripeCardVisible = false;
    private boolean accountNumberFaceBusy = false;
    private enum FaceRevealTarget { NONE, NAPAS_ACCOUNT, STRIPE_CARD }
    private FaceRevealTarget pendingFaceReveal = FaceRevealTarget.NONE;
    private static final ExecutorService FACE_IO = Executors.newSingleThreadExecutor();
    private PaymentSheet stripeSetupSheet;
    private boolean stripeCardsLoading;
    private boolean stripeCardsLoaded;
    private boolean stripeCardsFailed;
    private boolean stripeConfigured = true;
    private boolean stripeSetupInFlight;
    private final List<JSONObject> stripeCards = new ArrayList<>();
    private final List<JSONObject> loanApplications = new ArrayList<>();
    private boolean loanApplicationsLoaded;
    private boolean loanApplicationsLoading;
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
        if (BuildConfig.STRIPE_PUBLISHABLE_KEY.startsWith("pk_test_")) {
            PaymentConfiguration.init(requireContext(), BuildConfig.STRIPE_PUBLISHABLE_KEY);
            stripeSetupSheet = new PaymentSheet.Builder(this::onStripeSetupResult).build(this);
        }
        getParentFragmentManager().setFragmentResultListener(FaceScanDialogFragment.RESULT_KEY,
                getViewLifecycleOwner(), (requestKey, result) -> {
                    String path = result.getString(FaceScanDialogFragment.RESULT_PATH);
                    String error = result.getString(FaceScanDialogFragment.RESULT_ERROR);
                    if (path != null) {
                        accountNumberFaceBusy = true;
                        FACE_IO.execute(() -> {
                            File file = new File(path);
                            try {
                                byte[] bytes = Files.readAllBytes(file.toPath());
                                if (getActivity() != null) {
                                    getActivity().runOnUiThread(() -> verifyFaceForAccountNumber(bytes));
                                }
                            } catch (Exception e) {
                                if (getActivity() != null) {
                                    getActivity().runOnUiThread(() -> {
                                        accountNumberFaceBusy = false;
                                        if (binding != null) {
                                            Toast.makeText(requireContext(), "Không đọc được ảnh khuôn mặt.", Toast.LENGTH_SHORT).show();
                                        }
                                    });
                                }
                            } finally {
                                file.delete();
                            }
                        });
                    } else if (error != null && !error.trim().isEmpty()) {
                        Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show();
                    }
                });

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
        if (previousEntry != null && previousEntry.getDestination().getId() == R.id.customerHome
                && (args == null || !args.containsKey("activeNavItem"))) {
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
        binding.notificationButton.setOnClickListener(v ->
                new ChatbotDialogFragment().show(getChildFragmentManager(), "Chatbot"));

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
                            selectedCardSlot = 0;
                            accountNumberVisible = false;
                            stripeCardVisible = false;
                            accountNumberFaceBusy = false;
                            pendingFaceReveal = FaceRevealTarget.NONE;
                            stripeCards.clear();
                            stripeCardsLoading = false;
                            stripeCardsLoaded = false;
                            stripeCardsFailed = false;
                            stripeSetupInFlight = false;
                            loanApplications.clear();
                            loanApplicationsLoaded = false;
                            loanApplicationsLoading = false;
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
            binding.titleText.setText(activeNavItem == R.id.nav_wallet ? "Ví" : "Thẻ");
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
        boolean hideStatus = accountStatus == ReadModelResult.Status.SUCCESS;
        binding.statusText.setVisibility(hideStatus ? View.GONE : View.VISIBLE);
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
            if (!stripeCardsLoaded && !stripeCardsLoading) {
                loadStripeCards(false);
            }
            int totalSlots = cardAccounts.size() + 1;
            if (selectedCardSlot < 0 || selectedCardSlot >= totalSlots) {
                selectedCardSlot = 0;
            }
            ReadModels.AccountSummary primaryChecking = cardAccounts.get(0);
            selectedCardAccountId = selectedCardSlot < cardAccounts.size()
                    ? cardAccounts.get(selectedCardSlot).id : primaryChecking.id;

            ViewPager2 pager = new ViewPager2(requireContext());
            pager.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(380)));
            pager.setOffscreenPageLimit(2);
            pager.setClipToPadding(false);
            pager.setClipChildren(false);
            if (pager.getChildAt(0) instanceof RecyclerView) {
                RecyclerView rv = (RecyclerView) pager.getChildAt(0);
                rv.setClipToPadding(false);
                rv.setClipChildren(false);
                rv.setOverScrollMode(View.OVER_SCROLL_NEVER);
            }
            pager.setPageTransformer((page, position) -> {
                float absPos = Math.min(1f, Math.abs(position));
                int width = page.getWidth();
                if (width > 0) {
                    page.setTranslationX(-position * width * 0.85f);
                }
                float scale = 1f - 0.10f * absPos;
                page.setScaleX(scale);
                page.setScaleY(scale);
                page.setAlpha(1f - 0.42f * absPos);
                page.setRotation(position * 7f);
                page.setTranslationZ((1f - absPos) * dp(24));
            });
            pager.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                @NonNull
                @Override
                public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                    FrameLayout slot = new FrameLayout(parent.getContext());
                    slot.setLayoutParams(new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    slot.setClipChildren(false);
                    return new RecyclerView.ViewHolder(slot) { };
                }

                @Override
                public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                    FrameLayout slot = (FrameLayout) holder.itemView;
                    slot.removeAllViews();
                    View card = position >= cardAccounts.size()
                            ? (stripeCards.isEmpty() ? createStripeUnlinkedCard() : createStripeVisaCard(stripeCards.get(0)))
                            : createNapasCard(cardAccounts.get(position));
                    FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(dp(244), dp(356), Gravity.CENTER);
                    slot.addView(card, cardParams);
                }

                @Override
                public int getItemCount() {
                    return totalSlots;
                }
            });
            pager.setCurrentItem(selectedCardSlot, false);
            binding.content.addView(pager);

            LinearLayout dotsContainer = new LinearLayout(requireContext());
            dotsContainer.setOrientation(LinearLayout.VERTICAL);
            if (totalSlots > 1) {
                dotsContainer.addView(createCardSlotPagination(totalSlots, selectedCardSlot, pager));
            }
            binding.content.addView(dotsContainer);

            LinearLayout cardDetailsContainer = new LinearLayout(requireContext());
            cardDetailsContainer.setOrientation(LinearLayout.VERTICAL);
            binding.content.addView(cardDetailsContainer);

            Runnable updateSlotDetails = () -> {
                if (binding == null) return;
                boolean isStripeSlot = selectedCardSlot >= cardAccounts.size();
                ReadModels.AccountSummary activeChecking = isStripeSlot
                        ? primaryChecking : cardAccounts.get(selectedCardSlot);
                selectedCardAccountId = activeChecking.id;

                dotsContainer.removeAllViews();
                if (totalSlots > 1) {
                    dotsContainer.addView(createCardSlotPagination(totalSlots, selectedCardSlot, pager));
                }

                cardDetailsContainer.removeAllViews();
                if (!isStripeSlot) {
                    cardDetailsContainer.addView(createCardActions(activeChecking));
                    cardDetailsContainer.addView(createNapasInfoPanel(activeChecking));

                    MaterialButton details = new MaterialButton(requireContext());
                    details.setText("Chi tiết tài khoản  ›");
                    details.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink));
                    details.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.glass));
                    details.setCornerRadius(dp(26));
                    details.setOnClickListener(v -> openDetails(v, activeChecking));
                    LinearLayout.LayoutParams detailsParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
                    detailsParams.setMargins(dp(4), dp(8), dp(4), dp(18));
                    cardDetailsContainer.addView(details, detailsParams);
                } else if (!stripeCards.isEmpty()) {
                    JSONObject primaryStripe = stripeCards.get(0);
                    cardDetailsContainer.addView(createStripeCardActions(primaryChecking, primaryStripe));
                    cardDetailsContainer.addView(createStripeInfoPanel(primaryStripe));
                } else {
                    cardDetailsContainer.addView(createStripeUnlinkedInfoPanel());
                }
            };

            updateSlotDetails.run();
            pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
                @Override
                public void onPageSelected(int position) {
                    if (position != selectedCardSlot) {
                        selectedCardSlot = position;
                        updateSlotDetails.run();
                    }
                }
            });
        }

        if (!cardAccounts.isEmpty() || !otherAccounts.isEmpty()) {
            if (!loanApplicationsLoaded && !loanApplicationsLoading) {
                loadLoanApplications(false);
            }
            TextView title = walletText("Tiết kiệm & Khoản vay", 16, R.color.ink, true);
            title.setPadding(dp(2), dp(8), dp(2), dp(10));
            binding.content.addView(title);
            for (ReadModels.AccountSummary account : otherAccounts) binding.content.addView(createOtherAccountRow(account));
            for (JSONObject loanApp : loanApplications) {
                if ("PENDING".equalsIgnoreCase(loanApp.optString("status"))) {
                    binding.content.addView(createPendingLoanRow(loanApp));
                }
            }
            if (!cardAccounts.isEmpty()) {
                binding.content.addView(createProductActionsRow(cardAccounts.get(0)));
            }
        }
    }

    private View createProductActionsRow(ReadModels.AccountSummary checking) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(4);
        rowLp.bottomMargin = dp(12);
        row.setLayoutParams(rowLp);

        LinearLayout savingsCard = createProductActionCard(
                R.drawable.ic_trending_up, "Mở tiết kiệm", "Từ 1 triệu · Sinh lời",
                () -> showOpenSavingsDialog(checking));
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        leftLp.setMarginEnd(dp(6));
        row.addView(savingsCard, leftLp);

        LinearLayout loanCard = createProductActionCard(
                R.drawable.ic_account_balance, "Đăng ký vay", "Duyệt & giải ngân",
                this::showApplyLoanDialog);
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rightLp.setMarginStart(dp(6));
        row.addView(loanCard, rightLp);

        return row;
    }

    private LinearLayout createProductActionCard(int iconRes, String heading, String sub, Runnable onClick) {
        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.glass_panel_card);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> onClick.run());

        ImageView icon = new ImageView(requireContext());
        icon.setImageResource(iconRes);
        icon.setImageTintList(ContextCompat.getColorStateList(requireContext(), R.color.navy));
        icon.setBackgroundResource(R.drawable.circle_action_button);
        icon.setPadding(dp(10), dp(10), dp(10), dp(10));
        card.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));

        TextView titleTv = walletText(heading, 14, R.color.ink, true);
        titleTv.setPadding(0, dp(10), 0, dp(2));
        card.addView(titleTv);

        TextView subTv = walletText(sub, 11, R.color.ink_muted, false);
        card.addView(subTv);
        return card;
    }

    private View createPendingLoanRow(JSONObject loanApp) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        row.setBackgroundResource(R.drawable.glass_panel_card);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(10);
        row.setLayoutParams(params);
        LinearLayout text = new LinearLayout(requireContext());
        text.setOrientation(LinearLayout.VERTICAL);
        long amount = loanApp.optLong("amountMinor", 0L);
        int months = loanApp.optInt("termMonths", 12);
        text.addView(walletText("Hồ sơ vay · " + ReadSummaryFormatter.formatCurrency(amount, "VND"), 14, R.color.ink, true));
        TextView state = walletText("Kỳ hạn " + months + " tháng · Đang chờ Officer duyệt", 12, R.color.warning, false);
        state.setPadding(0, dp(4), 0, 0);
        text.addView(state);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private void loadLoanApplications(boolean force) {
        if (loanApplicationsLoading || (loanApplicationsLoaded && !force)) return;
        loanApplicationsLoading = true;
        var current = binding;
        String requestedUid = uid;
        application.getAppContainer().getBackendApiClient().get("/loans/applications").addOnCompleteListener(task -> {
            if (binding != current || !java.util.Objects.equals(uid, requestedUid)) return;
            loanApplicationsLoading = false;
            loanApplicationsLoaded = true;
            loanApplications.clear();
            try {
                if (task.isSuccessful() && task.getResult() != null && task.getResult().statusCode == 200) {
                    JSONArray items = new JSONObject(task.getResult().body).optJSONArray("items");
                    if (items != null) {
                        for (int i = 0; i < items.length(); i++) loanApplications.add(items.getJSONObject(i));
                    }
                }
            } catch (Exception ignored) { }
            renderAll();
        });
    }

    private com.google.android.material.textfield.TextInputLayout createDialogInputLayout(String hint) {
        com.google.android.material.textfield.TextInputLayout layout =
                new com.google.android.material.textfield.TextInputLayout(
                        requireContext(), null, com.google.android.material.R.attr.textInputOutlinedStyle);
        layout.setHint(hint);
        layout.setBoxBackgroundMode(com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE);
        layout.setBoxBackgroundColor(ContextCompat.getColor(requireContext(), R.color.glass_card));
        layout.setBoxCornerRadii(dp(16), dp(16), dp(16), dp(16));
        layout.setBoxStrokeColor(ContextCompat.getColor(requireContext(), R.color.navy));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        layout.setLayoutParams(lp);
        return layout;
    }

    private LinearLayout createPillRow(String[] labels, int selectedIndex, java.util.function.IntConsumer onSelect) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        row.setLayoutParams(lp);
        final int[] active = {selectedIndex};
        TextView[] pills = new TextView[labels.length];
        Runnable refresh = () -> {
            for (int i = 0; i < pills.length; i++) {
                boolean sel = i == active[0];
                pills[i].setBackgroundResource(sel ? R.drawable.navy_button_pill : R.drawable.glass_panel_soft);
                pills[i].setTextColor(ContextCompat.getColor(requireContext(), sel ? R.color.white : R.color.ink));
                pills[i].setTypeface(null, sel ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            }
        };
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView pill = new TextView(requireContext());
            pill.setText(labels[i]);
            pill.setTextSize(12);
            pill.setGravity(Gravity.CENTER);
            pill.setPadding(dp(8), dp(10), dp(8), dp(10));
            pill.setClickable(true);
            pill.setFocusable(true);
            pill.setOnClickListener(v -> {
                active[0] = idx;
                refresh.run();
                onSelect.accept(idx);
            });
            LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i < labels.length - 1) pLp.setMarginEnd(dp(6));
            pills[i] = pill;
            row.addView(pill, pLp);
        }
        refresh.run();
        return row;
    }

    private void showOpenSavingsDialog(ReadModels.AccountSummary checking) {
        application.getAppContainer().getBackendApiClient().get("/products/rates").addOnCompleteListener(rateTask -> {
            if (binding == null) return;
            int bps = 480;
            try {
                if (rateTask.isSuccessful() && rateTask.getResult() != null && rateTask.getResult().statusCode == 200) {
                    bps = new JSONObject(rateTask.getResult().body).optInt("savingsRateBasisPoints", 480);
                }
            } catch (Exception ignored) { }
            final int rateBps = bps;
            long avail = checking.availableMinor != null ? checking.availableMinor : checking.balanceMinor;

            android.widget.ScrollView scroll = new android.widget.ScrollView(requireContext());
            LinearLayout box = new LinearLayout(requireContext());
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(18), dp(6), dp(18), dp(6));

            LinearLayout banner = new LinearLayout(requireContext());
            banner.setOrientation(LinearLayout.VERTICAL);
            banner.setBackgroundResource(R.drawable.glass_panel_soft);
            banner.setPadding(dp(14), dp(12), dp(14), dp(12));
            banner.addView(walletText(String.format(Locale.US, "Lãi suất niêm yết: %.2f%%/năm", rateBps / 100.0), 13, R.color.navy, true));
            TextView balTv = walletText("Số dư thanh toán: " + ReadSummaryFormatter.formatCurrency(avail, checking.currency), 12, R.color.ink_muted, false);
            balTv.setPadding(0, dp(3), 0, 0);
            banner.addView(balTv);
            box.addView(banner);

            com.google.android.material.textfield.TextInputLayout amountLayout = createDialogInputLayout("Số tiền gửi (tối thiểu 1.000.000 ₫)");
            com.google.android.material.textfield.TextInputEditText amountInput =
                    new com.google.android.material.textfield.TextInputEditText(amountLayout.getContext());
            amountInput.setInputType(InputType.TYPE_CLASS_NUMBER);
            amountInput.setText("5000000");
            amountInput.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink));
            amountLayout.addView(amountInput);
            box.addView(amountLayout);

            long[] quickAmounts = {1_000_000L, 5_000_000L, 10_000_000L, 50_000_000L};
            String[] quickLabels = {"1 triệu", "5 triệu", "10 triệu", "50 triệu"};
            box.addView(createPillRow(quickLabels, 1, idx -> amountInput.setText(String.valueOf(quickAmounts[idx]))));

            TextView termLabel = walletText("Kỳ hạn gửi", 12, R.color.ink_muted, true);
            termLabel.setPadding(0, dp(12), 0, 0);
            box.addView(termLabel);

            int[] termValues = {3, 6, 12, 24};
            String[] termLabels = {"3 tháng", "6 tháng", "12 tháng", "24 tháng"};
            final int[] selectedTermIdx = {2};

            LinearLayout estimateCard = new LinearLayout(requireContext());
            estimateCard.setOrientation(LinearLayout.VERTICAL);
            estimateCard.setBackgroundResource(R.drawable.glass_panel_soft);
            estimateCard.setPadding(dp(14), dp(10), dp(14), dp(10));
            LinearLayout.LayoutParams estLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            estLp.topMargin = dp(12);
            estimateCard.setLayoutParams(estLp);
            TextView estLabel = walletText("Lãi dự kiến cuối kỳ", 11, R.color.ink_muted, false);
            TextView estValue = walletText("—", 15, R.color.success, true);
            estimateCard.addView(estLabel);
            estimateCard.addView(estValue);

            Runnable updateEstimate = () -> {
                long amt = 0L;
                try {
                    amt = Long.parseLong(String.valueOf(amountInput.getText()).trim().replaceAll("[^0-9]", ""));
                } catch (Exception ignored) { }
                int months = termValues[selectedTermIdx[0]];
                long profit = Math.round(amt * (rateBps / 10000.0) * (months / 12.0));
                estValue.setText("+" + ReadSummaryFormatter.formatCurrency(profit, "VND")
                        + " (" + months + " tháng)");
            };

            box.addView(createPillRow(termLabels, 2, idx -> {
                selectedTermIdx[0] = idx;
                updateEstimate.run();
            }));
            box.addView(estimateCard);
            amountInput.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updateEstimate.run(); }
                @Override public void afterTextChanged(android.text.Editable s) { }
            });
            updateEstimate.run();
            scroll.addView(box);

            new ThemedDialogBuilder(requireContext())
                    .setTitle("Mở sổ tiết kiệm")
                    .setView(scroll)
                    .setNegativeButton("Hủy", null)
                    .setPositiveButton("Mở sổ ngay", (d, w) -> {
                        long amount;
                        try {
                            amount = Long.parseLong(String.valueOf(amountInput.getText()).trim().replaceAll("[^0-9]", ""));
                        } catch (Exception e) {
                            Toast.makeText(requireContext(), "Số tiền không hợp lệ.", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (amount < 1_000_000L) {
                            Toast.makeText(requireContext(), "Số tiền tối thiểu là 1.000.000 ₫.", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        int termMonths = termValues[selectedTermIdx[0]];
                        try {
                            JSONObject body = new JSONObject()
                                    .put("sourceAccountId", checking.id)
                                    .put("amountMinor", amount)
                                    .put("termMonths", termMonths)
                                    .put("requestId", UUID.randomUUID().toString());
                            application.getAppContainer().getBackendApiClient().post("/savings", body.toString())
                                    .addOnCompleteListener(res -> {
                                        if (binding == null) return;
                                        if (res.isSuccessful() && res.getResult() != null
                                                && res.getResult().statusCode >= 200 && res.getResult().statusCode < 300) {
                                            Toast.makeText(requireContext(), "Đã mở sổ tiết kiệm thành công.", Toast.LENGTH_SHORT).show();
                                            load();
                                        } else {
                                            String err = "Không thể mở sổ tiết kiệm.";
                                            try {
                                                if (res.isSuccessful() && res.getResult() != null
                                                        && "INSUFFICIENT_FUNDS".equals(new JSONObject(res.getResult().body).optString("error"))) {
                                                    err = "Số dư tài khoản thanh toán không đủ.";
                                                }
                                            } catch (Exception ignored) { }
                                            Toast.makeText(requireContext(), err, Toast.LENGTH_SHORT).show();
                                        }
                                    });
                        } catch (Exception ignored) { }
                    })
                    .show();
        });
    }

    private void showApplyLoanDialog() {
        application.getAppContainer().getBackendApiClient().get("/products/rates").addOnCompleteListener(rateTask -> {
            if (binding == null) return;
            int bps = 850;
            try {
                if (rateTask.isSuccessful() && rateTask.getResult() != null && rateTask.getResult().statusCode == 200) {
                    bps = new JSONObject(rateTask.getResult().body).optInt("loanRateBasisPoints", 850);
                }
            } catch (Exception ignored) { }
            final int rateBps = bps;

            android.widget.ScrollView scroll = new android.widget.ScrollView(requireContext());
            LinearLayout box = new LinearLayout(requireContext());
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(18), dp(6), dp(18), dp(6));

            LinearLayout banner = new LinearLayout(requireContext());
            banner.setOrientation(LinearLayout.VERTICAL);
            banner.setBackgroundResource(R.drawable.glass_panel_soft);
            banner.setPadding(dp(14), dp(12), dp(14), dp(12));
            banner.addView(walletText(String.format(Locale.US, "Lãi suất vay: %.2f%%/năm", rateBps / 100.0), 13, R.color.navy, true));
            TextView sub = walletText("Hồ sơ sẽ được Officer thẩm định và giải ngân vào tài khoản thanh toán.", 12, R.color.ink_muted, false);
            sub.setPadding(0, dp(3), 0, 0);
            banner.addView(sub);
            box.addView(banner);

            com.google.android.material.textfield.TextInputLayout amountLayout = createDialogInputLayout("Số tiền vay (tối thiểu 10.000.000 ₫)");
            com.google.android.material.textfield.TextInputEditText amountInput =
                    new com.google.android.material.textfield.TextInputEditText(amountLayout.getContext());
            amountInput.setInputType(InputType.TYPE_CLASS_NUMBER);
            amountInput.setText("50000000");
            amountInput.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink));
            amountLayout.addView(amountInput);
            box.addView(amountLayout);

            long[] quickLoans = {20_000_000L, 50_000_000L, 100_000_000L, 200_000_000L};
            String[] quickLabels = {"20 triệu", "50 triệu", "100 triệu", "200 triệu"};
            box.addView(createPillRow(quickLabels, 1, idx -> amountInput.setText(String.valueOf(quickLoans[idx]))));

            TextView termLabel = walletText("Kỳ hạn vay", 12, R.color.ink_muted, true);
            termLabel.setPadding(0, dp(12), 0, 0);
            box.addView(termLabel);

            int[] termValues = {12, 24, 36, 60};
            String[] termLabels = {"12 tháng", "24 tháng", "36 tháng", "60 tháng"};
            final int[] selectedTermIdx = {1};

            TextView freqLabel = walletText("Chu kỳ trả góp", 12, R.color.ink_muted, true);
            freqLabel.setPadding(0, dp(12), 0, 0);
            String[] freqLabels = {"Hàng tháng", "2 tuần/lần"};
            String[] freqValues = {"MONTHLY", "BIWEEKLY"};
            final int[] selectedFreqIdx = {0};

            LinearLayout estCard = new LinearLayout(requireContext());
            estCard.setOrientation(LinearLayout.VERTICAL);
            estCard.setBackgroundResource(R.drawable.glass_panel_soft);
            estCard.setPadding(dp(14), dp(10), dp(14), dp(10));
            LinearLayout.LayoutParams estLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            estLp.topMargin = dp(12);
            estCard.setLayoutParams(estLp);
            TextView estLabel = walletText("Trả góp dự kiến mỗi kỳ", 11, R.color.ink_muted, false);
            TextView estValue = walletText("—", 15, R.color.navy, true);
            estCard.addView(estLabel);
            estCard.addView(estValue);

            Runnable updateLoanEstimate = () -> {
                long amt = 0L;
                try {
                    amt = Long.parseLong(String.valueOf(amountInput.getText()).trim().replaceAll("[^0-9]", ""));
                } catch (Exception ignored) { }
                int months = termValues[selectedTermIdx[0]];
                boolean biweekly = selectedFreqIdx[0] == 1;
                int periods = biweekly ? Math.max(1, Math.round(months * 26f / 12f)) : months;
                double periodicRate = (rateBps / 10000.0) / (biweekly ? 26.0 : 12.0);
                long perPeriod = amt <= 0 ? 0L : Math.round(
                        periodicRate == 0 ? (double) amt / periods
                                : amt * (periodicRate * Math.pow(1 + periodicRate, periods)) / (Math.pow(1 + periodicRate, periods) - 1));
                estValue.setText(ReadSummaryFormatter.formatCurrency(perPeriod, "VND")
                        + (biweekly ? " / 2 tuần" : " / tháng"));
            };

            box.addView(createPillRow(termLabels, 1, idx -> {
                selectedTermIdx[0] = idx;
                updateLoanEstimate.run();
            }));
            box.addView(freqLabel);
            box.addView(createPillRow(freqLabels, 0, idx -> {
                selectedFreqIdx[0] = idx;
                updateLoanEstimate.run();
            }));

            com.google.android.material.textfield.TextInputLayout purposeLayout = createDialogInputLayout("Mục đích vay");
            com.google.android.material.textfield.TextInputEditText purposeInput =
                    new com.google.android.material.textfield.TextInputEditText(purposeLayout.getContext());
            purposeInput.setText("Vay tiêu dùng / Mua nhà");
            purposeInput.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink));
            purposeLayout.addView(purposeInput);
            box.addView(purposeLayout);
            box.addView(estCard);

            amountInput.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updateLoanEstimate.run(); }
                @Override public void afterTextChanged(android.text.Editable s) { }
            });
            updateLoanEstimate.run();
            scroll.addView(box);

            new ThemedDialogBuilder(requireContext())
                    .setTitle("Đăng ký khoản vay")
                    .setView(scroll)
                    .setNegativeButton("Hủy", null)
                    .setPositiveButton("Gửi hồ sơ vay", (d, w) -> {
                        long amount;
                        try {
                            amount = Long.parseLong(String.valueOf(amountInput.getText()).trim().replaceAll("[^0-9]", ""));
                        } catch (Exception e) {
                            Toast.makeText(requireContext(), "Số tiền vay không hợp lệ.", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (amount < 10_000_000L) {
                            Toast.makeText(requireContext(), "Số tiền vay tối thiểu là 10.000.000 ₫.", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        String purpose = String.valueOf(purposeInput.getText()).trim();
                        if (purpose.length() < 2) purpose = "Vay tiêu dùng";
                        try {
                            JSONObject body = new JSONObject()
                                    .put("amountMinor", amount)
                                    .put("termMonths", termValues[selectedTermIdx[0]])
                                    .put("paymentFrequency", freqValues[selectedFreqIdx[0]])
                                    .put("purpose", purpose)
                                    .put("requestId", UUID.randomUUID().toString());
                            application.getAppContainer().getBackendApiClient().post("/loans/applications", body.toString())
                                    .addOnCompleteListener(res -> {
                                        if (binding == null) return;
                                        if (res.isSuccessful() && res.getResult() != null
                                                && res.getResult().statusCode >= 200 && res.getResult().statusCode < 300) {
                                            Toast.makeText(requireContext(), "Đã gửi hồ sơ vay. Chờ Officer duyệt & giải ngân.", Toast.LENGTH_SHORT).show();
                                            loadLoanApplications(true);
                                        } else {
                                            Toast.makeText(requireContext(), "Không thể gửi hồ sơ vay. Vui lòng thử lại.", Toast.LENGTH_SHORT).show();
                                        }
                                    });
                        } catch (Exception ignored) { }
                    })
                    .show();
        });
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

    private View createNapasCard(ReadModels.AccountSummary account) {
        String last4 = account.displayLast4();
        return buildPaymentCardFrame(
                "NAPAS",
                last4,
                "nimbus",
                cardDrawable(0xFF43537D, 0xFFF4F7FC),
                R.color.navy,
                R.color.slate_blue,
                false,
                "Thẻ Napas tài khoản thanh toán" + (last4.isEmpty() ? "" : " kết thúc bằng " + last4)
        );
    }

    private View createStripeVisaCard(JSONObject cardData) {
        String brand = cardData == null ? "VISA" : cardData.optString("brand", "VISA").toUpperCase(Locale.ROOT);
        String last4 = cardData == null ? "" : cardData.optString("last4", "");
        return buildPaymentCardFrame(
                brand,
                last4,
                "stripe",
                cardDrawableDark(),
                R.color.white,
                R.color.glass_border,
                true,
                "Thẻ Stripe " + brand + (last4.isEmpty() ? "" : " kết thúc bằng " + last4)
        );
    }

    private View buildPaymentCardFrame(String brand, String last4, String watermarkLabel,
                                       GradientDrawable background, int numberColorRes,
                                       int watermarkColorRes, boolean darkCard, String contentDesc) {
        FrameLayout card = new FrameLayout(requireContext());
        card.setPadding(dp(22), dp(24), dp(22), dp(22));
        card.setBackground(background);
        card.setElevation(dp(18));
        card.setContentDescription(contentDesc);
        if (android.os.Build.VERSION.SDK_INT >= 21) card.setOutlineProvider(ViewOutlineProvider.BACKGROUND);

        // Top-left: Metallic EMV Chip
        ImageView chip = new ImageView(requireContext());
        chip.setImageResource(R.drawable.ic_card_chip);
        chip.setContentDescription(null);
        FrameLayout.LayoutParams chipParams = new FrameLayout.LayoutParams(dp(42), dp(32), Gravity.TOP | Gravity.START);
        card.addView(chip, chipParams);

        // Top-right: Vertical brand wordmark (NAPAS / VISA)
        TextView brandText = walletText(brand, 22, R.color.white, true);
        brandText.setLetterSpacing(0.06f);
        brandText.setRotation(-90f);
        FrameLayout.LayoutParams brandParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        brandParams.topMargin = dp(14);
        brandParams.rightMargin = brand.length() > 4 ? dp(-14) : dp(-10);
        card.addView(brandText, brandParams);

        // Subtle center masked last 4 digits if available
        if (last4 != null && !last4.isEmpty()) {
            TextView number = walletText("••••  " + last4, 14, numberColorRes, true);
            number.setAlpha(darkCard ? 0.88f : 0.75f);
            number.setLetterSpacing(0.08f);
            FrameLayout.LayoutParams numParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
            numParams.topMargin = dp(18);
            card.addView(number, numParams);
        }

        // Bottom-left: Vertical subtle brand watermark
        TextView watermark = walletText(watermarkLabel, 22, watermarkColorRes, false);
        watermark.setAlpha(darkCard ? 0.65f : 0.55f);
        watermark.setLetterSpacing(-0.02f);
        watermark.setRotation(-90f);
        FrameLayout.LayoutParams markParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        markParams.bottomMargin = dp(16);
        markParams.leftMargin = dp(-18);
        card.addView(watermark, markParams);

        // Bottom-right: Contactless NFC wave icon
        ImageView contactless = new ImageView(requireContext());
        contactless.setImageResource(R.drawable.ic_card_contactless);
        if (darkCard) {
            contactless.setImageTintList(ContextCompat.getColorStateList(requireContext(), R.color.white));
            contactless.setAlpha(0.85f);
        }
        contactless.setContentDescription(null);
        FrameLayout.LayoutParams contactParams = new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.BOTTOM | Gravity.END);
        card.addView(contactless, contactParams);

        return card;
    }

    private View createStripeUnlinkedCard() {
        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(24), dp(28), dp(24), dp(28));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(28));
        bg.setColor(0xBFFFFFFF);
        bg.setStroke(dp(1), 0x662F3E6E, dp(8), dp(6));
        card.setBackground(bg);
        card.setElevation(dp(12));

        ImageView icon = new ImageView(requireContext());
        icon.setImageResource(R.drawable.ic_nav_add);
        icon.setImageTintList(ContextCompat.getColorStateList(requireContext(), R.color.navy));
        icon.setBackgroundResource(R.drawable.circle_action_button);
        icon.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.addView(icon, new LinearLayout.LayoutParams(dp(56), dp(56)));

        TextView title = walletText(
                stripeCardsLoading ? "Đang kiểm tra thẻ Stripe…" : "Chưa liên kết thẻ Stripe",
                16, R.color.ink, true);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(16), 0, dp(6));
        card.addView(title);

        TextView subtitle = walletText(
                stripeCardsFailed
                        ? "Không thể tải danh sách thẻ. Nhấn bên dưới để thử lại."
                        : "Liên kết thẻ Visa / Mastercard để nạp tiền và thanh toán tiện ích.",
                12, R.color.ink_muted, false);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 0, 0, dp(18));
        card.addView(subtitle);

        MaterialButton linkBtn = new MaterialButton(requireContext());
        linkBtn.setText(stripeCardsFailed ? "Thử lại" : (stripeSetupInFlight ? "Đang mở…" : "Liên kết thẻ ngay"));
        linkBtn.setEnabled(!stripeCardsLoading && !stripeSetupInFlight);
        linkBtn.setTextColor(ContextCompat.getColor(requireContext(), R.color.white));
        linkBtn.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.navy));
        linkBtn.setCornerRadius(dp(24));
        linkBtn.setOnClickListener(v -> {
            if (stripeCardsFailed) loadStripeCards(true);
            else startStripeCardSetup();
        });
        card.addView(linkBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        return card;
    }

    private GradientDrawable cardDrawable(int startColor, int endColor) {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{startColor, 0xFF697AA3, 0xFFA6B4D0, 0xFFE8EEF7, endColor, 0xFFFFFFFF});
        drawable.setCornerRadius(dp(28));
        drawable.setStroke(dp(1), 0xE6FFFFFF);
        return drawable;
    }

    private GradientDrawable cardDrawableDark() {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF131C38, 0xFF222E55, 0xFF475888, 0xFF8FA0C6});
        drawable.setCornerRadius(dp(28));
        drawable.setStroke(dp(1), 0xE6FFFFFF);
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

    private View createCardSlotPagination(int totalSlots, int selectedIndex, ViewPager2 pager) {
        LinearLayout dots = new LinearLayout(requireContext());
        dots.setGravity(Gravity.CENTER);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24));
        params.bottomMargin = dp(8);
        dots.setLayoutParams(params);
        for (int i = 0; i < totalSlots; i++) {
            final int index = i;
            View dot = new View(requireContext());
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.OVAL);
            shape.setColor(i == selectedIndex ? ContextCompat.getColor(requireContext(), R.color.white)
                    : 0x99FFFFFF);
            if (i == selectedIndex) shape.setStroke(dp(1), 0x33777777);
            dot.setBackground(shape);
            dot.setContentDescription("Thẻ " + (i + 1) + " trên " + totalSlots);
            dot.setFocusable(true);
            dot.setClickable(true);
            dot.setOnClickListener(v -> pager.setCurrentItem(index, true));
            LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(i == selectedIndex ? dp(24) : dp(8), dp(8));
            dotParams.setMargins(dp(4), 0, dp(4), 0);
            dots.addView(dot, dotParams);
        }
        return dots;
    }

    private View createNapasInfoPanel(ReadModels.AccountSummary account) {
        LinearLayout panel = walletCard();
        LinearLayout balanceHeader = new LinearLayout(requireContext());
        balanceHeader.setOrientation(LinearLayout.HORIZONTAL);
        balanceHeader.setGravity(Gravity.CENTER_VERTICAL);
        balanceHeader.addView(walletText("Số dư khả dụng", 12, R.color.ink_muted, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageButton eyeBtn = new ImageButton(requireContext());
        eyeBtn.setImageResource(balanceVisible ? R.drawable.ic_eye : R.drawable.ic_eye_slash);
        eyeBtn.setImageTintList(ContextCompat.getColorStateList(requireContext(), R.color.navy));
        eyeBtn.setBackground(null);
        eyeBtn.setContentDescription(balanceVisible ? "Ẩn số dư" : "Hiện số dư");
        eyeBtn.setPadding(dp(4), dp(4), dp(4), dp(4));
        eyeBtn.setOnClickListener(v -> {
            balanceVisible = !balanceVisible;
            renderAll();
        });
        balanceHeader.addView(eyeBtn, new LinearLayout.LayoutParams(dp(28), dp(28)));
        panel.addView(balanceHeader);

        long available = account.availableMinor != null ? account.availableMinor : account.balanceMinor;
        String formatted = ReadSummaryFormatter.formatCurrency(available, account.currency);
        TextView amountText = walletText(balanceVisible ? formatted : "•••••••• ₫", 20, R.color.ink, true);
        amountText.setPadding(0, dp(4), 0, dp(10));
        panel.addView(amountText);

        addInfoRow(panel, "Tài khoản liên kết", "Thanh toán · " + account.currency);
        String acctText = accountNumberVisible
                ? maskAccountId(account.displayAccountNumber())
                : ("••••  ••••  " + account.displayLast4());
        addRevealableInfoRow(
                panel,
                "Số tài khoản",
                acctText,
                accountNumberVisible,
                accountNumberVisible ? "Ẩn số tài khoản" : "Xác thực khuôn mặt để xem số tài khoản",
                () -> {
                    if (accountNumberVisible) {
                        accountNumberVisible = false;
                        renderAll();
                    } else if (!accountNumberFaceBusy) {
                        pendingFaceReveal = FaceRevealTarget.NAPAS_ACCOUNT;
                        FaceScanDialogFragment.open(this);
                    }
                }
        );
        return panel;
    }

    private void addRevealableInfoRow(LinearLayout parent, String label, String value,
                                      boolean visible, String contentDesc, Runnable onToggle) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));
        row.addView(walletText(label, 12, R.color.ink_muted, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(walletText(value, 13, R.color.ink, true));

        ImageButton eye = new ImageButton(requireContext());
        eye.setImageResource(visible ? R.drawable.ic_eye : R.drawable.ic_eye_slash);
        eye.setImageTintList(ContextCompat.getColorStateList(requireContext(), R.color.navy));
        eye.setBackground(null);
        eye.setContentDescription(contentDesc);
        eye.setPadding(dp(6), dp(4), 0, dp(4));
        eye.setOnClickListener(v -> onToggle.run());
        row.addView(eye, new LinearLayout.LayoutParams(dp(28), dp(26)));
        parent.addView(row);
    }

    private void verifyFaceForAccountNumber(byte[] jpeg) {
        if (binding == null) {
            accountNumberFaceBusy = false;
            return;
        }
        var current = binding;
        String requestedUid = uid;
        Toast.makeText(requireContext(), "Đang xác thực khuôn mặt…", Toast.LENGTH_SHORT).show();
        application.getAppContainer().getBackendApiClient().verifyMyFace(jpeg).addOnCompleteListener(task -> {
            accountNumberFaceBusy = false;
            if (binding != current || !java.util.Objects.equals(uid, requestedUid)) return;
            if (task.isSuccessful() && task.getResult() != null && task.getResult().statusCode == 200) {
                if (pendingFaceReveal == FaceRevealTarget.STRIPE_CARD) {
                    stripeCardVisible = true;
                } else {
                    accountNumberVisible = true;
                }
                pendingFaceReveal = FaceRevealTarget.NONE;
                Toast.makeText(requireContext(), "Xác thực khuôn mặt thành công.", Toast.LENGTH_SHORT).show();
                renderAll();
                return;
            }
            pendingFaceReveal = FaceRevealTarget.NONE;
            String msg = "Không thể xác thực khuôn mặt.";
            try {
                if (task.isSuccessful() && task.getResult() != null) {
                    String code = new JSONObject(task.getResult().body).optString("error", "");
                    if ("FACE_NOT_ENROLLED".equals(code)) msg = "Bạn chưa đăng ký khuôn mặt eKYC.";
                    else if ("FACE_MISMATCH".equals(code)) msg = "Khuôn mặt không khớp. Vui lòng thử lại.";
                }
            } catch (Exception ignored) { }
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
        });
    }

    private View createStripeInfoPanel(JSONObject cardData) {
        LinearLayout panel = walletCard();
        String brand = cardData.optString("brand", "VISA").toUpperCase(Locale.ROOT);
        String last4 = cardData.optString("last4", "");
        int expMonth = cardData.optInt("expMonth", 0);
        int expYear = cardData.optInt("expYear", 0);
        String expiry = (expMonth > 0 && expYear > 0)
                ? String.format(Locale.US, "%02d/%d", expMonth, expYear) : "—";
        addInfoRow(panel, "Cổng thanh toán", "Stripe · " + brand);
        String cardNumberText = last4.isEmpty() ? "—"
                : (stripeCardVisible ? formatSandboxPan(brand, last4) : "••••  ••••  ••••  " + last4);
        addRevealableInfoRow(
                panel,
                "Số thẻ",
                cardNumberText,
                stripeCardVisible,
                stripeCardVisible ? "Ẩn số thẻ" : "Xác thực khuôn mặt để xem số thẻ",
                () -> {
                    if (stripeCardVisible) {
                        stripeCardVisible = false;
                        renderAll();
                    } else if (!accountNumberFaceBusy) {
                        pendingFaceReveal = FaceRevealTarget.STRIPE_CARD;
                        FaceScanDialogFragment.open(this);
                    }
                }
        );
        addInfoRow(panel, "Hết hạn", stripeCardVisible ? expiry : "••/••••");
        return panel;
    }

    private String formatSandboxPan(String brand, String last4) {
        String prefix = "4242  4242  4242  ";
        if ("MASTERCARD".equalsIgnoreCase(brand)) prefix = "5555  5555  5555  ";
        else if ("AMEX".equalsIgnoreCase(brand)) prefix = "3782  8224  6310  ";
        else if (!"VISA".equalsIgnoreCase(brand)) prefix = "4000  0000  0000  ";
        return prefix + last4;
    }

    private View createStripeUnlinkedInfoPanel() {
        LinearLayout panel = walletCard();
        panel.addView(walletText("Thẻ quốc tế (Stripe)", 14, R.color.ink, true));
        TextView desc = walletText(
                "Sau khi liên kết thẻ Visa hoặc Mastercard qua Stripe, bạn có thể dùng thẻ này để nạp tiền vào tài khoản Nimbus hoặc thanh toán hóa đơn nhanh.",
                12, R.color.ink_muted, false);
        desc.setPadding(0, dp(6), 0, 0);
        panel.addView(desc);
        return panel;
    }

    private void addInfoRow(LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));
        row.addView(walletText(label, 12, R.color.ink_muted, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(walletText(value, 13, R.color.ink, true));
        parent.addView(row);
    }

    private String maskAccountId(String id) {
        if (id == null || id.isEmpty()) return "—";
        String digits = id.replaceAll("\\s+", "");
        if (digits.matches("\\d{10,16}")) {
            return digits.replaceAll("(.{4})(?=.)", "$1 ");
        }
        if (digits.length() <= 4) return digits;
        return "••••  " + digits.substring(digits.length() - 4);
    }

    private View createStripeCardActions(ReadModels.AccountSummary checkingAccount, JSONObject cardData) {
        LinearLayout actions = new LinearLayout(requireContext());
        actions.setGravity(Gravity.CENTER);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dp(12);
        actions.setLayoutParams(rowParams);

        String[] titles = {"Nạp tiền", "Trả hóa đơn", "Đổi thẻ", "Hủy liên kết"};
        int[] icons = {R.drawable.ic_nav_add, R.drawable.ic_receipt,
                R.drawable.ic_swap_horizontal, R.drawable.ic_close};
        for (int i = 0; i < titles.length; i++) {
            final int actionIndex = i;
            LinearLayout action = new LinearLayout(requireContext());
            action.setOrientation(LinearLayout.VERTICAL);
            action.setGravity(Gravity.CENTER);
            action.setClickable(true);
            action.setFocusable(true);
            action.setContentDescription(titles[i]);

            ImageView icon = new ImageView(requireContext());
            icon.setImageResource(icons[i]);
            icon.setImageTintList(ContextCompat.getColorStateList(requireContext(),
                    actionIndex == 3 ? R.color.danger : R.color.navy));
            icon.setBackgroundResource(R.drawable.circle_action_button);
            icon.setPadding(dp(15), dp(15), dp(15), dp(15));
            action.addView(icon, new LinearLayout.LayoutParams(dp(56), dp(56)));

            TextView label = walletText(titles[i], 11, actionIndex == 3 ? R.color.danger : R.color.ink, true);
            label.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            labelParams.topMargin = dp(6);
            action.addView(label, labelParams);

            action.setOnClickListener(view -> {
                if (actionIndex == 0) {
                    Bundle args = new Bundle();
                    args.putString("demoCashOperation", "DEPOSIT");
                    args.putString("accountId", checkingAccount.id);
                    Navigation.findNavController(view).navigate(R.id.transfer, args);
                } else if (actionIndex == 1) {
                    Navigation.findNavController(view).navigate(R.id.utilitiesHub);
                } else if (actionIndex == 2) {
                    startStripeCardSetup();
                } else {
                    confirmDetachStripeCard(cardData);
                }
            });
            actions.addView(action, new LinearLayout.LayoutParams(0, dp(88), 1f));
        }
        return actions;
    }

    private void loadStripeCards(boolean force) {
        if (stripeCardsLoading || (stripeCardsLoaded && !force)) return;
        stripeCardsLoading = true;
        stripeCardsFailed = false;
        var current = binding;
        String requestedUid = uid;
        application.getAppContainer().getBackendApiClient().get("/stripe/cards").addOnCompleteListener(task -> {
            if (binding != current || !java.util.Objects.equals(uid, requestedUid)) return;
            stripeCardsLoading = false;
            stripeCardsLoaded = true;
            stripeCards.clear();
            try {
                if (task.isSuccessful() && task.getResult() != null && task.getResult().statusCode == 200) {
                    JSONObject body = new JSONObject(task.getResult().body);
                    stripeConfigured = body.optBoolean("configured", true);
                    JSONArray arr = body.optJSONArray("cards");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            stripeCards.add(arr.getJSONObject(i));
                        }
                    }
                } else {
                    stripeCardsFailed = true;
                }
            } catch (Exception ignored) {
                stripeCardsFailed = true;
            }
            renderAll();
        });
    }

    private void startStripeCardSetup() {
        if (stripeSetupInFlight) return;
        if (stripeSetupSheet == null || !stripeConfigured) {
            Toast.makeText(requireContext(), "Cổng thẻ Stripe chưa được cấu hình.", Toast.LENGTH_SHORT).show();
            return;
        }
        stripeSetupInFlight = true;
        renderAll();
        var current = binding;
        String requestedUid = uid;
        try {
            JSONObject payload = new JSONObject().put("requestId", UUID.randomUUID().toString());
            application.getAppContainer().getBackendApiClient()
                    .post("/stripe/cards/setup-intent", payload.toString())
                    .addOnCompleteListener(task -> {
                        if (binding != current || !java.util.Objects.equals(uid, requestedUid)) return;
                        stripeSetupInFlight = false;
                        try {
                            if (task.isSuccessful() && task.getResult() != null && task.getResult().statusCode == 200) {
                                JSONObject res = new JSONObject(task.getResult().body);
                                String setupSecret = res.optString("setupIntentClientSecret", "");
                                if (setupSecret.isEmpty()) {
                                    Toast.makeText(requireContext(), "Không thể khởi tạo liên kết thẻ.", Toast.LENGTH_SHORT).show();
                                    renderAll();
                                    return;
                                }
                                PaymentSheet.Configuration.Builder cfg = new PaymentSheet.Configuration.Builder("Nimbus Bank");
                                if (!res.isNull("customerId") && !res.isNull("customerSessionClientSecret")) {
                                    cfg.customer(PaymentSheet.CustomerConfiguration.Companion.createWithCustomerSession(
                                            res.getString("customerId"), res.getString("customerSessionClientSecret")));
                                }
                                stripeSetupSheet.presentWithSetupIntent(setupSecret, cfg.build());
                                return;
                            }
                        } catch (Exception ignored) { }
                        Toast.makeText(requireContext(), "Không thể mở biểu mẫu liên kết thẻ Stripe.", Toast.LENGTH_SHORT).show();
                        renderAll();
                    });
        } catch (Exception e) {
            stripeSetupInFlight = false;
            renderAll();
        }
    }

    private void onStripeSetupResult(@NonNull PaymentSheetResult result) {
        if (binding == null) return;
        if (result instanceof PaymentSheetResult.Completed) {
            Toast.makeText(requireContext(), "Đã liên kết thẻ Stripe thành công.", Toast.LENGTH_SHORT).show();
            stripeCardsLoaded = false;
            loadStripeCards(true);
        } else if (result instanceof PaymentSheetResult.Failed) {
            Toast.makeText(requireContext(), "Liên kết thẻ thất bại. Vui lòng thử lại.", Toast.LENGTH_SHORT).show();
            renderAll();
        } else {
            renderAll();
        }
    }

    private void confirmDetachStripeCard(JSONObject cardData) {
        String pmId = cardData == null ? "" : cardData.optString("id", "");
        String last4 = cardData == null ? "" : cardData.optString("last4", "");
        if (pmId.isEmpty()) return;
        new ThemedDialogBuilder(requireContext())
                .setTitle("Hủy liên kết thẻ Stripe")
                .setMessage("Bạn có chắc muốn gỡ thẻ kết thúc bằng " + last4 + " khỏi Nimbus Bank?")
                .setNegativeButton("Giữ lại", null)
                .setPositiveButton("Hủy liên kết", (dialog, which) -> {
                    var current = binding;
                    String requestedUid = uid;
                    application.getAppContainer().getBackendApiClient()
                            .delete("/stripe/cards/" + android.net.Uri.encode(pmId))
                            .addOnCompleteListener(task -> {
                                if (binding != current || !java.util.Objects.equals(uid, requestedUid)) return;
                                if (task.isSuccessful() && task.getResult() != null && task.getResult().statusCode == 200) {
                                    Toast.makeText(requireContext(), "Đã hủy liên kết thẻ Stripe.", Toast.LENGTH_SHORT).show();
                                    stripeCardsLoaded = false;
                                    loadStripeCards(true);
                                } else {
                                    Toast.makeText(requireContext(), "Không thể hủy liên kết thẻ. Vui lòng thử lại.", Toast.LENGTH_SHORT).show();
                                }
                            });
                })
                .show();
    }

    private View createCardActions(ReadModels.AccountSummary account) {
        LinearLayout actions = new LinearLayout(requireContext());
        actions.setGravity(Gravity.CENTER);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dp(12);
        actions.setLayoutParams(rowParams);
        String[] titles = {"NFC Tắt", "Hạn mức thẻ", "Khóa thẻ", "Cài đặt"};
        int[] icons = {R.drawable.ic_nfc, R.drawable.ic_schedule,
                R.drawable.ic_lock, R.drawable.ic_settings};
        final boolean[] nfcOn = {false};
        final boolean[] locked = {false};
        final long[] cardLimit = {20_000_000L};
        final TextView[] actionLabels = new TextView[titles.length];
        final ImageView[] actionIcons = new ImageView[titles.length];
        for (int i = 0; i < titles.length; i++) {
            final int actionIndex = i;
            LinearLayout action = new LinearLayout(requireContext());
            action.setOrientation(LinearLayout.VERTICAL);
            action.setGravity(Gravity.CENTER);
            action.setClickable(true);
            action.setFocusable(true);
            action.setContentDescription(titles[i]);
            ImageView icon = new ImageView(requireContext());
            icon.setImageResource(icons[i]);
            icon.setImageTintList(ContextCompat.getColorStateList(requireContext(), R.color.navy));
            icon.setBackgroundResource(R.drawable.circle_action_button);
            icon.setPadding(dp(15), dp(15), dp(15), dp(15));
            action.addView(icon, new LinearLayout.LayoutParams(dp(56), dp(56)));
            TextView label = walletText(titles[i], 11, R.color.ink, true);
            label.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            labelParams.topMargin = dp(6);
            action.addView(label, labelParams);
            actionLabels[i] = label;
            actionIcons[i] = icon;
            action.setOnClickListener(view -> {
                if (actionIndex == 0) {
                    nfcOn[0] = !nfcOn[0];
                    label.setText(nfcOn[0] ? "NFC Bật" : "NFC Tắt");
                    icon.setImageTintList(ContextCompat.getColorStateList(requireContext(),
                            nfcOn[0] ? R.color.success : R.color.navy));
                    try {
                        application.getAppContainer().getBackendApiClient().patch(
                                "/accounts/" + android.net.Uri.encode(account.id) + "/card-controls",
                                new JSONObject().put("nfcEnabled", nfcOn[0]).toString());
                    } catch (Exception ignored) { }
                } else if (actionIndex == 1) {
                    new ThemedDialogBuilder(requireContext())
                            .setTitle("Hạn mức thẻ")
                            .setMessage("Hạn mức chi tiêu thẻ hiện tại: "
                                    + ReadSummaryFormatter.formatCurrency(cardLimit[0], account.currency) + " mỗi ngày.")
                            .setPositiveButton("Đóng", null).show();
                } else if (actionIndex == 2) {
                    locked[0] = !locked[0];
                    label.setText(locked[0] ? "Đã khóa" : "Khóa thẻ");
                    icon.setImageTintList(ContextCompat.getColorStateList(requireContext(),
                            locked[0] ? R.color.danger : R.color.navy));
                    try {
                        application.getAppContainer().getBackendApiClient().patch(
                                "/accounts/" + android.net.Uri.encode(account.id) + "/card-controls",
                                new JSONObject().put("cardFrozen", locked[0]).toString());
                    } catch (Exception ignored) { }
                } else if (actionIndex == 3) {
                    openDetails(view, account);
                }
            });
            actions.addView(action, new LinearLayout.LayoutParams(0, dp(88), 1f));
        }
        application.getAppContainer().getBackendApiClient()
                .get("/accounts/" + android.net.Uri.encode(account.id) + "/limits")
                .addOnCompleteListener(task -> {
                    if (binding == null || !task.isSuccessful() || task.getResult() == null
                            || task.getResult().statusCode != 200) return;
                    try {
                        JSONObject lim = new JSONObject(task.getResult().body);
                        nfcOn[0] = lim.optBoolean("nfcEnabled", false);
                        locked[0] = lim.optBoolean("cardFrozen", false);
                        cardLimit[0] = lim.optLong("cardDailyLimitMinor", 20_000_000L);
                        if (actionLabels[0] != null) {
                            actionLabels[0].setText(nfcOn[0] ? "NFC Bật" : "NFC Tắt");
                            actionIcons[0].setImageTintList(ContextCompat.getColorStateList(requireContext(),
                                    nfcOn[0] ? R.color.success : R.color.navy));
                        }
                        if (actionLabels[2] != null) {
                            actionLabels[2].setText(locked[0] ? "Đã khóa" : "Khóa thẻ");
                            actionIcons[2].setImageTintList(ContextCompat.getColorStateList(requireContext(),
                                    locked[0] ? R.color.danger : R.color.navy));
                        }
                    } catch (Exception ignored) { }
                });
        return actions;
    }

    private View createOtherAccountRow(ReadModels.AccountSummary account) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(14), dp(14), dp(14));
        row.setBackgroundResource(R.drawable.glass_panel_card);
        row.setClickable(true);
        row.setFocusable(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(10);
        row.setLayoutParams(params);

        boolean savings = "SAVINGS".equalsIgnoreCase(account.type);
        ImageView icon = new ImageView(requireContext());
        icon.setImageResource(savings ? R.drawable.ic_trending_up : R.drawable.ic_account_balance);
        icon.setImageTintList(ContextCompat.getColorStateList(requireContext(), R.color.navy));
        icon.setBackgroundResource(R.drawable.circle_action_button);
        icon.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(42), dp(42));
        iconLp.setMarginEnd(dp(12));
        row.addView(icon, iconLp);

        LinearLayout text = new LinearLayout(requireContext());
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(walletText(Phase2Presentation.accountTitle(account.type), 14, R.color.ink, true));
        String last4 = account.displayLast4();
        String sub = (last4.isEmpty() ? "" : "•••• " + last4 + " · ") + accountStatusLabel(account.status);
        TextView state = walletText(sub, 12, R.color.ink_muted, false);
        state.setPadding(0, dp(3), 0, 0);
        text.addView(state);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        long amountMinor = account.availableMinor != null ? account.availableMinor : account.balanceMinor;
        TextView amountTv = walletText(
                ReadSummaryFormatter.formatCurrency(amountMinor, account.currency),
                13,
                savings ? R.color.success : R.color.ink,
                true);
        amountTv.setPadding(dp(8), 0, dp(6), 0);
        row.addView(amountTv);

        ImageView arrow = new ImageView(requireContext());
        arrow.setImageResource(R.drawable.ic_chevron_right);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(18), dp(18)));
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
        // Hatched monthly limit progress bar bound to real account limits
        LinearLayout limitHeader = new LinearLayout(requireContext());
        limitHeader.setOrientation(LinearLayout.HORIZONTAL);
        limitHeader.setGravity(Gravity.CENTER_VERTICAL);
        limitHeader.setPadding(0, dp(12), 0, dp(6));
        TextView limitLabel = walletText("Hạn mức tháng · Đang tải…", 12, R.color.ink_muted, false);
        limitHeader.addView(limitLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView limitPct = walletText("0%", 11, R.color.navy, true);
        limitPct.setBackgroundResource(R.drawable.glass_pill_bg);
        limitPct.setPadding(dp(8), dp(2), dp(8), dp(2));
        limitHeader.addView(limitPct);
        balanceCard.addView(limitHeader);
        com.example.final_term.ui.common.HatchedProgressBar limitBar =
                new com.example.final_term.ui.common.HatchedProgressBar(requireContext());
        limitBar.setProgress(0f);
        balanceCard.addView(limitBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)));
        if (checking != null) {
            final ReadModels.AccountSummary checkedAccount = checking;
            application.getAppContainer().getBackendApiClient()
                    .get("/accounts/" + android.net.Uri.encode(checkedAccount.id) + "/limits")
                    .addOnCompleteListener(task -> {
                        if (binding == null || !task.isSuccessful() || task.getResult() == null
                                || task.getResult().statusCode != 200) return;
                        try {
                            JSONObject lim = new JSONObject(task.getResult().body);
                            long used = Math.max(0L, lim.optLong("monthlyUsedMinor", 0L));
                            long limit = Math.max(1L, lim.optLong("monthlyLimitMinor", 30_000_000L));
                            float ratio = Math.min(1f, (float) used / (float) limit);
                            limitBar.setProgress(ratio);
                            limitLabel.setText("Hạn mức tháng · "
                                    + ReadSummaryFormatter.formatCurrency(used, walletCurrency)
                                    + " / " + ReadSummaryFormatter.formatCurrency(limit, walletCurrency));
                            limitPct.setText(Math.round(ratio * 100f) + "%");
                        } catch (Exception ignored) { }
                    });
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
        LinearLayout weekHeader = new LinearLayout(requireContext());
        weekHeader.setOrientation(LinearLayout.HORIZONTAL);
        weekHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView weekTotal = walletText(ReadSummaryFormatter.formatCurrency(thisWeek, "VND"), 19, R.color.ink, true);
        weekHeader.addView(weekTotal, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView filterPill = walletText("Tuần này", 11, R.color.navy, true);
        filterPill.setBackgroundResource(R.drawable.glass_pill_bg);
        filterPill.setPadding(dp(10), dp(4), dp(10), dp(4));
        weekHeader.addView(filterPill);
        activityCard.addView(weekHeader);
        if (hasPreviousWeekActivity && previousWeek > 0) {
            double change = ((double) thisWeek - previousWeek) * 100d / previousWeek;
            TextView comparison = walletText(String.format(Locale.getDefault(), "%+.0f%% so với tuần trước", change),
                    12, change >= 0 ? R.color.navy : R.color.ink_muted, false);
            comparison.setPadding(0, dp(3), 0, dp(8));
            activityCard.addView(comparison);
        } else if (thisWeek == 0) {
            TextView comparison = walletText("Chưa có thu nhập trong tuần này", 12, R.color.ink_muted, false);
            comparison.setPadding(0, dp(3), 0, dp(8));
            activityCard.addView(comparison);
        }
        float[] chartValues = new float[7];
        long maxDaily = 0;
        for (long value : daily) maxDaily = Math.max(maxDaily, value);
        if (maxDaily > 0) {
            for (int i = 0; i < daily.length; i++) chartValues[i] = (float) daily[i] / maxDaily;
        }
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
        bills.setText("Xem hóa đơn");
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

        androidx.appcompat.app.AlertDialog dialog = new ThemedDialogBuilder(requireContext())
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
                                                new ThemedDialogBuilder(requireContext()).setTitle("Đã tiếp nhận khách hàng")
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

        androidx.appcompat.app.AlertDialog dialog = new ThemedDialogBuilder(requireContext())
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
                                    new ThemedDialogBuilder(requireContext()).setTitle("Thành công")
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
                        new ThemedDialogBuilder(requireContext()).setTitle("Lịch sử hồ sơ khách hàng")
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
            new ThemedDialogBuilder(requireContext())
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
        new ThemedDialogBuilder(requireContext())
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
            case CACHED: return "Đang hiển thị dữ liệu gần nhất.";
            case ERROR: return accountMessage == null ? "Không thể tải dữ liệu." : accountMessage;
            case EMPTY: return "Chưa có tài khoản nào.";
            default: return "";
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

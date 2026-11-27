package com.example.final_term.ui.customer;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.example.final_term.R;
import com.example.final_term.core.FinalTermApplication;
import com.example.final_term.data.ReadModelResult;
import com.example.final_term.data.ReadModels;
import com.example.final_term.data.ReadSummaryFormatter;
import com.example.final_term.databinding.FragmentAccountDetailBinding;
import com.example.final_term.ui.customer.Phase2Presentation;
import com.google.android.material.button.MaterialButtonToggleGroup;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class AccountDetailFragment extends Fragment {
    private static final int SCHEDULE_PAGE_SIZE = 4;

    private FragmentAccountDetailBinding binding;
    private String currentAccountId = "";
    private String currentAccountType = "";
    private Long currentBalance = null;
    private String currentCurrency = "";
    private String loadedTermsKey = "";
    private String loadedHistoryFor = "";
    private String requestedFrequency;
    private JSONArray scheduleItems;
    private int visibleScheduleItems = SCHEDULE_PAGE_SIZE;
    private boolean updatingFrequencySelection;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle state) {
        binding = FragmentAccountDetailBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        Bundle args = getArguments();
        currentAccountType = args == null ? "UNKNOWN" : args.getString("accountType", "UNKNOWN");
        currentAccountId = args == null ? "" : args.getString("accountId", "");

        binding.titleText.setText(Phase2Presentation.accountTitle(currentAccountType));
        binding.subtitleText.setText(Phase2Presentation.accountSubtitle(currentAccountType));
        binding.balanceText.setText("—");
        binding.statusText.setText("ĐANG TẢI");
        binding.genericDetails.setVisibility(View.GONE);
        binding.savingsContent.setVisibility("SAVINGS".equals(currentAccountType) ? View.VISIBLE : View.GONE);
        binding.mortgageContent.setVisibility("MORTGAGE".equals(currentAccountType) ? View.VISIBLE : View.GONE);
        binding.historySection.setVisibility(View.VISIBLE);
        binding.heroLabel.setText("MORTGAGE".equals(currentAccountType)
                ? "Dư nợ còn lại" : "SAVINGS".equals(currentAccountType)
                        ? "Tổng tiền gửi tiết kiệm" : "Số dư tài khoản");
        binding.heroMeta.setVisibility(View.GONE);
        binding.detailText.setText("");
        binding.historyContainer.setVisibility(binding.historySection.getVisibility() == View.VISIBLE
                ? View.VISIBLE : View.GONE);
        binding.historyContainer.removeAllViews();
        binding.mortgageScheduleContainer.removeAllViews();
        loadedTermsKey = "";
        loadedHistoryFor = "";
        requestedFrequency = null;
        scheduleItems = null;
        visibleScheduleItems = SCHEDULE_PAGE_SIZE;

        binding.backButton.setOnClickListener(v -> Navigation.findNavController(v).navigateUp());
        binding.historyTitle.setOnClickListener(v -> {
            Bundle historyArgs = new Bundle();
            historyArgs.putString("accountId", currentAccountId);
            Navigation.findNavController(v).navigate(R.id.transactionHistory, historyArgs);
        });
        binding.shareButton.setOnClickListener(v -> shareAccount());

        binding.mortgageFrequencyToggle.addOnButtonCheckedListener(
                (MaterialButtonToggleGroup group, int checkedId, boolean isChecked) -> {
                    if (!isChecked || updatingFrequencySelection || currentAccountId.isEmpty()) return;
                    String frequency = checkedId == R.id.mortgage_biweekly_button ? "BIWEEKLY" : "MONTHLY";
                    if (frequency.equals(requestedFrequency)) return;
                    requestedFrequency = frequency;
                    loadTerms(currentAccountId, currentAccountType);
                });
        binding.mortgageLoadMore.setOnClickListener(v -> {
            visibleScheduleItems += SCHEDULE_PAGE_SIZE;
            renderMortgageSchedule();
        });

        if (currentAccountId.isEmpty()) {
            binding.statusText.setText("Thiếu mã tài khoản");
            return;
        }

        FinalTermApplication app = (FinalTermApplication) requireActivity().getApplication();
        app.getAppContainer().getAuthRepository().observeAuthState().observe(getViewLifecycleOwner(), auth -> {
            if (auth == null || auth.getUser() == null) {
                currentBalance = null;
                binding.balanceText.setText("—");
                binding.statusText.setText("CẦN ĐĂNG NHẬP");
                return;
            }
            var repo = app.getAppContainer().getReadRepository();
            var accounts = auth.getUser().getRole() == com.example.final_term.auth.UserRole.OFFICER
                    ? repo.observeAllAccountsForOfficer()
                    : repo.observeAccounts(auth.getUser().getUid());
            accounts.observe(getViewLifecycleOwner(), result -> renderAccount(result, currentAccountId));
        });
    }

    private void renderAccount(ReadModelResult<List<ReadModels.AccountSummary>> result, String accountId) {
        List<ReadModels.AccountSummary> accounts = result.getData();
        if (accounts != null) for (ReadModels.AccountSummary account : accounts) {
            if (!accountId.equals(account.id)) continue;
            currentCurrency = account.currency;
            binding.titleText.setText(Phase2Presentation.accountTitle(account.type));
            binding.subtitleText.setText(Phase2Presentation.accountSubtitle(account.type));
            binding.statusText.setText("ACTIVE".equals(account.status) ? "ĐANG HOẠT ĐỘNG" : "INACTIVE".equals(account.status) ? "TẠM KHÓA" : "ĐANG CẬP NHẬT");
            binding.heroMeta.setVisibility(View.GONE);

            if ("MORTGAGE".equals(account.type)) {
                binding.heroLabel.setText("Dư nợ còn lại");
            } else if ("SAVINGS".equals(account.type)) {
                binding.heroLabel.setText("Tổng tiền gửi tiết kiệm");
                currentBalance = account.availableMinor != null ? account.availableMinor : account.balanceMinor;
                binding.balanceText.setText(ReadSummaryFormatter.formatCurrency(currentBalance, currentCurrency));
            } else {
                binding.heroLabel.setText("Số dư khả dụng");
                currentBalance = account.availableMinor != null ? account.availableMinor : account.balanceMinor;
                binding.balanceText.setText(ReadSummaryFormatter.formatCurrency(currentBalance, currentCurrency));
                binding.heroMeta.setVisibility(View.VISIBLE);
                binding.heroMeta.setText("Tài khoản thanh toán Platinum");
                binding.miniVisaBadge.setVisibility(View.VISIBLE);
                binding.checkingLimitBox.setVisibility(View.VISIBLE);
                binding.checkingLimitBar.setProgress(0.64f);
                binding.genericDetails.setVisibility(View.VISIBLE);
                bindCheckingDetails("Tài khoản thanh toán Platinum", account.displayAccountNumber(), currentCurrency,
                        "Hội sở chính", account.status);
                var current = binding;
                ((FinalTermApplication) requireActivity().getApplication()).getAppContainer().getBackendApiClient()
                        .get("/accounts/" + android.net.Uri.encode(account.id) + "/limits")
                        .addOnCompleteListener(task -> {
                            if (binding != current || !task.isSuccessful() || task.getResult() == null
                                    || task.getResult().statusCode != 200) return;
                            try {
                                JSONObject lim = new JSONObject(task.getResult().body);
                                long limit = Math.max(1L, lim.optLong("dailyLimitMinor", 10_000_000L));
                                long used = Math.max(0L, lim.optLong("dailyUsedMinor", 0L));
                                float ratio = Math.min(1f, (float) used / (float) limit);
                                int pct = Math.round(ratio * 100f);
                                binding.checkingLimitBar.setProgress(ratio);
                                binding.checkingLimitText.setText("Hạn mức giao dịch · "
                                        + ReadSummaryFormatter.formatCurrency(used, currentCurrency)
                                        + " / " + ReadSummaryFormatter.formatCurrency(limit, currentCurrency));
                                binding.checkingLimitPct.setText(pct + "%");
                                String displayName = lim.optString("displayName", "Tài khoản thanh toán");
                                String branchName = lim.optString("branchName", "Hội sở chính");
                                binding.heroMeta.setText(displayName);
                                bindCheckingDetails(displayName, account.displayAccountNumber(), currentCurrency, branchName, account.status);
                            } catch (Exception ignored) { }
                        });
            }

            if (!"CHECKING".equals(account.type)) loadTerms(account.id, account.type);
            loadAccountHistory(account.id);
            return;
        }

        currentBalance = null;
        binding.balanceText.setText("—");
        binding.statusText.setText(result.getStatus() == ReadModelResult.Status.LOADING
                ? "ĐANG TẢI" : result.getStatus() == ReadModelResult.Status.ERROR
                ? "LỖI KẾT NỐI" : "KHÔNG CÓ QUYỀN XEM");
    }

    private void bindCheckingDetails(String name, String accountId, String currency, String branch, String status) {
        if (binding == null) return;
        binding.checkingAccountName.setText(name);
        binding.checkingAccountNumber.setText(accountId);
        binding.checkingCurrency.setText(currency == null || currency.trim().isEmpty() ? "VND" : currency);
        binding.checkingBranch.setText(branch);
        boolean active = "ACTIVE".equalsIgnoreCase(status);
        binding.checkingState.setText(active ? "Đang hoạt động" : "Tạm khóa");
        binding.checkingState.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(),
                active ? R.color.success : R.color.warning));
        binding.checkingAccountNumberRow.setOnClickListener(v -> {
            ClipboardManager clipboard =
                    (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("account_number", accountId));
                Toast.makeText(requireContext(), "Đã sao chép số tài khoản", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void loadTerms(String accountId, String type) {
        final String endpoint;
        switch (type) {
            case "CHECKING": endpoint = ""; break;
            case "SAVINGS": endpoint = "/savings-estimate"; break;
            case "MORTGAGE":
                String frequency = requestedFrequency == null ? "" : "&frequency=" + requestedFrequency;
                endpoint = "/mortgage-schedule" + (frequency.isEmpty() ? "" : "?" + frequency.substring(1));
                break;
            default: endpoint = "";
        }
        if (endpoint.isEmpty()) return;
        String key = accountId + ":" + type + ":" + endpoint;
        if (loadedTermsKey.equals(key)) return;
        loadedTermsKey = key;
        FragmentAccountDetailBinding current = binding;
        if ("MORTGAGE".equals(type)) {
            binding.mortgagePayment.setText("Đang tính lịch trả góp…");
            binding.mortgageScheduleContainer.removeAllViews();
        } else if ("SAVINGS".equals(type)) {
            binding.savingsProfit.setText("Đang tính…");
        } else {
            binding.detailText.setText("Đang tải điều khoản tài khoản…");
        }

        FinalTermApplication app = (FinalTermApplication) requireActivity().getApplication();
        app.getAppContainer().getBackendApiClient()
                .get("/accounts/" + android.net.Uri.encode(accountId) + endpoint)
                .addOnCompleteListener(task -> {
                    if (binding != current || !loadedTermsKey.equals(key)) return;
                    try {
                        if (!task.isSuccessful() || task.getResult().statusCode != 200)
                            throw new JSONException("Unavailable");
                        JSONObject terms = new JSONObject(task.getResult().body);
                        if ("SAVINGS".equals(type)) renderSavings(terms);
                        else if ("MORTGAGE".equals(type)) renderMortgage(terms);
                    } catch (JSONException error) {
                        loadedTermsKey = "";
                        TextView retryTarget = "MORTGAGE".equals(type) ? binding.mortgagePayment
                                : "SAVINGS".equals(type) ? binding.savingsProfit : binding.detailText;
                        retryTarget.setText("Chưa tải được dữ liệu. Chạm để thử lại.");
                        retryTarget.setOnClickListener(v -> loadTerms(accountId, type));
                    }
                });
    }

    private void renderSavings(JSONObject terms) throws JSONException {
        String currency = terms.getString("currency");
        currentCurrency = currency;
        currentBalance = terms.optLong("balanceMinor", terms.getLong("principalMinor"));
        binding.heroLabel.setText("Tổng tiền gửi tiết kiệm");
        binding.balanceText.setText(formatMoney(currentBalance, currency));

        NumberFormat percent = NumberFormat.getPercentInstance(Locale.forLanguageTag("vi-VN"));
        percent.setMaximumFractionDigits(2);
        binding.savingsRate.setText(percent.format(terms.getInt("annualRateBasisPoints") / 10000.0) + "/năm");
        binding.savingsOpened.setText(date(terms, "openedAt"));
        binding.savingsMaturity.setText(date(terms, "maturityAt"));
        binding.savingsPolicy.setText("Kỳ tính lãi: " + date(terms, "periodStart") + " – " + date(terms, "periodEnd"));
        binding.savingsConvention.setText("Khoản lãi dự kiến chưa được cộng vào số dư.");
        binding.savingsProfit.setText("+" + formatMoney(terms.getLong("profitMinor"), currency));
        long maturityProfit = terms.optLong("maturityProfitMinor", terms.getLong("profitMinor") * 12L);
        binding.savingsMaturityProfit.setText("+" + formatMoney(maturityProfit, currency) + " khi đáo hạn");
    }

    private void renderMortgage(JSONObject terms) throws JSONException {
        currentCurrency = terms.getString("currency");
        currentBalance = terms.isNull("outstandingMinor")
                ? terms.getLong("principalMinor") : terms.getLong("outstandingMinor");
        binding.heroLabel.setText("Dư nợ còn lại");
        binding.balanceText.setText(formatMoney(currentBalance, currentCurrency));
        long origPrincipal = terms.optLong("principalMinor", 0L);
        float paidRatio = origPrincipal > 0L && currentBalance != null
                ? Math.max(0f, Math.min(1f, (float) (origPrincipal - currentBalance) / (float) origPrincipal))
                : 0f;
        int paidPct = Math.round(paidRatio * 100f);
        binding.mortgageProgressBar.setProgress(paidRatio);
        binding.mortgageProgressTitle.setText("Tiến độ trả nợ · Đã trả " + paidPct + "%");
        String contractCode = terms.optString("contractCode", currentAccountId);
        long nextDueAt = terms.optLong("nextDueAt", 0L);
        if (nextDueAt > 0L) {
            String dueStr = java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT,
                    Locale.forLanguageTag("vi-VN")).format(new Date(nextDueAt));
            long daysLeft = Math.max(0L, (nextDueAt - System.currentTimeMillis()) / 86_400_000L);
            binding.mortgageDueBadge.setText(daysLeft <= 7 ? "Sắp đến hạn" : "Đang trả nợ");
            binding.mortgageContractInfo.setText("HĐ #" + contractCode + " · Hạn thanh toán: " + dueStr
                    + " (còn " + daysLeft + " ngày)");
        } else {
            binding.mortgageContractInfo.setText("HĐ #" + contractCode);
        }
        binding.mortgageRate.setText(formatRate(terms.getInt("annualRateBasisPoints")));
        binding.mortgagePrincipal.setText(formatMoney(terms.getLong("principalMinor"), currentCurrency));
        binding.mortgageTerm.setText(terms.getInt("termMonths") + " tháng");
        binding.mortgageTotal.setText(formatMoney(terms.getLong("totalMinor"), currentCurrency));

        String frequency = terms.optString("paymentFrequency", "MONTHLY");
        requestedFrequency = frequency;
        updatingFrequencySelection = true;
        binding.mortgageFrequencyToggle.check("BIWEEKLY".equals(frequency)
                ? R.id.mortgage_biweekly_button : R.id.mortgage_monthly_button);
        updatingFrequencySelection = false;
        binding.mortgageFrequencyCaption.setText("Dự kiến theo dư nợ hiện tại · "
                + ("BIWEEKLY".equals(frequency) ? "hai tuần một lần" : "hàng tháng"));

        scheduleItems = terms.getJSONArray("items");
        visibleScheduleItems = SCHEDULE_PAGE_SIZE;
        JSONObject first = scheduleItems.length() == 0 ? null : scheduleItems.getJSONObject(0);
        binding.mortgagePayment.setText(first == null ? "Chưa có lịch trả góp"
                : formatMoney(first.getLong("totalMinor"), currentCurrency) + " / kỳ");
        binding.mortgagePayment.setOnClickListener(null);
        renderMortgageSchedule();
    }

    private void renderMortgageSchedule() {
        if (binding == null) return;
        LinearLayout container = binding.mortgageScheduleContainer;
        container.removeAllViews();
        if (scheduleItems == null || scheduleItems.length() == 0) {
            addMessage(container, "Chưa có kỳ trả góp trong lịch dự kiến.");
            binding.mortgageLoadMore.setVisibility(View.GONE);
            return;
        }

        int count = Math.min(scheduleItems.length(), visibleScheduleItems);
        for (int i = 0; i < count; i++) {
            try {
                JSONObject item = scheduleItems.getJSONObject(i);
                addScheduleRow(container, item);
                if (i < count - 1) addDivider(container);
            } catch (JSONException ignored) {
                // Ignore an invalid row while preserving the rest of the schedule.
            }
        }
        boolean hasMore = count < scheduleItems.length();
        binding.mortgageLoadMore.setVisibility(hasMore ? View.VISIBLE : View.GONE);
        if (hasMore) binding.mortgageLoadMore.setText("Xem thêm kỳ (" + (scheduleItems.length() - count) + " còn lại)");
    }

    private void addScheduleRow(LinearLayout container, JSONObject item) throws JSONException {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(10), dp(12), dp(10), dp(12));

        TextView installment = new TextView(requireContext());
        String statusBadge = item.getInt("installment") == 1 ? " · Chờ thanh toán" : " · Chưa đến hạn";
        installment.setText("Kỳ " + item.getInt("installment") + statusBadge);
        installment.setTextColor(requireContext().getColor(R.color.ink));
        installment.setTextSize(14);
        installment.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        row.addView(installment);

        LinearLayout amounts = new LinearLayout(requireContext());
        amounts.setGravity(android.view.Gravity.CENTER_VERTICAL);
        amounts.setOrientation(LinearLayout.HORIZONTAL);
        amounts.setPadding(0, dp(5), 0, 0);
        TextView breakdown = new TextView(requireContext());
        breakdown.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        breakdown.setText("Gốc " + formatMoney(item.getLong("principalMinor"), currentCurrency)
                + " · Lãi " + formatMoney(item.getLong("interestMinor"), currentCurrency));
        breakdown.setTextColor(requireContext().getColor(R.color.ink_muted));
        breakdown.setTextSize(11);
        TextView total = new TextView(requireContext());
        total.setText(formatMoney(item.getLong("totalMinor"), currentCurrency));
        total.setTextColor(requireContext().getColor(R.color.navy));
        total.setTextSize(13);
        total.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        amounts.addView(breakdown);
        amounts.addView(total);
        row.addView(amounts);

        TextView remaining = new TextView(requireContext());
        remaining.setPadding(0, dp(5), 0, 0);
        remaining.setText("Dư nợ dự kiến sau kỳ này · "
                + formatMoney(item.getLong("remainingMinor"), currentCurrency));
        remaining.setTextColor(requireContext().getColor(R.color.ink_muted));
        remaining.setTextSize(11);
        row.addView(remaining);
        container.addView(row);
    }

    private void loadAccountHistory(String accountId) {
        if (loadedHistoryFor.equals(accountId)) return;
        loadedHistoryFor = accountId;
        binding.historyContainer.removeAllViews();
        addMessage(binding.historyContainer, "Đang tải giao dịch…");
        FragmentAccountDetailBinding current = binding;
        String path = "/transactions?accountId=" + android.net.Uri.encode(accountId) + "&limit=5";
        FinalTermApplication app = (FinalTermApplication) requireActivity().getApplication();
        app.getAppContainer().getBackendApiClient().get(path).addOnCompleteListener(task -> {
            if (binding != current || !loadedHistoryFor.equals(accountId)) return;
            binding.historyContainer.removeAllViews();
            try {
                if (!task.isSuccessful() || task.getResult().statusCode != 200)
                    throw new JSONException("Unavailable");
                JSONArray rows = new JSONObject(task.getResult().body).getJSONArray("items");
                if (rows.length() == 0) {
                    addMessage(binding.historyContainer, "Chưa có giao dịch cho tài khoản này.");
                    return;
                }
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject tx = rows.getJSONObject(i);
                    addTransactionRow(binding.historyContainer, tx);
                    if (i < rows.length() - 1) addDivider(binding.historyContainer);
                }
            } catch (JSONException error) {
                addMessage(binding.historyContainer, "Chưa tải được lịch sử giao dịch.");
            }
        });
    }

    private void addTransactionRow(LinearLayout container, JSONObject tx) throws JSONException {
        String type = tx.optString("type", "TRANSACTION");
        String status = transactionStatus(tx.optString("status", ""));
        String currency = tx.optString("currency", currentCurrency);
        long amount = tx.optLong("amountMinor");
        boolean credit = "TRANSFER_RECEIVED".equals(type);
        LinearLayout row = new LinearLayout(requireContext());
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(12), dp(4), dp(12));

        LinearLayout labels = new LinearLayout(requireContext());
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView title = new TextView(requireContext());
        title.setText(transactionTitle(type));
        title.setTextColor(requireContext().getColor(R.color.ink));
        title.setTextSize(13);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        TextView meta = new TextView(requireContext());
        meta.setText(transactionDate(tx) + " · " + status);
        meta.setTextColor(requireContext().getColor(R.color.ink_muted));
        meta.setTextSize(11);
        labels.addView(title);
        labels.addView(meta);

        TextView amountView = new TextView(requireContext());
        amountView.setText((credit ? "+" : "−") + formatMoney(amount, currency));
        amountView.setTextColor(requireContext().getColor(credit ? R.color.success : R.color.ink));
        amountView.setTextSize(12);
        amountView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        row.addView(labels);
        row.addView(amountView);
        container.addView(row);
    }

    private String transactionTitle(String type) {
        switch (type) {
            case "TRANSFER_RECEIVED": return "Tiền chuyển đến";
            case "INTERNAL_TRANSFER": return "Chuyển khoản";
            case "UTILITY_PAYMENT": return "Thanh toán tiện ích";
            case "SAVINGS_INTEREST": return "Lãi tiết kiệm";
            case "MORTGAGE_PAYMENT": return "Thanh toán khoản vay";
            case "DEMO_DEPOSIT": case "TOP_UP": case "STRIPE_CREDIT": return "Nạp tiền";
            case "DEMO_WITHDRAWAL": case "STRIPE_DEBIT": return "Rút tiền";
            case "TRANSFER_SENT": case "DEMO_EXTERNAL_TRANSFER": return "Chuyển tiền";
            default: return type.replace('_', ' ');
        }
    }

    private String transactionStatus(String status) {
        if ("SETTLED".equals(status) || "DEMO_SETTLED".equals(status)) return "Thành công";
        if ("PENDING".equals(status)) return "Đang xử lý";
        if ("FAILED".equals(status)) return "Thất bại";
        return "Đang cập nhật";
    }

    private String transactionDate(JSONObject tx) {
        long timestamp = tx.optLong("committedAt");
        if (timestamp <= 0) return "Chưa có thời gian";
        return java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT,
                Locale.forLanguageTag("vi-VN")).format(new Date(timestamp));
    }

    private void addMessage(LinearLayout container, String message) {
        TextView text = new TextView(requireContext());
        text.setText(message);
        text.setTextColor(requireContext().getColor(R.color.ink_muted));
        text.setTextSize(13);
        text.setPadding(dp(8), dp(14), dp(8), dp(14));
        container.addView(text);
    }

    private void addDivider(LinearLayout container) {
        View divider = new View(requireContext());
        divider.setBackgroundColor(requireContext().getColor(R.color.glass_border_subtle));
        container.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
    }

    private void shareAccount() {
        String typeLabel = Phase2Presentation.accountTitle(currentAccountType);
        String info = "Tài khoản Nimbus Bank\nLoại: " + typeLabel
                + (currentBalance == null ? "" : "\n" + ("MORTGAGE".equals(currentAccountType)
                ? "Dư nợ: " : "Số dư: ") + formatMoney(currentBalance, currentCurrency));
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, info);
        startActivity(Intent.createChooser(share, "Chia sẻ thông tin tài khoản"));
    }

    private String formatRate(int basisPoints) {
        NumberFormat percent = NumberFormat.getPercentInstance(Locale.forLanguageTag("vi-VN"));
        percent.setMaximumFractionDigits(2);
        return percent.format(basisPoints / 10000.0) + "/năm";
    }

    private String date(JSONObject data, String field) {
        if (data.isNull(field)) return "Chưa có dữ liệu";
        long value = data.optLong(field);
        return java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM,
                Locale.forLanguageTag("vi-VN")).format(new Date(value));
    }

    private String formatMoney(long amount, String currency) {
        return ReadSummaryFormatter.formatCurrency(amount, currency);
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

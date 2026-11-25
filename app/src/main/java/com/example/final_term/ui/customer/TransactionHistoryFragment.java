package com.example.final_term.ui.customer;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.TextView;

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
import com.example.final_term.databinding.FragmentTransactionHistoryBinding;
import com.example.final_term.databinding.ItemTransactionRowBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.google.firebase.Timestamp;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class TransactionHistoryFragment extends Fragment {
    private FragmentTransactionHistoryBinding binding;
    private FinalTermApplication application;
    private String uid;
    private String accountFilter = "";
    private UserRole role;
    private List<ReadModels.TransactionSummary> rows;
    private String baseStatus = "";
    private boolean thisMonth;
    private String nextCursor;
    private boolean pageLoading;
    private boolean pageError;
    private int loadGeneration;
    private androidx.lifecycle.LiveData<ReadModelResult<List<ReadModels.TransactionSummary>>> history;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentTransactionHistoryBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        application = (FinalTermApplication) requireActivity().getApplication();
        thisMonth = state != null ? state.getBoolean("thisMonth")
                : getArguments() != null && getArguments().getBoolean("thisMonth");
        accountFilter = getArguments() == null ? "" : getArguments().getString("accountId", "");

        binding.backButton.setOnClickListener(v -> Navigation.findNavController(v).navigateUp());
        binding.retryButton.setOnClickListener(v -> load());
        binding.loadMoreButton.setOnClickListener(v -> loadPage(nextCursor));

        binding.filterIconBtn.setOnClickListener(v -> {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Thời gian giao dịch")
                    .setSingleChoiceItems(new String[]{"Mọi thời điểm", "Tháng này"}, thisMonth ? 1 : 0,
                            (dialog, which) -> {
                                thisMonth = which == 1;
                                dialog.dismiss();
                                if (role == UserRole.CUSTOMER) load();
                                else renderFiltered();
                            })
                    .show();
        });

        binding.typeFilter.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Tất cả giao dịch", "Tiền vào", "Tiền ra"}));
        binding.statusFilter.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Mọi trạng thái", "Hoàn tất", "Đang xử lý", "Thất bại"}));

        TextWatcher watcher = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            public void onTextChanged(CharSequence s, int st, int b, int c) { renderFiltered(); }
            public void afterTextChanged(Editable e) { }
        };
        binding.searchInput.addTextChangedListener(watcher);

        AdapterView.OnItemSelectedListener selected = new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { renderFiltered(); }
            public void onNothingSelected(AdapterView<?> p) { }
        };
        binding.typeFilter.setOnItemSelectedListener(selected);
        binding.statusFilter.setOnItemSelectedListener(selected);
        binding.chipHistAll.setOnClickListener(v -> binding.typeFilter.setSelection(0));
        binding.chipHistIn.setOnClickListener(v -> binding.typeFilter.setSelection(1));
        binding.chipHistOut.setOnClickListener(v -> binding.typeFilter.setSelection(2));

        application.getAppContainer().getAuthRepository().observeAuthState()
                .observe(getViewLifecycleOwner(), authState -> {
                    if (authState != null && authState.getStatus() == AuthState.Status.SIGNED_IN && authState.getUser() != null) {
                        uid = authState.getUser().getUid();
                        role = authState.getUser().getRole();
                        load();
                    }
                });
    }

    private void load() {
        if (uid == null) return;
        if (history != null) {
            history.removeObservers(getViewLifecycleOwner());
            history = null;
        }
        if (role == UserRole.CUSTOMER) {
            loadPage(null);
            loadSummary();
        } else {
            FirestoreReadRepository repository = application.getAppContainer().getReadRepository();
            history = repository.observeAllTransactionsForOfficer();
            history.observe(getViewLifecycleOwner(), this::render);
        }
    }

    private void loadSummary() {
        if (application == null || binding == null) return;
        var current = binding;
        application.getAppContainer().getBackendApiClient().get("/transactions/summary")
                .addOnCompleteListener(task -> {
                    if (binding != current || !task.isSuccessful() || task.getResult() == null
                            || task.getResult().statusCode != 200) return;
                    try {
                        JSONObject s = new JSONObject(task.getResult().body);
                        long inMinor = s.optLong("totalInMinor", 0L);
                        long outMinor = s.optLong("totalOutMinor", 0L);
                        binding.summaryInText.setText("+" + ReadSummaryFormatter.formatCurrency(inMinor, "VND"));
                        binding.summaryOutText.setText("−" + ReadSummaryFormatter.formatCurrency(outMinor, "VND"));
                    } catch (Exception ignored) { }
                });
    }

    private void loadPage(String cursor) {
        if (uid == null || binding == null || (cursor != null && pageLoading)) return;
        int generation = ++loadGeneration;
        var current = binding;
        pageLoading = true;
        pageError = false;
        if (cursor == null) {
            nextCursor = null;
            rows = null;
        }
        baseStatus = "Đang tải lịch sử…";
        updatePageControls();
        renderFiltered();
        String path = "/transactions?limit=25";
        if (!accountFilter.isEmpty()) path += "&accountId=" + android.net.Uri.encode(accountFilter);
        if (cursor != null) path += "&cursor=" + android.net.Uri.encode(cursor);
        if (thisMonth) {
            var zone = java.time.ZoneId.systemDefault();
            var month = java.time.YearMonth.now(zone);
            path += "&from=" + month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    + "&to=" + (month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1);
        }
        application.getAppContainer().getBackendApiClient().get(path).addOnCompleteListener(task -> {
            if (binding != current || generation != loadGeneration) return;
            pageLoading = false;
            try {
                if (!task.isSuccessful() || task.getResult().statusCode != 200)
                    throw new JSONException("Unavailable");
                JSONObject page = new JSONObject(task.getResult().body);
                JSONArray items = page.getJSONArray("items");
                List<ReadModels.TransactionSummary> loaded = rows == null ? new ArrayList<>() : new ArrayList<>(rows);
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    var time = item.isNull("committedAt") ? null : new Timestamp(new java.util.Date(item.getLong("committedAt")));
                    loaded.add(new ReadModels.TransactionSummary(item.getString("id"), item.optString("accountId"),
                            item.getString("type"), item.optString("currency"), item.getLong("amountMinor"),
                            item.getString("status"), time,
                            item.isNull("failureCode") ? null : item.getString("failureCode"),
                            item.isNull("balanceAfterMinor") ? null : item.getLong("balanceAfterMinor"),
                            item.optString("destinationBankCode", "")));
                }
                nextCursor = page.isNull("nextCursor") ? null : page.getString("nextCursor");
                rows = loaded;
                baseStatus = rows.isEmpty() ? "Chưa có giao dịch." : "Lịch sử giao dịch";
            } catch (JSONException error) {
                pageError = true;
                baseStatus = cursor == null ? "Không tải được lịch sử." : "Không tải được trang sau. Các dòng cũ có thể đã thay đổi.";
                if (!task.isSuccessful() && cursor == null) showOfflineHistory(generation);
            }
            updatePageControls();
            renderFiltered();
        });
    }

    private void showOfflineHistory(int generation) {
        history = application.getAppContainer().getReadRepository().observeTransactions(uid);
        history.observe(getViewLifecycleOwner(), result -> {
            if (generation != loadGeneration || binding == null || result.getData() == null) return;
            rows = new ArrayList<>();
            rows.addAll(result.getData());
            baseStatus = "Đang hiển thị dữ liệu đã lưu trên thiết bị. Vuốt xuống để thử tải lại.";
            renderFiltered();
        });
    }

    private void updatePageControls() {
        binding.retryButton.setVisibility(pageError && nextCursor == null ? View.VISIBLE : View.GONE);
        binding.loadMoreButton.setVisibility(nextCursor == null ? View.GONE : View.VISIBLE);
        binding.loadMoreButton.setEnabled(!pageLoading);
        binding.loadMoreButton.setText(pageError ? "Thử tải lại trang" : "Tải thêm giao dịch");
    }

    private void render(ReadModelResult<List<ReadModels.TransactionSummary>> result) {
        if (result == null) return;
        baseStatus = statusMessage(result);
        binding.statusText.setText(baseStatus);
        binding.retryButton.setVisibility(result.getStatus() == ReadModelResult.Status.ERROR ? View.VISIBLE : View.GONE);
        rows = result.getData();
        renderFiltered();
    }

    private void renderFiltered() {
        if (binding == null) return;
        binding.content.removeAllViews();
        if (rows == null) { binding.statusText.setText(baseStatus); return; }

        String query = binding.searchInput.getText() == null ? "" : binding.searchInput.getText().toString();
        int typePosition = binding.typeFilter.getSelectedItemPosition();
        int statusPosition = binding.statusFilter.getSelectedItemPosition();
        String type = typePosition == 1 ? "CREDIT" : typePosition == 2 ? "DEBIT" : "ALL";
        String status = statusPosition == 1 ? "COMPLETED" : statusPosition == 2 ? "PROCESSING" : statusPosition == 3 ? "FAILED" : "ALL";
        Long from = null, to = null;
        if (thisMonth) {
            var zone = java.time.ZoneId.systemDefault();
            from = java.time.YearMonth.now(zone).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli();
            to = java.time.YearMonth.now(zone).plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1;
        }
        List<ReadModels.TransactionSummary> filtered = TransactionHistoryFilter.apply(rows, query, type, status, accountFilter, from, to);
        binding.statusText.setText(baseStatus + (thisMonth ? " · Tháng này" : "") + " · " + filtered.size() + " kết quả đã tải");

        for (ReadModels.TransactionSummary tx : filtered) {
            ItemTransactionRowBinding rowBinding = ItemTransactionRowBinding.inflate(
                    getLayoutInflater(), binding.content, false);

            boolean isIncoming = tx.isIncoming();
            rowBinding.titleText.setText(ReadSummaryFormatter.transactionTitle(tx));
            rowBinding.subtitleText.setText(tx.createdAt == null ? ReadSummaryFormatter.formatRelativeTime(0L, 0)
                    : java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT,
                    java.util.Locale.forLanguageTag("vi-VN")).format(tx.createdAt.toDate()));
            rowBinding.transactionIcon.setVisibility(View.GONE);
            rowBinding.avatarInitials.setVisibility(View.VISIBLE);
            rowBinding.avatarInitials.setText(ReadSummaryFormatter.initialsForTransaction(tx));

            String amountFormatted = ReadSummaryFormatter.formatCurrency(tx.amountMinor, tx.currency);
            if (isIncoming) {
                rowBinding.amountText.setText("+" + amountFormatted);
                rowBinding.amountText.setTextColor(ContextCompat.getColor(requireContext(), R.color.success));
                rowBinding.avatarInitials.setTextColor(ContextCompat.getColor(requireContext(), R.color.success));
            } else {
                rowBinding.amountText.setText("−" + amountFormatted);
                rowBinding.amountText.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink));
                rowBinding.avatarInitials.setTextColor(ContextCompat.getColor(requireContext(), R.color.navy));
            }

            rowBinding.getRoot().setOnClickListener(v -> {
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Chi tiết giao dịch")
                        .setMessage(ReadSummaryFormatter.transactionTitle(tx) + "\n\n"
                                + ReadSummaryFormatter.transactionDetail(tx)
                                + "\nTài khoản: " + tx.accountId)
                        .setPositiveButton("Đóng", null)
                        .show();
            });

            binding.content.addView(rowBinding.getRoot());
        }

        if (filtered.isEmpty()) {
            TextView empty = new TextView(requireContext());
            empty.setText(nextCursor == null ? "Chưa có giao dịch phù hợp bộ lọc."
                    : "Trang đã tải chưa có kết quả phù hợp. Bấm tải thêm để xem trang sau.");
            empty.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink_muted));
            empty.setPadding(0, 24, 0, 24);
            empty.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            binding.content.addView(empty);
        }
    }

    private String statusMessage(ReadModelResult<?> result) {
        switch (result.getStatus()) {
            case LOADING: return "Đang tải lịch sử…";
            case CACHED: return "Dữ liệu có thể chưa cập nhật.";
            case ERROR: return "Chưa thể tải lịch sử giao dịch. Vuốt xuống để thử lại.";
            case EMPTY: return "Không có giao dịch.";
            default: return "Lịch sử đã đồng bộ.";
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("thisMonth", thisMonth);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}

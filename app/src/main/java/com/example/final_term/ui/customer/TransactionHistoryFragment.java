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

import java.util.List;

public final class TransactionHistoryFragment extends Fragment {
    private FragmentTransactionHistoryBinding binding;
    private FinalTermApplication application;
    private String uid;
    private UserRole role;
    private List<ReadModels.TransactionSummary> rows;
    private String baseStatus = "";

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

        binding.backButton.setOnClickListener(v -> Navigation.findNavController(v).navigateUp());
        binding.retryButton.setOnClickListener(v -> load());

        binding.filterIconBtn.setOnClickListener(v -> {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Bộ lọc giao dịch")
                    .setMessage("Bạn có thể lọc theo loại giao dịch (Tất cả, Tiền vào, Tiền ra) và trạng thái (Thành công, Đang chờ, Thất bại) bằng thanh chọn nhanh phía trên.")
                    .setPositiveButton("Đã hiểu", null)
                    .show();
        });

        binding.typeFilter.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item,
                new String[]{"ALL", "CREDIT", "DEBIT"}));
        binding.statusFilter.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item,
                new String[]{"ALL", "SETTLED", "PENDING", "FAILED"}));

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
        FirestoreReadRepository repository = application.getAppContainer().getReadRepository();
        if (role == UserRole.OFFICER) {
            repository.observeAllTransactionsForOfficer().observe(getViewLifecycleOwner(), this::render);
        } else {
            repository.observeTransactions(uid).observe(getViewLifecycleOwner(), this::render);
        }
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
        if (rows == null) return;

        String query = binding.searchInput.getText() == null ? "" : binding.searchInput.getText().toString();
        String type = binding.typeFilter.getSelectedItem() == null ? "ALL" : binding.typeFilter.getSelectedItem().toString();
        String status = binding.statusFilter.getSelectedItem() == null ? "ALL" : binding.statusFilter.getSelectedItem().toString();

        List<ReadModels.TransactionSummary> filtered = TransactionHistoryFilter.apply(rows, query, type, status);
        binding.statusText.setText(baseStatus + " · " + filtered.size() + " kết quả");

        for (ReadModels.TransactionSummary tx : filtered) {
            ItemTransactionRowBinding rowBinding = ItemTransactionRowBinding.inflate(
                    getLayoutInflater(), binding.content, false);

            boolean isIncoming = tx.isIncoming();
            rowBinding.titleText.setText(tx.type != null ? tx.type : (isIncoming ? "Nhận tiền" : "Chuyển tiền"));
            rowBinding.subtitleText.setText("SETTLED".equals(tx.status) ? "Thành công · " + tx.id : tx.status + " · " + tx.id);

            String amountFormatted = ReadSummaryFormatter.formatCurrency(tx.amountMinor, tx.currency);
            if (isIncoming) {
                rowBinding.amountText.setText("+" + amountFormatted);
                rowBinding.amountText.setTextColor(ContextCompat.getColor(requireContext(), R.color.success));
                rowBinding.transactionIcon.setImageResource(R.drawable.ic_trending_up);
                rowBinding.transactionIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.success));
            } else {
                rowBinding.amountText.setText("−" + amountFormatted);
                rowBinding.amountText.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink));
                rowBinding.transactionIcon.setImageResource(R.drawable.ic_nav_send);
                rowBinding.transactionIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.navy));
            }

            rowBinding.getRoot().setOnClickListener(v -> {
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Chi tiết giao dịch")
                        .setMessage(ReadSummaryFormatter.transactionTitle(tx) + "\n\n"
                                + ReadSummaryFormatter.transactionDetail(tx)
                                + "\nMã giao dịch: " + tx.id
                                + "\nTài khoản: " + tx.accountId)
                        .setPositiveButton("Đóng", null)
                        .show();
            });

            binding.content.addView(rowBinding.getRoot());
        }

        if (filtered.isEmpty()) {
            TextView empty = new TextView(requireContext());
            empty.setText("Chưa có giao dịch phù hợp bộ lọc.\nLịch sử sẽ xuất hiện khi phát sinh giao dịch mới.");
            empty.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink_muted));
            empty.setPadding(0, 24, 0, 24);
            empty.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            binding.content.addView(empty);
        }
    }

    private String statusMessage(ReadModelResult<?> result) {
        switch (result.getStatus()) {
            case LOADING: return "Đang tải lịch sử…";
            case CACHED: return result.getMessage();
            case ERROR: return result.getMessage();
            case EMPTY: return "Không có giao dịch.";
            default: return "Lịch sử đã đồng bộ.";
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}

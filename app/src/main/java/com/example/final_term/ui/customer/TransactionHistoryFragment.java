package com.example.final_term.ui.customer;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.final_term.R;
import com.example.final_term.auth.AuthState;
import com.example.final_term.auth.UserRole;
import com.example.final_term.core.FinalTermApplication;
import com.example.final_term.data.FirestoreReadRepository;
import com.example.final_term.data.ReadModelResult;
import com.example.final_term.data.ReadModels;
import com.example.final_term.data.ReadSummaryFormatter;
import com.example.final_term.databinding.FragmentTransactionHistoryBinding;
import androidx.navigation.Navigation;

import java.util.List;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;

public final class TransactionHistoryFragment extends Fragment {
    private FragmentTransactionHistoryBinding binding;
    private FinalTermApplication application;
    private String uid;
    private UserRole role;
    private List<ReadModels.TransactionSummary> rows;
    private String baseStatus = "";

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentTransactionHistoryBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        application = (FinalTermApplication) requireActivity().getApplication();
        binding.retryButton.setOnClickListener(v -> load());
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
        rows = result.getData();
        renderFiltered();
    }

    private void renderFiltered() {
        binding.content.removeAllViews();
        if (rows == null) return;
        String query = binding.searchInput.getText() == null ? "" : binding.searchInput.getText().toString();
        String type = binding.typeFilter.getSelectedItem() == null ? "ALL" : binding.typeFilter.getSelectedItem().toString();
        String status = binding.statusFilter.getSelectedItem() == null ? "ALL" : binding.statusFilter.getSelectedItem().toString();
        List<ReadModels.TransactionSummary> filtered = TransactionHistoryFilter.apply(rows, query, type, status);
        binding.statusText.setText(baseStatus + " · " + filtered.size() + " kết quả");
        for (ReadModels.TransactionSummary transaction : filtered) {
            addLine(ReadSummaryFormatter.transactionTitle(transaction),
                    ReadSummaryFormatter.transactionDetail(transaction));
        }
        if (filtered.isEmpty()) {
            addLine("Chưa có giao dịch", "Lịch sử sẽ được hiển thị khi Firestore có dữ liệu.");
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

    private void addLine(String title, String detail) {
        TextView line = new TextView(requireContext());
        line.setText(title + "\n" + detail);
        line.setTextColor(requireContext().getColor(R.color.ink));
        line.setTextSize(16);
        line.setPadding(0, 18, 0, 18);
        binding.content.addView(line);
    }

    @Override public void onDestroyView() { super.onDestroyView(); binding = null; }
}

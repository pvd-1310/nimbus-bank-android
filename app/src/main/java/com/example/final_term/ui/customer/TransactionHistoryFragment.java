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
import com.example.final_term.databinding.FragmentTransactionHistoryBinding;
import com.example.final_term.navigation.BottomNavigationBinder;
import androidx.navigation.Navigation;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

public final class TransactionHistoryFragment extends Fragment {
    private FragmentTransactionHistoryBinding binding;
    private FinalTermApplication application;
    private String uid;
    private UserRole role;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentTransactionHistoryBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        application = (FinalTermApplication) requireActivity().getApplication();
        binding.retryButton.setOnClickListener(v -> load());
        application.getAppContainer().getAuthRepository().observeAuthState()
                .observe(getViewLifecycleOwner(), authState -> {
                    if (authState != null && authState.getStatus() == AuthState.Status.SIGNED_IN && authState.getUser() != null) {
                        uid = authState.getUser().getUid();
                        role = authState.getUser().getRole();
                        BottomNavigationBinder.bind(binding.bottomNavigation.getRoot(), Navigation.findNavController(view),
                                role == UserRole.OFFICER ? R.id.officerHome : R.id.customerHome, R.id.accountList);
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
        binding.statusText.setText(statusMessage(result));
        binding.content.removeAllViews();
        if (result.getData() == null) return;
        for (ReadModels.TransactionSummary transaction : result.getData()) {
            addLine(transaction.type + "  •  " + transaction.status,
                    formatMoney(transaction.amountMinor, transaction.currency));
        }
        if (result.getStatus() == ReadModelResult.Status.EMPTY) {
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

    private String formatMoney(long amountMinor, String currency) {
        return NumberFormat.getIntegerInstance(new Locale("vi", "VN")).format(amountMinor)
                + " " + (currency == null ? "" : currency);
    }

    @Override public void onDestroyView() { super.onDestroyView(); binding = null; }
}

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
import com.example.final_term.databinding.FragmentAccountListBinding;
import com.example.final_term.navigation.BottomNavigationBinder;
import androidx.navigation.Navigation;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

public final class AccountListFragment extends Fragment {
    private FragmentAccountListBinding binding;
    private FinalTermApplication application;
    private String uid;
    private UserRole role;
    private List<ReadModels.AccountSummary> accounts;
    private List<ReadModels.NotificationSummary> notifications;
    private ReadModelResult.Status accountStatus;
    private String accountMessage;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentAccountListBinding.inflate(inflater, container, false);
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
            repository.observeAllAccountsForOfficer().observe(getViewLifecycleOwner(), this::renderAccounts);
        } else {
            repository.observeAccounts(uid).observe(getViewLifecycleOwner(), this::renderAccounts);
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

    private void renderNotifications(ReadModelResult<List<ReadModels.NotificationSummary>> result) {
        if (result == null) return;
        notifications = result.getData();
        renderAll();
    }

    private void renderAll() {
        binding.statusText.setText(accountStatus == null ? "Đang tải dữ liệu…" : accountStatusMessage());
        binding.content.removeAllViews();
        if (accounts != null) {
            for (ReadModels.AccountSummary account : accounts) {
                addLine(account.type + "  •  " + account.status,
                        formatMoney(account.balanceMinor, account.currency));
            }
            if (accounts.isEmpty()) addLine("Chưa có tài khoản", "Dữ liệu sẽ xuất hiện sau khi seed Firestore.");
        }
        addLine("Thông báo", "");
        if (notifications == null || notifications.isEmpty()) {
            addLine("Chưa có thông báo", "");
        } else {
            for (ReadModels.NotificationSummary notification : notifications) {
                addLine((notification.read ? "Đã đọc: " : "Mới: ") + notification.title, notification.body);
            }
        }
    }

    private String accountStatusMessage() {
        switch (accountStatus) {
            case CACHED: return accountMessage;
            case ERROR: return accountMessage;
            case EMPTY: return "Không có dữ liệu tài khoản.";
            case LOADING: return "Đang tải dữ liệu…";
            default: return "Dữ liệu tài khoản đã đồng bộ.";
        }
    }

    private String statusMessage(ReadModelResult<?> result) {
        switch (result.getStatus()) {
            case LOADING: return "Đang tải dữ liệu…";
            case CACHED: return result.getMessage();
            case ERROR: return result.getMessage();
            case EMPTY: return "Không có dữ liệu tài khoản.";
            default: return "Dữ liệu tài khoản đã đồng bộ.";
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

package com.example.final_term.ui.customer;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
import com.example.final_term.data.AvatarCache;
import com.example.final_term.data.FirestoreReadRepository;
import com.example.final_term.data.ReadModelResult;
import com.example.final_term.data.ReadModels;
import com.example.final_term.data.ReadSummaryFormatter;
import com.example.final_term.databinding.FragmentCustomerHomeBinding;
import com.example.final_term.databinding.ItemTransactionRowBinding;
import com.example.final_term.ui.quickactions.QuickActionsBottomSheet;
import com.example.final_term.ui.common.ThemedDialogBuilder;

import java.util.ArrayList;
import java.util.List;

public final class CustomerHomeFragment extends Fragment {
    private FragmentCustomerHomeBinding binding;
    private final List<ReadModels.AccountSummary> balanceAccounts = new ArrayList<>();
    private String selectedAccountId;
    private String currentBalanceCurrency = "VND";
    private boolean isBalanceHidden = false;
    private Long currentBalanceMinor = null;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentCustomerHomeBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        if (state != null) {
            selectedAccountId = state.getString("balanceAccountId");
            isBalanceHidden = state.getBoolean("isBalanceHidden", false);
        }

        binding.toggleBalanceButton.setOnClickListener(v -> {
            isBalanceHidden = !isBalanceHidden;
            updateBalanceDisplay();
        });

        binding.accountDetailsArrowBtn.setOnClickListener(v -> {
            if (selectedAccountId != null && !balanceAccounts.isEmpty()) {
                Bundle args = new Bundle();
                args.putString("accountId", selectedAccountId);
                ReadModels.AccountSummary acc = getSelectedAccount();
                args.putString("accountType", acc != null ? acc.type : "CHECKING");
                Navigation.findNavController(v).navigate(R.id.accountDetail, args);
            } else {
                Navigation.findNavController(v).navigate(R.id.accountList);
            }
        });

        FinalTermApplication app = (FinalTermApplication) requireActivity().getApplication();
        app.getAppContainer().getAuthRepository().observeAuthState().observe(getViewLifecycleOwner(), authState -> {
            if (authState == null || authState.getStatus() != AuthState.Status.SIGNED_IN
                    || authState.getUser() == null || authState.getUser().getRole() != UserRole.CUSTOMER) {
                requireActivity().getOnBackPressedDispatcher().onBackPressed();
                return;
            }
            binding.roleText.setText(getString(R.string.role_customer));
            String fullName = authState.getUser().getFullName();
            String displayName = (fullName == null || fullName.trim().isEmpty())
                    ? authState.getUser().getEmail() : fullName;
            binding.identityText.setText(displayName);

            // Compute Initials for Avatar
            String initials = computeInitials(displayName);
            binding.avatarText.setText(initials);
            android.graphics.Bitmap cachedAvatar = AvatarCache.load(requireContext(), authState.getUser().getUid());
            if (cachedAvatar == null) {
                binding.avatarImage.setVisibility(View.GONE);
                binding.avatarText.setVisibility(View.VISIBLE);
            } else {
                binding.avatarImage.setImageBitmap(cachedAvatar);
                binding.avatarImage.setVisibility(View.VISIBLE);
                binding.avatarText.setVisibility(View.GONE);
            }

            FirestoreReadRepository reads = app.getAppContainer().getReadRepository();
            reads.observeAccounts(authState.getUser().getUid()).observe(getViewLifecycleOwner(), this::renderAccounts);
            reads.observeTransactions(authState.getUser().getUid()).observe(getViewLifecycleOwner(), this::renderTransactions);

        });

        // Navigation CTA handlers
        binding.accountButton.setOnClickListener(v -> {
            Bundle args = new Bundle();
            args.putInt("activeNavItem", R.id.nav_accounts);
            navigateToAccounts(v, args);
        });
        binding.historyButton.setOnClickListener(v -> Navigation.findNavController(v).navigate(R.id.transactionHistory));
        binding.branchLocatorButton.setOnClickListener(v -> Navigation.findNavController(v).navigate(R.id.branchLocator));

        // Quick action round buttons
        binding.quickTransfer.setOnClickListener(v -> Navigation.findNavController(v).navigate(R.id.transfer));
        binding.quickDeposit.setOnClickListener(v -> {
            Bundle args = new Bundle();
            args.putString("operation", "DEPOSIT");
            Navigation.findNavController(v).navigate(R.id.moneyRequest, args);
        });

        binding.quickPayment.setOnClickListener(v -> {
            Bundle arguments = new Bundle();
            ReadModels.AccountSummary account = getSelectedAccount();
            if (account != null && "CHECKING".equals(account.type))
                arguments.putString("sourceAccountId", account.id);
            Navigation.findNavController(v).navigate(R.id.utilitiesHub, arguments);
        });

        binding.quickQr.setOnClickListener(v -> {
            new ThemedDialogBuilder(requireContext())
                    .setTitle("Quét mã QR")
                    .setMessage("Tính năng này đang được hoàn thiện.")
                    .setPositiveButton("Đóng", null)
                    .show();
        });

        binding.notificationButton.setOnClickListener(v ->
                new ChatbotDialogFragment().show(getChildFragmentManager(), "Chatbot"));
    }

    private void navigateToAccounts(View source, Bundle args) {
        androidx.navigation.NavController controller = Navigation.findNavController(source);
        controller.navigate(R.id.accountList, args);
        androidx.navigation.NavBackStackEntry entry = controller.getCurrentBackStackEntry();
        if (entry != null && entry.getDestination().getId() == R.id.accountList) {
            entry.getSavedStateHandle().set("activeNavItem", R.id.nav_accounts);
            if (entry.getArguments() != null) {
                entry.getArguments().putInt("activeNavItem", R.id.nav_accounts);
            }
        }
    }

    private String computeInitials(String name) {
        if (name == null || name.trim().isEmpty()) return "NB";
        String[] parts = name.trim().split("\\s+");
        if (parts.length == 1) {
            return parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase();
        }
        return ("" + parts[0].charAt(0) + parts[parts.length - 1].charAt(0)).toUpperCase();
    }

    private ReadModels.AccountSummary getSelectedAccount() {
        for (ReadModels.AccountSummary acc : balanceAccounts) {
            if (acc.id.equals(selectedAccountId)) return acc;
        }
        return balanceAccounts.isEmpty() ? null : balanceAccounts.get(0);
    }

    private void renderAccounts(ReadModelResult<List<ReadModels.AccountSummary>> result) {
        if (result == null || binding == null) return;
        balanceAccounts.clear();
        if (result.getData() == null) {
            currentBalanceMinor = null;
            binding.accountStatusText.setVisibility(View.GONE);
            binding.balanceText.setText(result.getStatus() == ReadModelResult.Status.LOADING ? "Đang tải…" : "—");
            return;
        }
        for (ReadModels.AccountSummary account : result.getData()) {
            if ("ACTIVE".equalsIgnoreCase(account.status)) balanceAccounts.add(account);
        }
        balanceAccounts.sort((left, right) -> {
            int paymentFirst = Boolean.compare(!"CHECKING".equalsIgnoreCase(left.type),
                    !"CHECKING".equalsIgnoreCase(right.type));
            return paymentFirst != 0 ? paymentFirst : left.id.compareTo(right.id);
        });

        if (balanceAccounts.isEmpty()) {
            currentBalanceMinor = null;
            binding.balanceText.setText("—");
            binding.accountStatusText.setVisibility(View.GONE);
            return;
        }
        ReadModels.AccountSummary paymentAccount = null;
        for (ReadModels.AccountSummary account : balanceAccounts) {
            if ("CHECKING".equalsIgnoreCase(account.type)) {
                paymentAccount = account;
                break;
            }
        }
        if (paymentAccount == null) {
            selectedAccountId = null;
            currentBalanceMinor = null;
            binding.balanceText.setText("—");
            binding.accountStatusText.setVisibility(View.GONE);
            return;
        }
        selectedAccountId = paymentAccount.id;
        currentBalanceMinor = paymentAccount.availableMinor != null ? paymentAccount.availableMinor : paymentAccount.balanceMinor;
        currentBalanceCurrency = paymentAccount.currency;
        binding.accountStatusText.setVisibility(View.GONE);
        updateBalanceDisplay();
        var current = binding;
        ((FinalTermApplication) requireActivity().getApplication()).getAppContainer().getBackendApiClient()
                .get("/accounts/" + android.net.Uri.encode(paymentAccount.id) + "/limits")
                .addOnCompleteListener(task -> {
                    if (binding != current || !task.isSuccessful() || task.getResult() == null
                            || task.getResult().statusCode != 200) return;
                    try {
                        org.json.JSONObject lim = new org.json.JSONObject(task.getResult().body);
                        long limit = lim.optLong("dailyLimitMinor", 10_000_000L);
                        long used = lim.optLong("dailyUsedMinor", 0L);
                        long remaining = Math.max(0L, limit - used);
                        binding.transactionLimitPill.setText("ⓘ Hạn mức giao dịch · còn "
                                + ReadSummaryFormatter.formatCurrency(remaining, "VND"));
                    } catch (Exception ignored) { }
                });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (binding != null) updateBalanceDisplay();
    }

    private void updateBalanceDisplay() {
        if (binding == null) return;
        if (isBalanceHidden) {
            binding.balanceText.setText("••••••••");
            binding.toggleBalanceButton.setImageResource(R.drawable.ic_eye_slash);
        } else {
            binding.toggleBalanceButton.setImageResource(R.drawable.ic_eye);
            if (currentBalanceMinor == null) {
                binding.balanceText.setText("—");
            } else {
                binding.balanceText.setText(ReadSummaryFormatter.formatCurrency(currentBalanceMinor,
                        currentBalanceCurrency));
            }
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("balanceAccountId", selectedAccountId);
        state.putBoolean("isBalanceHidden", isBalanceHidden);
    }

    private void renderTransactions(ReadModelResult<List<ReadModels.TransactionSummary>> result) {
        if (result == null || result.getData() == null || binding == null) return;
        binding.recentTransactionsContent.removeAllViews();
        if (result.isReadOnly()) {
            TextView status = new TextView(requireContext());
            status.setText("Đang hiển thị dữ liệu đã lưu trên thiết bị.");
            binding.recentTransactionsContent.addView(status);
        }
        List<ReadModels.TransactionSummary> ledgerRows = result.getData();
        if (ledgerRows.isEmpty()) {
            TextView emptyText = new TextView(requireContext());
            emptyText.setText("Chưa có giao dịch gần đây.");
            emptyText.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink_muted));
            emptyText.setPadding(0, 16, 0, 16);
            binding.recentTransactionsContent.addView(emptyText);
            return;
        }
        // Take at most 4 recent transactions for Home preview
        int limit = Math.min(4, ledgerRows.size());
        for (int i = 0; i < limit; i++) {
            ReadModels.TransactionSummary tx = ledgerRows.get(i);
            ItemTransactionRowBinding rowBinding = ItemTransactionRowBinding.inflate(
                    getLayoutInflater(), binding.recentTransactionsContent, false);

            boolean isIncoming = tx.isIncoming();
            rowBinding.titleText.setText(ReadSummaryFormatter.transactionTitle(tx));
            long ts = tx.createdAt != null ? tx.createdAt.toDate().getTime() : 0L;
            rowBinding.subtitleText.setText(ReadSummaryFormatter.formatRelativeTime(ts, i));
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

            binding.recentTransactionsContent.addView(rowBinding.getRoot());
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}

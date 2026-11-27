package com.example.final_term.ui.customer;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.example.final_term.R;
import com.example.final_term.data.ReadModelResult;
import com.example.final_term.data.ReadModels.AccountSummary;
import com.example.final_term.data.ReadSummaryFormatter;
import com.example.final_term.databinding.ViewTransactionAccountBinding;
import com.example.final_term.ui.common.AccountSourcePresentation;
import com.example.final_term.ui.common.ThemedDialogBuilder;
import com.example.final_term.ui.transaction.TransactionPresentation;

import java.util.ArrayList;
import java.util.List;

final class TransactionAccountPicker {
    private final ViewTransactionAccountBinding binding;
    private final Context context;
    private final EditText amountInput;
    private final TextView amountWarning;
    private final Runnable onChange;
    private final Selection selection = new Selection();
    private String ownerName = "";
    private boolean deposit;
    private boolean editable = true;
    private AlertDialog dialog;

    TransactionAccountPicker(ViewTransactionAccountBinding binding, EditText amountInput,
            TextView amountWarning, @Nullable String selectedId, Runnable onChange) {
        this.binding = binding;
        this.context = binding.getRoot().getContext();
        this.amountInput = amountInput;
        this.amountWarning = amountWarning;
        this.onChange = onChange;
        selection.id = selectedId;
        binding.changeAccount.setContentDescription(context.getString(R.string.transaction_account_change_description));
        binding.changeAccount.setOnClickListener(view -> showOptions());
        amountInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                renderAmountWarning();
            }
            @Override public void afterTextChanged(Editable text) { }
        });
        render();
    }

    void setRole(boolean deposit) {
        this.deposit = deposit;
        binding.accountRole.setText(deposit ? R.string.transaction_account_deposit : R.string.source_account);
        binding.fundingMethod.setVisibility(deposit ? View.VISIBLE : View.GONE);
        dismiss();
        updateView();
    }

    void setEditable(boolean editable) {
        this.editable = editable;
        if (!editable) dismiss();
        binding.changeAccount.setEnabled(editable && selection.status == ReadModelResult.Status.SUCCESS);
    }

    void clear() {
        dismiss();
        ownerName = "";
        selection.id = null;
        setAccounts(ReadModelResult.loading());
    }

    void setOwnerName(@Nullable String fullName) {
        String name = fullName == null ? "" : fullName.trim();
        if (ownerName.equals(name)) return;
        ownerName = name;
        dismiss();
        render();
    }

    void setAccounts(ReadModelResult<List<AccountSummary>> result) {
        selection.update(result);
        updateView();
    }

    @Nullable String selectedId() {
        return selection.id;
    }

    @Nullable AccountSummary selectedAccount() {
        return selection.status == ReadModelResult.Status.SUCCESS ? selection.selected() : null;
    }

    boolean exceedsAvailable(long amount) {
        AccountSummary account = selectedAccount();
        return !deposit && account != null && account.availableMinor != null && amount > account.availableMinor;
    }

    String label(AccountSummary account) {
        if (!deposit) {
            String number = AccountSourcePresentation.number(context, account.id, account.accountNumber);
            return AccountSourcePresentation.identity(context, number, ownerName);
        }
        String type = context.getString(R.string.transaction_account_checking);
        return account.accountNumber.isEmpty()
                ? TransactionPresentation.maskAccountLabel(account.id, type)
                : context.getString(R.string.transaction_account_name, type, account.accountNumber);
    }

    private String balance(AccountSummary account) {
        if (account.availableMinor == null) {
            return context.getString(R.string.transaction_account_balance_unknown);
        }
        return ReadSummaryFormatter.formatCurrency(account.availableMinor, account.currency);
    }

    private void updateView() {
        render();
        onChange.run();
    }

    private void render() {
        AccountSummary account = selection.selected();
        if (account != null) {
            binding.accountName.setText(label(account));
        } else {
            int text = R.string.transaction_account_choose;
            if (selection.status == ReadModelResult.Status.LOADING) text = R.string.transaction_account_loading;
            else if (selection.status == ReadModelResult.Status.ERROR) text = R.string.transaction_account_load_error;
            else if (selection.accounts.isEmpty()) text = R.string.transaction_account_unavailable;
            binding.accountName.setText(text);
        }
        String amount = account != null && !deposit ? balance(account) : null;
        showText(binding.accountBalance, amount);
        binding.accountBalance.setContentDescription(amount == null ? null
                : account.availableMinor == null
                    ? context.getString(R.string.transaction_account_balance_unavailable_description)
                    : context.getString(R.string.transaction_account_available, amount));
        String status = null;
        if (selection.status == ReadModelResult.Status.CACHED) {
            status = context.getString(R.string.transaction_account_cached);
        } else if ("".equals(selection.id)) {
            status = context.getString(R.string.transaction_account_reselect);
        }
        showText(binding.accountStatus, status);
        boolean canChoose = selection.accounts.size() > 1 || (account == null && !selection.accounts.isEmpty());
        binding.changeAccount.setVisibility(canChoose ? View.VISIBLE : View.GONE);
        binding.changeAccount.setText(account == null ? R.string.transaction_account_choose : R.string.transaction_account_change);
        binding.changeAccount.setEnabled(editable && selection.status == ReadModelResult.Status.SUCCESS);
        renderAmountWarning();
    }

    private void renderAmountWarning() {
        String input = amountInput.getText().toString().trim();
        boolean insufficient = Phase2Presentation.validRequestAmount(input)
                && exceedsAvailable(Long.parseLong(input));
        amountWarning.setVisibility(insufficient ? View.VISIBLE : View.GONE);
    }

    private static void showText(TextView textView, @Nullable String text) {
        textView.setText(text);
        textView.setVisibility(text == null ? View.GONE : View.VISIBLE);
    }

    private void showOptions() {
        List<AccountSummary> options = selection.accounts;
        CharSequence[] labels = new CharSequence[options.size()];
        int checked = -1;
        for (int i = 0; i < options.size(); i++) {
            AccountSummary option = options.get(i);
            String name = label(option);
            labels[i] = deposit ? name : balance(option) + "\n" + name;
            if (option.id.equals(selection.id)) checked = i;
        }
        dialog = new ThemedDialogBuilder(context)
                .setTitle(R.string.transaction_account_choose)
                .setSingleChoiceItems(labels, checked, (choiceDialog, position) -> {
                    String chosenId = options.get(position).id;
                    if (editable && selection.status == ReadModelResult.Status.SUCCESS
                            && selection.accounts.stream().anyMatch(account -> account.id.equals(chosenId))) {
                        selection.id = chosenId;
                        updateView();
                    }
                    dismiss();
                })
                .show();
    }

    void dismiss() {
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
    }

    static final class Selection {
        // Null allows the initial default. Empty means the user must choose again.
        @Nullable String id;
        List<AccountSummary> accounts = List.of();
        ReadModelResult.Status status = ReadModelResult.Status.LOADING;

        void update(ReadModelResult<List<AccountSummary>> result) {
            status = result.getStatus();
            List<AccountSummary> eligible = new ArrayList<>();
            if (result.getData() != null) {
                for (AccountSummary account : result.getData()) {
                    if ("ACTIVE".equalsIgnoreCase(account.status)
                            && "CHECKING".equalsIgnoreCase(account.type)
                            && "VND".equalsIgnoreCase(account.currency)) {
                        eligible.add(account);
                    }
                }
            }
            accounts = eligible;
            if (status == ReadModelResult.Status.SUCCESS || status == ReadModelResult.Status.EMPTY) {
                if (id == null && !accounts.isEmpty()) id = accounts.get(0).id;
                else if (id != null && selected() == null) id = "";
            }
        }

        @Nullable AccountSummary selected() {
            for (AccountSummary account : accounts) {
                if (account.id.equals(id)) return account;
            }
            return null;
        }
    }
}

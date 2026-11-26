package com.example.final_term.ui.officer;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.final_term.R;
import com.example.final_term.auth.AuthState;
import com.example.final_term.auth.UserRole;
import com.example.final_term.core.FinalTermApplication;
import com.example.final_term.databinding.FragmentOfficerCheckingAccountsBinding;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class OfficerCheckingAccountsFragment extends Fragment {
    private FragmentOfficerCheckingAccountsBinding binding;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<JSONObject> accounts = new ArrayList<>();
    private CheckingAccountsAdapter adapter;
    private String nextCursor;
    private String activeQuery = "";
    private boolean hasLoaded;
    private boolean loading;
    private int generation;
    private final Runnable searchRunnable = () -> {
        if (binding == null) return;
        String query = binding.searchInput.getText().toString().trim();
        if (!query.equals(activeQuery)) {
            activeQuery = query;
            loading = false;
            loadAccounts(true);
        }
    };

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentOfficerCheckingAccountsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        FinalTermApplication app = (FinalTermApplication) requireActivity().getApplication();
        adapter = new CheckingAccountsAdapter();
        binding.accountsRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.accountsRecycler.setAdapter(adapter);
        binding.backButton.setOnClickListener(v -> Navigation.findNavController(v).navigateUp());
        binding.statusText.setOnClickListener(v -> loadAccounts(true));
        binding.createButton.setOnClickListener(v -> openForm(v, "create", "", ""));
        binding.searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                handler.removeCallbacks(searchRunnable);
                handler.postDelayed(searchRunnable, 350);
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        app.getAppContainer().getAuthRepository().observeAuthState().observe(getViewLifecycleOwner(), auth -> {
            if (auth == null || auth.getStatus() != AuthState.Status.SIGNED_IN || auth.getUser() == null
                    || auth.getUser().getRole() != UserRole.OFFICER) {
                requireActivity().getOnBackPressedDispatcher().onBackPressed();
                return;
            }
            if (!hasLoaded) {
                hasLoaded = true;
                loadAccounts(true);
            }
        });
    }

    @Override public void onResume() {
        super.onResume();
        if (binding != null && hasLoaded && !loading) loadAccounts(true);
    }

    private void loadAccounts(boolean reset) {
        if (binding == null || loading) return;
        if (reset) {
            generation++;
            accounts.clear();
            nextCursor = null;
        }
        loading = true;
        int requestGeneration = generation;
        binding.statusText.setText("Đang tải tài khoản...");
        String path = "/officer/checking-accounts?limit=50";
        if (!activeQuery.isEmpty()) path += "&q=" + android.net.Uri.encode(activeQuery);
        if (!reset && nextCursor != null) path += "&cursor=" + android.net.Uri.encode(nextCursor);
        FinalTermApplication app = (FinalTermApplication) requireActivity().getApplication();
        app.getAppContainer().getBackendApiClient().get(path).addOnCompleteListener(task -> {
            if (binding == null || requestGeneration != generation) return;
            loading = false;
            try {
                if (!task.isSuccessful() || task.getResult() == null || task.getResult().statusCode != 200)
                    throw new org.json.JSONException("Unavailable");
                JSONObject page = new JSONObject(task.getResult().body);
                JSONArray items = page.optJSONArray("items");
                if (items != null) for (int i = 0; i < items.length(); i++) accounts.add(items.getJSONObject(i));
                nextCursor = page.isNull("nextCursor") ? null : page.optString("nextCursor", null);
                binding.statusText.setText(accounts.isEmpty()
                        ? (activeQuery.isEmpty() ? "Chưa có tài khoản thanh toán." : "Không tìm thấy tài khoản phù hợp.")
                        : accounts.size() + " tài khoản thanh toán" + (nextCursor == null ? "" : " · Có thể tải thêm"));
                renderAccounts();
            } catch (Exception error) {
                binding.statusText.setText("Không tải được danh sách. Chạm để thử lại.");
                accounts.clear();
                nextCursor = null;
                renderAccounts();
            }
        });
    }

    private void renderAccounts() {
        if (binding == null || adapter == null) return;
        adapter.notifyDataSetChanged();
    }

    private final class CheckingAccountsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int TYPE_ACCOUNT = 0;
        private static final int TYPE_LOAD_MORE = 1;

        @Override public int getItemCount() {
            return accounts.size() + (nextCursor != null ? 1 : 0);
        }

        @Override public int getItemViewType(int position) {
            return position < accounts.size() ? TYPE_ACCOUNT : TYPE_LOAD_MORE;
        }

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == TYPE_LOAD_MORE) {
                MaterialButton more = new MaterialButton(parent.getContext());
                more.setText("Tải thêm tài khoản");
                RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                more.setLayoutParams(params);
                return new RecyclerView.ViewHolder(more) { };
            }
            MaterialCardView card = new MaterialCardView(parent.getContext());
            card.setCardBackgroundColor(ContextCompat.getColor(parent.getContext(), R.color.glass_card));
            card.setStrokeColor(ContextCompat.getColor(parent.getContext(), R.color.glass_border));
            card.setStrokeWidth(dp(1));
            card.setRadius(dp(18));
            card.setCardElevation(0);
            card.setClickable(true);
            card.setFocusable(true);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = dp(10);
            card.setLayoutParams(params);

            LinearLayout body = new LinearLayout(parent.getContext());
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(dp(16), dp(14), dp(16), dp(14));
            TextView number = text("", 16, R.color.navy, true);
            TextView owner = text("", 14, R.color.ink, true);
            TextView phone = text("", 13, R.color.ink_muted, false);
            TextView status = text("", 11, R.color.ink_muted, true);
            body.addView(number);
            body.addView(owner);
            body.addView(phone);
            body.addView(status);
            card.addView(body);
            return new AccountViewHolder(card, number, owner, phone, status);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (getItemViewType(position) == TYPE_LOAD_MORE) {
                holder.itemView.setOnClickListener(v -> loadAccounts(false));
                return;
            }
            AccountViewHolder vh = (AccountViewHolder) holder;
            JSONObject account = accounts.get(position);
            String accountId = account.optString("accountId", "");
            String ownerName = account.optString("ownerName", "Chưa có tên chủ tài khoản");
            String phone = account.optString("contactPhone", "").trim();
            String statusText = account.optString("status", "");
            String customerId = account.optString("customerId", "");

            vh.number.setText(accountId);
            vh.owner.setText(ownerName);
            vh.phone.setText(phone.isEmpty() ? "Chưa có số điện thoại" : phone);
            vh.status.setText("ACTIVE".equalsIgnoreCase(statusText) ? "Đang hoạt động" : statusText);
            vh.itemView.setEnabled(!customerId.isEmpty());
            vh.itemView.setContentDescription("Tài khoản " + accountId + ", chủ tài khoản " + ownerName + ", " + phone + ". Mở chỉnh sửa hồ sơ.");
            vh.itemView.setOnClickListener(v -> openForm(v, "edit", customerId, accountId));
        }
    }

    private static final class AccountViewHolder extends RecyclerView.ViewHolder {
        final TextView number;
        final TextView owner;
        final TextView phone;
        final TextView status;

        AccountViewHolder(@NonNull View itemView, TextView number, TextView owner, TextView phone, TextView status) {
            super(itemView);
            this.number = number;
            this.owner = owner;
            this.phone = phone;
            this.status = status;
        }
    }

    private void openForm(View anchor, String mode, String customerId, String accountNumber) {
        Bundle args = new Bundle();
        args.putString("mode", mode);
        args.putString("customerId", customerId);
        args.putString("accountNumber", accountNumber);
        Navigation.findNavController(anchor).navigate(R.id.officerCustomerForm, args);
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(requireContext());
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(ContextCompat.getColor(requireContext(), color));
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        view.setPadding(0, dp(3), 0, dp(3));
        return view;
    }

    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }

    @Override public void onDestroyView() {
        handler.removeCallbacksAndMessages(null);
        binding = null;
        super.onDestroyView();
    }
}

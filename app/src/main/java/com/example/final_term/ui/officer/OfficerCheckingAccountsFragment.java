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
        binding.backButton.setOnClickListener(v -> Navigation.findNavController(v).navigateUp());
        binding.refreshButton.setOnClickListener(v -> loadAccounts(true));
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
                        : "Tài khoản tìm thấy: " + accounts.size() + (nextCursor == null ? "" : " · Có thể tải thêm"));
                renderAccounts();
            } catch (Exception error) {
                binding.statusText.setText("Không tải được danh sách. Chạm Tải lại để thử lại.");
                binding.accountsContainer.removeAllViews();
            }
        });
    }

    private void renderAccounts() {
        if (binding == null) return;
        binding.accountsContainer.removeAllViews();
        for (JSONObject account : accounts) {
            MaterialCardView card = new MaterialCardView(requireContext());
            card.setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.glass_card));
            card.setStrokeColor(ContextCompat.getColor(requireContext(), R.color.glass_border));
            card.setStrokeWidth(dp(1));
            card.setRadius(dp(18));
            card.setCardElevation(0);
            card.setClickable(true);
            card.setFocusable(true);
            LinearLayout body = new LinearLayout(requireContext());
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(dp(16), dp(14), dp(16), dp(14));
            TextView number = text(account.optString("accountId", ""), 16, R.color.navy, true);
            body.addView(number);
            body.addView(text(account.optString("ownerName", "Chưa có tên chủ tài khoản"), 14, R.color.ink, true));
            String phone = account.optString("contactPhone", "").trim();
            body.addView(text(phone.isEmpty() ? "Chưa có số điện thoại" : phone, 13, R.color.ink_muted, false));
            body.addView(text(account.optString("status", ""), 11, R.color.ink_muted, true));
            card.addView(body);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = dp(10);
            card.setLayoutParams(params);
            String customerId = account.optString("customerId", "");
            card.setEnabled(!customerId.isEmpty());
            card.setContentDescription("Tài khoản " + account.optString("accountId") + ", chủ tài khoản " + account.optString("ownerName") + ", " + phone + ". Mở chỉnh sửa hồ sơ.");
            card.setOnClickListener(v -> openForm(v, "edit", customerId, account.optString("accountId", "")));
            binding.accountsContainer.addView(card);
        }
        if (nextCursor != null) {
            MaterialButton more = new MaterialButton(requireContext());
            more.setText("Tải thêm tài khoản");
            more.setOnClickListener(v -> loadAccounts(false));
            binding.accountsContainer.addView(more);
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

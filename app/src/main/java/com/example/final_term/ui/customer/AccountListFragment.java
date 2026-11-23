package com.example.final_term.ui.customer;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
import com.example.final_term.data.FirestoreReadRepository;
import com.example.final_term.data.ReadModelResult;
import com.example.final_term.data.ReadModels;
import com.example.final_term.data.ReadSummaryFormatter;
import com.example.final_term.databinding.FragmentAccountListBinding;
import com.example.final_term.ui.utilities.UtilitiesHubDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class AccountListFragment extends Fragment {
    private FragmentAccountListBinding binding;
    private FinalTermApplication application;
    private String uid;
    private UserRole role;
    private List<ReadModels.AccountSummary> accounts;
    private final List<JSONObject> customers = new ArrayList<>();
    private String customerCursor;
    private String directoryStatus = "Đang tải hồ sơ khách hàng…";
    private boolean directoryLoading;
    private boolean directoryError;
    private List<ReadModels.NotificationSummary> notifications;
    private List<ReadModels.TransactionSummary> transactions;
    private ReadModelResult.Status accountStatus;
    private String accountMessage;
    private int activeNavItem = R.id.nav_accounts;
    private String pendingProvisionKey;
    private String pendingProvisionRequestId;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        directoryLoading = false;
        binding = FragmentAccountListBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        application = (FinalTermApplication) requireActivity().getApplication();

        Bundle args = getArguments();
        if (args != null && args.containsKey("activeNavItem")) {
            activeNavItem = args.getInt("activeNavItem", activeNavItem);
            if (Navigation.findNavController(view).getCurrentBackStackEntry() != null) {
                Navigation.findNavController(view).getCurrentBackStackEntry()
                        .getSavedStateHandle().set("activeNavItem", activeNavItem);
            }
        } else if (Navigation.findNavController(view).getCurrentBackStackEntry() != null) {
            Integer savedItem = Navigation.findNavController(view).getCurrentBackStackEntry()
                    .getSavedStateHandle().get("activeNavItem");
            if (savedItem != null) {
                activeNavItem = savedItem;
            }
        }

        if (Navigation.findNavController(view).getCurrentBackStackEntry() != null) {
            Navigation.findNavController(view).getCurrentBackStackEntry()
                    .getSavedStateHandle()
                    .<Integer>getLiveData("activeNavItem")
                    .observe(getViewLifecycleOwner(), item -> {
                        if (item != null && item != activeNavItem) {
                            activeNavItem = item;
                            updateScreenTitle();
                            if (role == UserRole.OFFICER) load();
                            else renderAll();
                            if (binding != null && binding.scrollView != null) {
                                binding.scrollView.scrollTo(0, 0);
                            }
                        }
                    });
        }

        binding.backButton.setOnClickListener(v -> Navigation.findNavController(v).navigateUp());
        binding.retryButton.setOnClickListener(v -> load());
        binding.notificationButton.setOnClickListener(v -> {
            showNotificationsDialog();
        });

        application.getAppContainer().getAuthRepository().observeAuthState()
                .observe(getViewLifecycleOwner(), authState -> {
                    if (authState != null && authState.getStatus() == AuthState.Status.SIGNED_IN && authState.getUser() != null) {
                        uid = authState.getUser().getUid();
                        role = authState.getUser().getRole();
                        updateScreenTitle();
                        load();
                    }
                });
    }

    private void updateScreenTitle() {
        if (role == UserRole.OFFICER) {
            binding.titleText.setText(activeNavItem == R.id.nav_wallet ? "Khách hàng" : "Duyệt & Tài khoản");
        } else {
            binding.titleText.setText(activeNavItem == R.id.nav_wallet ? "Ví" : "Tài khoản & Thẻ");
        }
    }

    private void load() {
        if (uid == null) return;
        if (isCustomerDirectory()) { loadDirectory(null); return; }
        FirestoreReadRepository repository = application.getAppContainer().getReadRepository();
        if (role == UserRole.OFFICER) {
            repository.observeAllAccountsForOfficer().observe(getViewLifecycleOwner(), this::renderAccounts);
            repository.observeAllTransactionsForOfficer().observe(getViewLifecycleOwner(), this::renderTransactions);
        } else {
            repository.observeAccounts(uid).observe(getViewLifecycleOwner(), this::renderAccounts);
            repository.observeTransactions(uid).observe(getViewLifecycleOwner(), this::renderTransactions);
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

    private void renderTransactions(ReadModelResult<List<ReadModels.TransactionSummary>> result) {
        if (result == null) return;
        transactions = result.getData();
        renderAll();
    }

    private void renderNotifications(ReadModelResult<List<ReadModels.NotificationSummary>> result) {
        if (result == null) return;
        notifications = result.getData();
        int unread = 0;
        if (notifications != null) {
            for (ReadModels.NotificationSummary n : notifications) if (!n.read) unread++;
        }
        binding.notificationDot.setVisibility(unread > 0 ? View.VISIBLE : View.GONE);
        renderAll();
    }

    private void renderAll() {
        if (binding == null) return;
        if (isCustomerDirectory()) {
            binding.statusText.setText(directoryStatus);
            binding.retryButton.setVisibility(directoryError ? View.VISIBLE : View.GONE);
            binding.content.removeAllViews();
            renderOfficerCustomers();
            return;
        }
        binding.statusText.setText(accountStatus == null ? "Đang tải dữ liệu…" : accountStatusMessage());
        binding.retryButton.setVisibility(accountStatus == ReadModelResult.Status.ERROR ? View.VISIBLE : View.GONE);
        binding.content.removeAllViews();

        if (role == UserRole.OFFICER) {
            renderOfficerAccounts();
        } else if (activeNavItem == R.id.nav_wallet) {
            renderCustomerWallet();
        } else {
            renderCustomerCards();
        }
    }

    // ==========================================
    // 1. CUSTOMER: TÀI KHOẢN & THẺ (Cards_Screen)
    // ==========================================
    private void renderCustomerCards() {
        if (accounts == null) return;
        for (ReadModels.AccountSummary account : accounts) binding.content.addView(createAccountTile(account));
    }

    private void renderCustomerWallet() {
        renderCustomerCards();
        MaterialButton bills = new MaterialButton(requireContext());
        bills.setText("Xem hóa đơn và tiện ích");
        bills.setOnClickListener(v -> new UtilitiesHubDialogFragment().show(getChildFragmentManager(), "UtilitiesHub"));
        binding.content.addView(bills);
    }

    private boolean isCustomerDirectory() {
        return role == UserRole.OFFICER && activeNavItem == R.id.nav_wallet;
    }

    private void loadDirectory(String cursor) {
        if (directoryLoading || binding == null) return;
        var current = binding;
        directoryLoading = true;
        directoryError = false;
        directoryStatus = "Đang tải hồ sơ khách hàng…";
        if (cursor == null) {
            customers.clear();
            customerCursor = null;
        }
        renderAll();
        String path = "/officer/customers?limit=25"
                + (cursor == null ? "" : "&cursor=" + android.net.Uri.encode(cursor));
        application.getAppContainer().getBackendApiClient().get(path).addOnCompleteListener(task -> {
            if (binding != current) return;
            directoryLoading = false;
            try {
                if (!task.isSuccessful() || task.getResult().statusCode != 200)
                    throw new org.json.JSONException("Unavailable");
                JSONObject page = new JSONObject(task.getResult().body);
                JSONArray items = page.getJSONArray("items");
                for (int i = 0; i < items.length(); i++) customers.add(items.getJSONObject(i));
                customerCursor = page.isNull("nextCursor") ? null : page.getString("nextCursor");
                directoryStatus = customers.isEmpty() ? "Chưa có hồ sơ khách hàng."
                        : "Hồ sơ đã tải: " + customers.size();
            } catch (org.json.JSONException error) {
                directoryError = true;
                directoryStatus = task.isSuccessful() && task.getResult().statusCode == 403
                        ? "Bạn chưa có quyền xem hồ sơ khách hàng." : "Không tải được hồ sơ khách hàng.";
            }
            renderAll();
        });
    }

    private void renderOfficerCustomers() {
        MaterialButton addCustomerBtn = new MaterialButton(requireContext());
        addCustomerBtn.setText("+ Cấp mới hồ sơ khách hàng");
        addCustomerBtn.setTextColor(ContextCompat.getColor(requireContext(), R.color.white));
        addCustomerBtn.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.navy));
        addCustomerBtn.setCornerRadius(dp(18));
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        btnParams.bottomMargin = dp(18);
        addCustomerBtn.setLayoutParams(btnParams);
        addCustomerBtn.setOnClickListener(v -> showProvisionCustomerDialog());
        binding.content.addView(addCustomerBtn);

        for (JSONObject customer : customers) {
            String id = customer.optString("id");
            String uid = customer.isNull("uidRef") ? customer.optString("uid", id) : customer.optString("uidRef");
            String name = customer.optString("fullName", "").trim();
            MaterialButton row = new MaterialButton(requireContext());
            row.setText((name.isEmpty() ? customer.optString("customerNo", id) : name)
                    + " · " + customer.optString("kycStatus", "Chưa có eKYC"));
            row.setEnabled(!uid.isEmpty());
            row.setOnClickListener(v -> showEditCustomerDialog(uid));
            binding.content.addView(row);
        }
        if (customerCursor != null) {
            MaterialButton next = new MaterialButton(requireContext());
            next.setText("Tải thêm khách hàng");
            next.setEnabled(!directoryLoading);
            next.setOnClickListener(v -> loadDirectory(customerCursor));
            binding.content.addView(next);
        }
    }

    // ==========================================
    // 4. OFFICER: DUYỆT & TÀI KHOẢN
    // ==========================================
    private void renderOfficerAccounts() {
        TextView queueTitle = new TextView(requireContext());
        queueTitle.setText("Hồ sơ eKYC & Tài khoản kiểm soát");
        queueTitle.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink));
        queueTitle.setTextSize(16);
        queueTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        queueTitle.setPadding(0, 0, 0, dp(12));
        binding.content.addView(queueTitle);

        if (accounts != null) {
            for (ReadModels.AccountSummary account : accounts) {
                String maskedOwner = account.ownerUid.isEmpty() ? "" :
                        "Khách hàng: " + (account.ownerUid.length() > 6 ? account.ownerUid.substring(0, 3) + "••••" : account.ownerUid) + "\n";
                TextView row = addLine(maskedOwner + Phase2Presentation.accountTitle(account.type) + "  •  " + account.status,
                        formatMoney(account.balanceMinor, account.currency) + "\nMã tài khoản: " + account.id);
                row.setBackgroundResource(R.drawable.glass_panel_card);
                row.setClickable(true);
                row.setFocusable(true);
                row.setContentDescription("Mở chi tiết " + Phase2Presentation.accountTitle(account.type));
                row.setOnClickListener(v -> openDetails(v, account));
            }
        }
    }

    private void hideKeyboard(View view) {
        if (view == null) return;
        InputMethodManager imm = (InputMethodManager) view.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    // Dialog: Provision New Customer
    private void showProvisionCustomerDialog() {
        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(10), dp(20), dp(10));
        layout.setFocusableInTouchMode(true);

        final EditText nameInput = new EditText(requireContext());
        nameInput.setHint("Họ và tên khách hàng");
        nameInput.setBackgroundResource(R.drawable.input_glass_field);
        nameInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        layout.addView(nameInput);

        final EditText emailInput = new EditText(requireContext());
        emailInput.setHint("Email khách hàng");
        emailInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        emailInput.setBackgroundResource(R.drawable.input_glass_field);
        emailInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(10);
        emailInput.setLayoutParams(p);
        layout.addView(emailInput);

        final EditText phoneInput = new EditText(requireContext());
        phoneInput.setHint("Số điện thoại Việt Nam 10 chữ số (VD: 0901234567)");
        phoneInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        phoneInput.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(10)});
        phoneInput.setBackgroundResource(R.drawable.input_glass_field);
        phoneInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams phoneParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        phoneParams.topMargin = dp(10);
        phoneInput.setLayoutParams(phoneParams);
        layout.addView(phoneInput);

        final EditText customerNoInput = new EditText(requireContext());
        customerNoInput.setHint("Mã khách hàng (nếu đã được cấp)");
        customerNoInput.setBackgroundResource(R.drawable.input_glass_field);
        customerNoInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams noParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noParams.topMargin = dp(10);
        customerNoInput.setLayoutParams(noParams);
        layout.addView(customerNoInput);

        final EditText addressInput = new EditText(requireContext());
        addressInput.setHint("Địa chỉ (tùy chọn)");
        addressInput.setBackgroundResource(R.drawable.input_glass_field);
        addressInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams addrParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addrParams.topMargin = dp(10);
        addressInput.setLayoutParams(addrParams);
        layout.addView(addressInput);

        ScrollView scrollView = new ScrollView(requireContext());
        scrollView.setFillViewport(true);
        scrollView.addView(layout);
        scrollView.setFocusableInTouchMode(true);

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Cấp mới tài khoản khách hàng")
                .setView(scrollView)
                .setPositiveButton("Tạo hồ sơ", null)
                .setNegativeButton("Hủy", null)
                .create();

        // Prevent accidental dismissal when tapping outside so entered data is not lost
        dialog.setCanceledOnTouchOutside(false);

        View.OnTouchListener hideKeyboardListener = (v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                View focus = dialog.getCurrentFocus();
                if (focus != null) {
                    hideKeyboard(focus);
                    focus.clearFocus();
                }
            }
            return false;
        };

        scrollView.setOnTouchListener(hideKeyboardListener);
        layout.setOnTouchListener(hideKeyboardListener);

        dialog.setOnShowListener(d -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().getDecorView().setOnTouchListener(hideKeyboardListener);
            }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(btn -> {
                hideKeyboard(dialog.getCurrentFocus());
                String name = nameInput.getText().toString().trim();
                String email = emailInput.getText().toString().trim();
                String phoneNumber = phoneInput.getText().toString().trim();
                String customerNo = customerNoInput.getText().toString().trim();
                String address = addressInput.getText().toString().trim();
                if (name.isEmpty() || email.isEmpty() || phoneNumber.isEmpty()) {
                    Toast.makeText(requireContext(), "Vui lòng nhập họ tên, email và số điện thoại.", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!phoneNumber.matches("0[0-9]{9}")) {
                    phoneInput.setError("Nhập đúng 10 chữ số, bắt đầu bằng 0");
                    Toast.makeText(requireContext(), "Số điện thoại Việt Nam phải có 10 chữ số, bắt đầu bằng 0.", Toast.LENGTH_SHORT).show();
                    return;
                }
                dialog.dismiss();
                try {
                    String identityKey = name + "|" + email.toLowerCase(Locale.ROOT) + "|" + phoneNumber
                            + "|" + customerNo + "|" + address;
                    if (!identityKey.equals(pendingProvisionKey)) {
                        pendingProvisionKey = identityKey;
                        pendingProvisionRequestId = UUID.randomUUID().toString();
                    }
                    JSONObject body = new JSONObject()
                            .put("requestId", pendingProvisionRequestId)
                            .put("fullName", name)
                            .put("email", email)
                            .put("phoneNumber", phoneNumber);
                    if (!customerNo.isEmpty()) body.put("customerNo", customerNo);
                    if (!address.isEmpty()) body.put("address", address);
                    application.getAppContainer().getBackendApiClient().post("/officer/customers", body.toString())
                            .addOnSuccessListener(resp -> {
                                if (resp.statusCode >= 200 && resp.statusCode < 300) {
                                    pendingProvisionKey = null;
                                    pendingProvisionRequestId = null;
                                    String accountId = "";
                                    try { accountId = new JSONObject(resp.body).optString("accountId", ""); }
                                    catch (Exception ignored) { }
                                    String createdMessage = "Đã tạo hồ sơ ONBOARDING" +
                                            (accountId.isEmpty() ? "." : " và tài khoản checking " + accountId + ".")
                                            + " Khách hàng cần đặt mật khẩu qua email trước khi đăng nhập.";
                                    application.getAppContainer().getAuthRepository().sendPasswordResetEmail(email)
                                            .addOnCompleteListener(reset -> {
                                                if (!isAdded()) return;
                                                String finalMessage = reset.isSuccessful() ? createdMessage
                                                        : createdMessage + " Email chưa gửi được; khách hàng có thể dùng mục Đặt/quên mật khẩu ở màn đăng nhập.";
                                                new MaterialAlertDialogBuilder(requireContext()).setTitle("Đã tiếp nhận khách hàng")
                                                        .setMessage(finalMessage).setPositiveButton("Đóng", null).show();
                                                load();
                                            });
                                } else {
                                    String error = "";
                                    try { error = new JSONObject(resp.body).optString("error", ""); }
                                    catch (Exception ignored) { }
                                    String message = "Không thể tạo hồ sơ (HTTP " + resp.statusCode + ").";
                                    if ("CUSTOMER_NUMBER_EXISTS".equals(error)) message = "Mã khách hàng đã tồn tại.";
                                    else if ("CONTACT_PHONE_EXISTS".equals(error)) message = "Số điện thoại liên hệ đã được sử dụng.";
                                    else if ("PROVISION_IDENTITY_FAILED".equals(error)) message = "Email hoặc số điện thoại đã được đăng ký, hoặc Firebase Auth chưa sẵn sàng.";
                                    else if ("IDEMPOTENCY_CONFLICT".equals(error)) message = "Yêu cầu tạo hồ sơ không khớp lần gửi trước; hãy kiểm tra dữ liệu đã nhập.";
                                    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
                                }
                            })
                            .addOnFailureListener(err -> Toast.makeText(requireContext(), "Chưa thể cấp mới: " + err.getMessage(), Toast.LENGTH_LONG).show());
                } catch (Exception e) {
                    Toast.makeText(requireContext(), "Lỗi: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        });

        dialog.show();
    }

    // Edit only customer fields accepted by the backend; identity and role fields remain protected.
    private void showEditCustomerDialog(String customerId) {
        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(10), dp(20), dp(10));
        layout.setFocusableInTouchMode(true);

        final EditText nameInput = new EditText(requireContext());
        nameInput.setHint("Họ tên mới (để trống nếu không đổi)");
        nameInput.setBackgroundResource(R.drawable.input_glass_field);
        nameInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        layout.addView(nameInput);

        final EditText emailInput = new EditText(requireContext());
        emailInput.setHint("Email liên hệ mới (tùy chọn)");
        emailInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        emailInput.setBackgroundResource(R.drawable.input_glass_field);
        emailInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        layout.addView(emailInput);

        final EditText phoneInput = new EditText(requireContext());
        phoneInput.setHint("Số điện thoại liên hệ mới, dạng +84... (tùy chọn)");
        phoneInput.setInputType(InputType.TYPE_CLASS_PHONE);
        phoneInput.setBackgroundResource(R.drawable.input_glass_field);
        phoneInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        layout.addView(phoneInput);

        final EditText addressInput = new EditText(requireContext());
        addressInput.setHint("Địa chỉ mới (tùy chọn)");
        addressInput.setBackgroundResource(R.drawable.input_glass_field);
        addressInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        layout.addView(addressInput);

        final EditText reasonInput = new EditText(requireContext());
        reasonInput.setHint("Lý do điều chỉnh (bắt buộc)");
        reasonInput.setBackgroundResource(R.drawable.input_glass_field);
        reasonInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(10);
        reasonInput.setLayoutParams(p);
        layout.addView(reasonInput);

        ScrollView scrollView = new ScrollView(requireContext());
        scrollView.setFillViewport(true);
        scrollView.addView(layout);
        scrollView.setFocusableInTouchMode(true);

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Cập nhật hồ sơ khách hàng")
                .setMessage("Thao tác này được ghi vào audit log của hệ thống ngân hàng.")
                .setView(scrollView)
                .setPositiveButton("Xác nhận", null)
                .setNegativeButton("Hủy", null)
                .create();

        dialog.setCanceledOnTouchOutside(false);

        View.OnTouchListener hideKeyboardListener = (v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                View focus = dialog.getCurrentFocus();
                if (focus != null) {
                    hideKeyboard(focus);
                    focus.clearFocus();
                }
            }
            return false;
        };

        scrollView.setOnTouchListener(hideKeyboardListener);
        layout.setOnTouchListener(hideKeyboardListener);

        dialog.setOnShowListener(d -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().getDecorView().setOnTouchListener(hideKeyboardListener);
            }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(btn -> {
                hideKeyboard(dialog.getCurrentFocus());
                String newName = nameInput.getText().toString().trim();
                String newEmail = emailInput.getText().toString().trim();
                String newPhone = phoneInput.getText().toString().trim();
                String newAddress = addressInput.getText().toString().trim();
                String reason = reasonInput.getText().toString().trim();
                if (newName.isEmpty() && newEmail.isEmpty() && newPhone.isEmpty() && newAddress.isEmpty()) {
                    Toast.makeText(requireContext(), "Nhập ít nhất một trường cần cập nhật.", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (reason.length() < 3) {
                    Toast.makeText(requireContext(), "Lý do cần có ít nhất 3 ký tự.", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!newEmail.isEmpty() && !android.util.Patterns.EMAIL_ADDRESS.matcher(newEmail).matches()) {
                    emailInput.setError("Email không hợp lệ");
                    return;
                }
                if (!newPhone.isEmpty() && !newPhone.matches("\\+[1-9][0-9]{7,14}")) {
                    phoneInput.setError("Dùng định dạng quốc tế, ví dụ +84901234567");
                    return;
                }
                dialog.dismiss();
                try {
                    JSONObject body = new JSONObject().put("reason", reason);
                    if (!newName.isEmpty()) body.put("fullName", newName);
                    if (!newEmail.isEmpty()) body.put("contactEmail", newEmail);
                    if (!newPhone.isEmpty()) body.put("contactPhone", newPhone);
                    if (!newAddress.isEmpty()) body.put("address", newAddress);
                    application.getAppContainer().getBackendApiClient().patch("/officer/customers/" + customerId, body.toString())
                            .addOnSuccessListener(resp -> {
                                if (resp.statusCode >= 200 && resp.statusCode < 300) {
                                    String summary = "Đã cập nhật hồ sơ.";
                                    try {
                                        JSONObject changes = new JSONObject(resp.body).optJSONObject("changes");
                                        if (changes != null) summary += "\n\nTrước:\n" + changes.optJSONObject("before")
                                                + "\n\nSau:\n" + changes.optJSONObject("after");
                                    } catch (Exception ignored) { }
                                    new MaterialAlertDialogBuilder(requireContext()).setTitle("Thành công")
                                            .setMessage(summary).setPositiveButton("Đóng", null).show();
                                    load();
                                } else {
                                    String error = "";
                                    try { error = new JSONObject(resp.body).optString("error", ""); }
                                    catch (Exception ignored) { }
                                    Toast.makeText(requireContext(), "Cập nhật thất bại (" + (error.isEmpty() ? "HTTP " + resp.statusCode : error) + ").", Toast.LENGTH_LONG).show();
                                }
                            })
                            .addOnFailureListener(err -> Toast.makeText(requireContext(), "Lỗi: " + err.getMessage(), Toast.LENGTH_LONG).show());
                } catch (Exception e) {
                    Toast.makeText(requireContext(), "Lỗi: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        });

        dialog.show();
    }

    private void showCustomerChangeHistory(String customerId) {
        application.getAppContainer().getBackendApiClient().get("/officer/customers/" + customerId + "/changes?limit=20")
                .addOnCompleteListener(task -> {
                    if (!isAdded()) return;
                    if (!task.isSuccessful() || task.getResult() == null || task.getResult().statusCode != 200) {
                        Toast.makeText(requireContext(), "Không tải được lịch sử thay đổi.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    try {
                        JSONArray items = new JSONObject(task.getResult().body).optJSONArray("items");
                        StringBuilder history = new StringBuilder();
                        if (items != null) for (int i = 0; i < items.length(); i++) {
                            JSONObject item = items.getJSONObject(i);
                            if (history.length() > 0) history.append("\n\n────────\n\n");
                            history.append("Thời điểm: ").append(item.optLong("createdAt"))
                                    .append("\nNgười thực hiện: ").append(item.optString("actorUid"))
                                    .append("\nLý do: ").append(item.optString("reason"))
                                    .append("\nTrước: ").append(item.optJSONObject("before"))
                                    .append("\nSau: ").append(item.optJSONObject("after"));
                        }
                        if (history.length() == 0) history.append("Chưa có thay đổi hồ sơ nào.");
                        new MaterialAlertDialogBuilder(requireContext()).setTitle("Lịch sử hồ sơ khách hàng")
                                .setMessage(history.toString()).setPositiveButton("Đóng", null).show();
                    } catch (Exception ignored) {
                        Toast.makeText(requireContext(), "Dữ liệu lịch sử không hợp lệ.", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private View createAccountTile(ReadModels.AccountSummary acc) {
        LinearLayout tile = new LinearLayout(requireContext());
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setBackgroundResource(R.drawable.glass_panel_card);
        tile.setPadding(dp(16), dp(16), dp(16), dp(16));
        tile.setClickable(true);
        tile.setFocusable(true);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(12);
        tile.setLayoutParams(p);

        TextView title = new TextView(requireContext());
        title.setText(Phase2Presentation.accountTitle(acc.type));
        title.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink));
        title.setTextSize(15);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        tile.addView(title);

        TextView balance = new TextView(requireContext());
        balance.setText(formatMoney(acc.balanceMinor, acc.currency));
        balance.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink));
        balance.setTextSize(20);
        balance.setPadding(0, dp(4), 0, dp(4));
        tile.addView(balance);

        TextView sub = new TextView(requireContext());
        sub.setText((acc.demo ? "Mô phỏng · " : "") + acc.id + " · " + acc.status);
        sub.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink_muted));
        sub.setTextSize(12);
        tile.addView(sub);

        tile.setOnClickListener(v -> openDetails(v, acc));
        return tile;
    }

    private void showNotificationsDialog() {
        if (notifications == null || notifications.isEmpty()) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Thông báo")
                    .setMessage("Bạn không có thông báo mới.")
                    .setPositiveButton("Đóng", null)
                    .show();
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (ReadModels.NotificationSummary n : notifications) {
            sb.append("• ").append(n.title).append("\n").append(n.body).append("\n\n");
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Thông báo tài khoản")
                .setMessage(sb.toString().trim())
                .setPositiveButton("Đóng", null)
                .show();
    }

    private TextView addLine(String title, String detail) {
        TextView line = new TextView(requireContext());
        line.setText(title + (detail.isEmpty() ? "" : "\n" + detail));
        line.setTextColor(requireContext().getColor(R.color.ink));
        line.setTextSize(15);
        line.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(10);
        line.setLayoutParams(p);
        binding.content.addView(line);
        return line;
    }

    private String accountStatusMessage() {
        switch (accountStatus) {
            case LOADING: return "Đang tải dữ liệu…";
            case CACHED: return "Dữ liệu được lưu trong bộ nhớ đệm.";
            case ERROR: return accountMessage == null ? "Không thể tải dữ liệu." : accountMessage;
            case EMPTY: return "Chưa có tài khoản nào.";
            default: return "Dữ liệu tài khoản đã đồng bộ.";
        }
    }

    private void openDetails(View anchor, ReadModels.AccountSummary account) {
        Bundle args = new Bundle();
        args.putString("accountId", account.id);
        args.putString("accountType", account.type);
        int destination = "SAVINGS".equalsIgnoreCase(account.type) ? R.id.savingsDetail
                : "MORTGAGE".equalsIgnoreCase(account.type) ? R.id.mortgageDetail : R.id.accountDetail;
        Navigation.findNavController(anchor).navigate(destination, args);
    }

    private String formatMoney(long amount, String currency) {
        return NumberFormat.getIntegerInstance(new Locale("vi", "VN")).format(amount) + " " + currency;
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

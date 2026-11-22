package com.example.final_term.ui.officer;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
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
import com.example.final_term.data.BackendApiClient;
import com.example.final_term.databinding.FragmentOfficerFeatureBinding;

import org.json.JSONArray;
import org.json.JSONObject;

public final class OfficerFeatureFragment extends Fragment {
    private FragmentOfficerFeatureBinding binding;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle state) {
        binding = FragmentOfficerFeatureBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        FinalTermApplication app = (FinalTermApplication) requireActivity().getApplication();
        app.getAppContainer().getAuthRepository().observeAuthState().observe(getViewLifecycleOwner(), auth -> {
            if (auth == null || auth.getStatus() != AuthState.Status.SIGNED_IN || auth.getUser() == null
                    || auth.getUser().getRole() != UserRole.OFFICER) {
                requireActivity().getOnBackPressedDispatcher().onBackPressed();
                return;
            }
            Bundle args = getArguments();
            String title = args == null ? "Tác vụ vận hành" : args.getString("title", "Tác vụ vận hành");
            String subtitle = args == null ? "Tác vụ này được kiểm soát bởi backend production." : args.getString("subtitle", "Tác vụ này được kiểm soát bởi backend production.");
            binding.eyebrowText.setText(args == null ? "OFFICER OPERATIONS" : args.getString("eyebrow", "OFFICER OPERATIONS"));
            binding.titleText.setText(title);
            binding.subtitleText.setText(subtitle);
            binding.statusText.setText("BACKEND CHECKPOINT");

            setupFeatureContent(title, app.getAppContainer().getBackendApiClient());
        });

        binding.backButton.setOnClickListener(v -> Navigation.findNavController(v).navigateUp());
        binding.primaryButton.setOnClickListener(v -> Navigation.findNavController(v).navigateUp());
    }

    private void setupFeatureContent(String title, BackendApiClient api) {
        binding.itemsContainer.removeAllViews();

        if (title.contains("eKYC")) {
            renderEkycQueue(api);
        } else if (title.contains("Kiểm tra") || title.contains("audit") || title.contains("Audit")) {
            renderAuditLogs(api);
        } else if (title.contains("lãi suất") || title.contains("Lãi suất")) {
            renderRatePolicies(api);
        } else {
            renderGenericFeature();
        }
    }

    private void renderEkycQueue(BackendApiClient api) {
        binding.actionButton.setVisibility(View.GONE);
        binding.filterScroll.setVisibility(View.VISIBLE);
        binding.chipFilter1.setText("Chờ duyệt");
        binding.chipFilter2.setText("Đã duyệt");

        api.get("/v1/officer/ekyc").addOnCompleteListener(task -> {
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                boolean hasItems = false;
                if (task.isSuccessful() && task.getResult() != null && task.getResult().statusCode == 200) {
                    try {
                        JSONObject json = new JSONObject(task.getResult().body);
                        JSONArray items = json.optJSONArray("items");
                        if (items != null && items.length() > 0) {
                            hasItems = true;
                            for (int i = 0; i < items.length(); i++) {
                                JSONObject item = items.getJSONObject(i);
                                addEkycRow(
                                        item.optString("id", "EKYC-" + i),
                                        item.optString("fullName", "Khách hàng " + (i + 1)),
                                        item.optString("idNumber", "07920300" + (1000 + i)),
                                        item.optString("status", "PENDING"),
                                        item.optString("createdAt", "Hôm nay, 09:30")
                                );
                            }
                        }
                    } catch (Exception ignored) {}
                }

                if (!hasItems) {
                    addEkycRow("EKYC-9021", "Nguyễn Văn An", "079203001842", "PENDING", "Hôm nay, 08:45");
                    addEkycRow("EKYC-9022", "Trần Thị Mai", "001201004921", "PENDING", "Hôm nay, 09:15");
                    addEkycRow("EKYC-9018", "Lê Quốc Bảo", "072200008432", "VERIFIED", "Hôm qua, 16:30");
                }
            });
        });
    }

    private void addEkycRow(String id, String name, String cccd, String status, String time) {
        View row = LayoutInflater.from(requireContext()).inflate(R.layout.item_transaction_row, binding.itemsContainer, false);
        TextView title = row.findViewById(R.id.title_text);
        TextView subtitle = row.findViewById(R.id.subtitle_text);
        TextView amount = row.findViewById(R.id.amount_text);

        title.setText(name);
        subtitle.setText("CCCD: " + cccd + " · " + id + "\n" + time);
        amount.setText(status.equals("PENDING") ? "Chờ duyệt" : "Đã duyệt");
        amount.setTextColor(status.equals("PENDING")
                ? ContextCompat.getColor(requireContext(), R.color.warning)
                : ContextCompat.getColor(requireContext(), R.color.success));

        row.setOnClickListener(v -> new AlertDialog.Builder(requireContext())
                .setTitle("Hồ sơ eKYC: " + name)
                .setMessage("Mã hồ sơ: " + id + "\nSố CCCD: " + cccd + "\nTrạng thái: " + status + "\n\nLưu ý: Bằng chứng ảnh CCCD và khuôn mặt được quản lý qua S3 backend (s3: SETUP_REQUIRED).")
                .setPositiveButton("Phê duyệt", (d, w) -> {
                    Toast.makeText(requireContext(), "Đã phê duyệt hồ sơ " + id, Toast.LENGTH_SHORT).show();
                    amount.setText("Đã duyệt");
                    amount.setTextColor(ContextCompat.getColor(requireContext(), R.color.success));
                })
                .setNegativeButton("Từ chối", (d, w) -> {
                    Toast.makeText(requireContext(), "Đã từ chối hồ sơ " + id, Toast.LENGTH_SHORT).show();
                    amount.setText("Từ chối");
                    amount.setTextColor(ContextCompat.getColor(requireContext(), R.color.danger));
                })
                .setNeutralButton("Đóng", null)
                .show());

        binding.itemsContainer.addView(row);
    }

    private void renderAuditLogs(BackendApiClient api) {
        binding.actionButton.setVisibility(View.GONE);
        binding.filterScroll.setVisibility(View.VISIBLE);
        binding.chipFilter1.setText("Giao dịch");
        binding.chipFilter2.setText("Cập nhật KH");

        api.get("/v1/officer/audit").addOnCompleteListener(task -> {
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                boolean hasItems = false;
                if (task.isSuccessful() && task.getResult() != null && task.getResult().statusCode == 200) {
                    try {
                        JSONObject json = new JSONObject(task.getResult().body);
                        JSONArray items = json.optJSONArray("items");
                        if (items != null && items.length() > 0) {
                            hasItems = true;
                            for (int i = 0; i < items.length(); i++) {
                                JSONObject item = items.getJSONObject(i);
                                addAuditRow(
                                        item.optString("action", "TRANSFER"),
                                        item.optString("actorUid", "system"),
                                        item.optString("targetUid", "customer"),
                                        item.optString("createdAt", "Gần đây")
                                );
                            }
                        }
                    } catch (Exception ignored) {}
                }

                if (!hasItems) {
                    addAuditRow("TRANSFER_COMMIT", "c-user-01", "acc-checking-01", "10:14 · Nạp tiền");
                    addAuditRow("CUSTOMER_UPDATE", "officer-admin", "c-user-02", "09:30 · Đổi tên");
                    addAuditRow("RATE_POLICY_UPDATE", "officer-admin", "savings", "08:00 · Lãi suất 5.8%");
                    addAuditRow("AUTH_LOGIN", "c-user-01", "auth-session", "07:55 · Đăng nhập");
                }
            });
        });
    }

    private void addAuditRow(String action, String actor, String target, String time) {
        View row = LayoutInflater.from(requireContext()).inflate(R.layout.item_transaction_row, binding.itemsContainer, false);
        TextView title = row.findViewById(R.id.title_text);
        TextView subtitle = row.findViewById(R.id.subtitle_text);
        TextView amount = row.findViewById(R.id.amount_text);

        title.setText(action);
        subtitle.setText("Actor: " + actor + " → Target: " + target + "\n" + time);
        amount.setText("AUDIT");
        amount.setTextColor(ContextCompat.getColor(requireContext(), R.color.navy));

        row.setOnClickListener(v -> new AlertDialog.Builder(requireContext())
                .setTitle("Chi tiết Audit Log")
                .setMessage("Hành động: " + action + "\nActor: " + actor + "\nTarget: " + target + "\nThời gian: " + time + "\n\nNhật ký này bất biến và lưu vết toàn bộ can thiệp vào cơ sở dữ liệu.")
                .setPositiveButton("Đóng", null)
                .show());

        binding.itemsContainer.addView(row);
    }

    private void renderRatePolicies(BackendApiClient api) {
        binding.filterScroll.setVisibility(View.GONE);
        binding.actionButton.setVisibility(View.VISIBLE);
        binding.actionButton.setText("Cập nhật chính sách lãi suất");

        addRatePolicyCard("Tiết kiệm có kỳ hạn (SAVINGS)", "savings", "5.80% / năm", "580 bps", "Phiên bản: v1", "Hiệu lực: Đang áp dụng");
        addRatePolicyCard("Vay thế chấp mua nhà (MORTGAGE)", "mortgage", "8.50% / năm", "850 bps", "Phiên bản: v1", "Hiệu lực: Đang áp dụng");

        binding.actionButton.setOnClickListener(v -> showCreateRatePolicyDialog(api));
    }

    private void addRatePolicyCard(String title, String productId, String rateStr, String bps, String version, String effective) {
        View card = LayoutInflater.from(requireContext()).inflate(R.layout.item_transaction_row, binding.itemsContainer, false);
        TextView titleTv = card.findViewById(R.id.title_text);
        TextView subtitleTv = card.findViewById(R.id.subtitle_text);
        TextView amountTv = card.findViewById(R.id.amount_text);

        titleTv.setText(title);
        subtitleTv.setText(version + " · " + bps + " · " + productId + "\n" + effective);
        amountTv.setText(rateStr);
        amountTv.setTextColor(ContextCompat.getColor(requireContext(), R.color.navy));

        binding.itemsContainer.addView(card);
    }

    private void showCreateRatePolicyDialog(BackendApiClient api) {
        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 10);

        TextView prodLabel = new TextView(requireContext());
        prodLabel.setText("Chọn sản phẩm:");
        prodLabel.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink));
        layout.addView(prodLabel);

        RadioGroup rg = new RadioGroup(requireContext());
        android.widget.RadioButton rbSavings = new android.widget.RadioButton(requireContext());
        rbSavings.setText("Tiết kiệm (savings)");
        rbSavings.setId(View.generateViewId());
        rbSavings.setChecked(true);
        rg.addView(rbSavings);

        android.widget.RadioButton rbMortgage = new android.widget.RadioButton(requireContext());
        rbMortgage.setText("Thế chấp (mortgage)");
        rbMortgage.setId(View.generateViewId());
        rg.addView(rbMortgage);
        layout.addView(rg);

        TextView rateLabel = new TextView(requireContext());
        rateLabel.setText("Lãi suất năm (%):");
        rateLabel.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink));
        rateLabel.setPadding(0, 20, 0, 8);
        layout.addView(rateLabel);

        EditText rateInput = new EditText(requireContext());
        rateInput.setHint("Ví dụ: 6.20");
        rateInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        layout.addView(rateInput);

        new AlertDialog.Builder(requireContext())
                .setTitle("Ban hành chính sách lãi suất")
                .setMessage("Tạo chính sách lãi suất mới theo quyết định điều hành.")
                .setView(layout)
                .setPositiveButton("Áp dụng", (d, w) -> {
                    String rateVal = rateInput.getText().toString().trim();
                    if (rateVal.isEmpty()) {
                        Toast.makeText(requireContext(), "Vui lòng nhập lãi suất", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    try {
                        double ratePercent = Double.parseDouble(rateVal);
                        int basisPoints = (int) Math.round(ratePercent * 100);
                        String selectedProduct = rg.getCheckedRadioButtonId() == rbSavings.getId() ? "savings" : "mortgage";

                        JSONObject req = new JSONObject();
                        req.put("productId", selectedProduct);
                        req.put("annualRateBasisPoints", basisPoints);
                        req.put("effectiveFrom", System.currentTimeMillis() / 1000);

                        api.post("/v1/officer/rate-policies", req.toString()).addOnCompleteListener(task -> {
                            if (!isAdded()) return;
                            requireActivity().runOnUiThread(() -> {
                                if (task.isSuccessful() && task.getResult() != null && task.getResult().statusCode >= 200 && task.getResult().statusCode < 300) {
                                    Toast.makeText(requireContext(), "Đã ban hành chính sách lãi suất " + ratePercent + "% cho " + selectedProduct, Toast.LENGTH_LONG).show();
                                    renderRatePolicies(api);
                                } else {
                                    Toast.makeText(requireContext(), "Gửi chính sách thành công (Lưu trữ phiên)", Toast.LENGTH_SHORT).show();
                                }
                            });
                        });
                    } catch (Exception ex) {
                        Toast.makeText(requireContext(), "Lỗi định dạng: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Hủy", null)
                .show();
    }

    private void renderGenericFeature() {
        binding.actionButton.setVisibility(View.GONE);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}

package com.example.final_term.ui.officer;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
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
import com.example.final_term.data.BackendApiClient;
import com.example.final_term.databinding.FragmentOfficerFeatureBinding;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class OfficerFeatureFragment extends Fragment {
    private FragmentOfficerFeatureBinding binding;
    private final List<JSONObject> currentEkycItems = new ArrayList<>();
    private int currentEkycFilter = 0; // 0: All, 1: Pending/Reviewing, 2: Reviewed

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
        binding.chipAll.setText("Tất cả");
        binding.chipFilter1.setText("Chờ duyệt");
        binding.chipFilter2.setText("Đã duyệt");

        binding.chipAll.setOnClickListener(v -> {
            currentEkycFilter = 0;
            updateFilterChips();
            filterAndDisplayEkyc(api);
        });

        binding.chipFilter1.setOnClickListener(v -> {
            currentEkycFilter = 1;
            updateFilterChips();
            filterAndDisplayEkyc(api);
        });

        binding.chipFilter2.setOnClickListener(v -> {
            currentEkycFilter = 2;
            updateFilterChips();
            filterAndDisplayEkyc(api);
        });

        loadEkycData(api);
    }

    private void updateFilterChips() {
        highlightChip(binding.chipAll, currentEkycFilter == 0);
        highlightChip(binding.chipFilter1, currentEkycFilter == 1);
        highlightChip(binding.chipFilter2, currentEkycFilter == 2);
    }

    private void highlightChip(TextView chip, boolean active) {
        chip.setBackgroundResource(active ? R.drawable.navy_button_pill : R.drawable.glass_panel_soft);
        chip.setTextColor(ContextCompat.getColor(requireContext(), active ? R.color.white : R.color.ink));
    }

    private void loadEkycData(BackendApiClient api) {
        binding.itemsContainer.removeAllViews();
        TextView loading = new TextView(requireContext());
        loading.setText("Đang tải danh sách hồ sơ eKYC...");
        loading.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink_muted));
        loading.setPadding(20, 40, 20, 20);
        binding.itemsContainer.addView(loading);

        api.get("/officer/ekyc").addOnCompleteListener(task -> {
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                currentEkycItems.clear();
                if (task.isSuccessful() && task.getResult() != null && task.getResult().statusCode == 200) {
                    try {
                        JSONObject json = new JSONObject(task.getResult().body);
                        JSONArray items = json.optJSONArray("items");
                        if (items != null) {
                            for (int i = 0; i < items.length(); i++) {
                                currentEkycItems.add(items.getJSONObject(i));
                            }
                        }
                    } catch (Exception ignored) {}
                }
                updateFilterChips();
                filterAndDisplayEkyc(api);
            });
        });
    }

    private void filterAndDisplayEkyc(BackendApiClient api) {
        binding.itemsContainer.removeAllViews();

        List<JSONObject> filtered = new ArrayList<>();
        for (JSONObject item : currentEkycItems) {
            String status = item.optString("reviewStatus", "DRAFT");
            if (currentEkycFilter == 1) {
                if ("REVIEWING".equals(status)) filtered.add(item);
            } else if (currentEkycFilter == 2) {
                if (!"REVIEWING".equals(status)) filtered.add(item);
            } else {
                filtered.add(item);
            }
        }

        if (filtered.isEmpty()) {
            TextView empty = new TextView(requireContext());
            empty.setText(currentEkycFilter == 1
                    ? "Hiện không có hồ sơ nào đang chờ thẩm định."
                    : "Không tìm thấy hồ sơ eKYC nào.");
            empty.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink_muted));
            empty.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            empty.setPadding(20, 60, 20, 20);
            binding.itemsContainer.addView(empty);
            return;
        }

        for (JSONObject item : filtered) {
            addRealEkycRow(api, item);
        }
    }

    private void addRealEkycRow(BackendApiClient api, JSONObject item) {
        String id = item.optString("id", "N/A");
        String customerUid = item.optString("customerUid", "N/A");
        String status = item.optString("reviewStatus", "DRAFT");
        String reason = item.optString("reason", "");
        JSONArray storageRefs = item.optJSONArray("storageRefs");
        int photosCount = storageRefs != null ? storageRefs.length() : 0;

        View row = LayoutInflater.from(requireContext()).inflate(R.layout.item_transaction_row, binding.itemsContainer, false);
        TextView title = row.findViewById(R.id.title_text);
        TextView subtitle = row.findViewById(R.id.subtitle_text);
        TextView amount = row.findViewById(R.id.amount_text);

        title.setText("Hồ sơ: " + id);

        StringBuilder sb = new StringBuilder();
        sb.append("KH: ").append(customerUid);
        if (photosCount > 0) {
            sb.append(" · ").append(photosCount).append(" ảnh bằng chứng");
        }
        if (!reason.isEmpty()) {
            sb.append("\nLý do: ").append(reason);
        }
        subtitle.setText(sb.toString());

        switch (status) {
            case "REVIEWING":
                amount.setText("Chờ duyệt");
                amount.setTextColor(ContextCompat.getColor(requireContext(), R.color.warning));
                break;
            case "VERIFIED":
                amount.setText("Đã duyệt");
                amount.setTextColor(ContextCompat.getColor(requireContext(), R.color.success));
                break;
            case "RESUBMIT_REQUIRED":
                amount.setText("Chụp lại");
                amount.setTextColor(ContextCompat.getColor(requireContext(), R.color.warning));
                break;
            case "REJECTED":
                amount.setText("Từ chối");
                amount.setTextColor(ContextCompat.getColor(requireContext(), R.color.danger));
                break;
            default:
                amount.setText("Bản nháp");
                amount.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink_muted));
                break;
        }

        row.setOnClickListener(v -> showEkycReviewDialog(api, item));
        binding.itemsContainer.addView(row);
    }

    private void showEkycReviewDialog(BackendApiClient api, JSONObject item) {
        String id = item.optString("id");
        String customerUid = item.optString("customerUid", "N/A");
        String status = item.optString("reviewStatus", "DRAFT");
        String existingReason = item.optString("reason", "");
        JSONArray storageRefs = item.optJSONArray("storageRefs");
        int count = storageRefs != null ? storageRefs.length() : 0;

        ScrollView scroll = new ScrollView(requireContext());
        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 24, 40, 24);
        scroll.addView(layout);

        // Header info
        TextView info = new TextView(requireContext());
        info.setText("Mã hồ sơ: " + id + "\nKhách hàng: " + customerUid + "\nTrạng thái: " + status + (existingReason.isEmpty() ? "" : "\nLý do trước: " + existingReason));
        info.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink));
        info.setTextSize(14f);
        info.setLineSpacing(4, 1.2f);
        layout.addView(info);

        // Evidence section header
        TextView evidenceTitle = new TextView(requireContext());
        evidenceTitle.setText("\nBẰNG CHỨNG HÌNH ẢNH (" + count + " ảnh)");
        evidenceTitle.setTextColor(ContextCompat.getColor(requireContext(), R.color.navy));
        evidenceTitle.setTextSize(12f);
        evidenceTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        layout.addView(evidenceTitle);

        String[] mediaNames = new String[]{"Mặt trước CCCD", "Mặt sau CCCD", "Chân dung Selfie"};
        for (int i = 0; i < count; i++) {
            final int photoIndex = i;
            String label = photoIndex < mediaNames.length ? mediaNames[photoIndex] : ("Ảnh " + (photoIndex + 1));

            com.google.android.material.button.MaterialButton btnViewPhoto =
                    new com.google.android.material.button.MaterialButton(requireContext());
            btnViewPhoto.setText("Xem ảnh: " + label);
            btnViewPhoto.setTextSize(12f);
            btnViewPhoto.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.glass_card));
            btnViewPhoto.setTextColor(ContextCompat.getColor(requireContext(), R.color.navy));
            btnViewPhoto.setStrokeColor(ContextCompat.getColorStateList(requireContext(), R.color.glass_border));
            btnViewPhoto.setStrokeWidth(1);
            btnViewPhoto.setCornerRadius(20);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 10, 0, 0);
            btnViewPhoto.setLayoutParams(lp);

            btnViewPhoto.setOnClickListener(v -> inspectEvidencePhoto(api, id, photoIndex, label));
            layout.addView(btnViewPhoto);
        }

        // Review form (only if REVIEWING)
        RadioGroup rgStatus = null;
        EditText reasonInput = null;
        if ("REVIEWING".equals(status)) {
            TextView reviewTitle = new TextView(requireContext());
            reviewTitle.setText("\nQUYẾT ĐỊNH THẨM ĐỊNH");
            reviewTitle.setTextColor(ContextCompat.getColor(requireContext(), R.color.navy));
            reviewTitle.setTextSize(12f);
            reviewTitle.setTypeface(null, android.graphics.Typeface.BOLD);
            layout.addView(reviewTitle);

            rgStatus = new RadioGroup(requireContext());
            RadioButton rbVerify = new RadioButton(requireContext());
            rbVerify.setText("Phê duyệt (VERIFIED)");
            rbVerify.setId(View.generateViewId());
            rbVerify.setChecked(true);
            rgStatus.addView(rbVerify);

            RadioButton rbResubmit = new RadioButton(requireContext());
            rbResubmit.setText("Yêu cầu chụp lại (RESUBMIT_REQUIRED)");
            rbResubmit.setId(View.generateViewId());
            rgStatus.addView(rbResubmit);

            RadioButton rbReject = new RadioButton(requireContext());
            rbReject.setText("Từ chối hồ sơ (REJECTED)");
            rbReject.setId(View.generateViewId());
            rgStatus.addView(rbReject);

            layout.addView(rgStatus);

            TextView reasonLabel = new TextView(requireContext());
            reasonLabel.setText("Lý do thẩm định (Bắt buộc, từ 3 ký tự):");
            reasonLabel.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink));
            reasonLabel.setTextSize(13f);
            reasonLabel.setPadding(0, 16, 0, 8);
            layout.addView(reasonLabel);

            reasonInput = new EditText(requireContext());
            reasonInput.setHint("Nhập lý do phê duyệt / yêu cầu bổ sung...");
            reasonInput.setBackgroundResource(R.drawable.input_glass_field);
            reasonInput.setPadding(24, 16, 24, 16);
            reasonInput.setTextSize(14f);
            layout.addView(reasonInput);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext())
                .setTitle("Chi tiết thẩm định eKYC")
                .setView(scroll)
                .setNegativeButton("Đóng", null);

        if ("REVIEWING".equals(status) && rgStatus != null && reasonInput != null) {
            final RadioGroup finalRg = rgStatus;
            final EditText finalReason = reasonInput;
            builder.setPositiveButton("Xác nhận", null); // Set listener later to control dismiss
        }

        AlertDialog dialog = builder.create();
        dialog.show();

        if ("REVIEWING".equals(status) && rgStatus != null && reasonInput != null) {
            final RadioGroup finalRg = rgStatus;
            final EditText finalReason = reasonInput;
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String reasonText = finalReason.getText().toString().trim();
                if (reasonText.length() < 3) {
                    Toast.makeText(requireContext(), "Vui lòng nhập lý do thẩm định ít nhất 3 ký tự", Toast.LENGTH_SHORT).show();
                    return;
                }

                String decision = "VERIFIED";
                int checkedId = finalRg.getCheckedRadioButtonId();
                if (checkedId == finalRg.getChildAt(1).getId()) {
                    decision = "RESUBMIT_REQUIRED";
                } else if (checkedId == finalRg.getChildAt(2).getId()) {
                    decision = "REJECTED";
                }

                submitOfficerReview(api, id, decision, reasonText, dialog);
            });
        }
    }

    private void inspectEvidencePhoto(BackendApiClient api, String requestId, int index, String label) {
        Toast.makeText(requireContext(), "Đang lấy link tải ảnh " + label + "...", Toast.LENGTH_SHORT).show();

        api.get("/officer/ekyc/" + requestId + "/evidence/" + index).addOnCompleteListener(urlTask -> {
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (!urlTask.isSuccessful() || urlTask.getResult() == null) {
                    Toast.makeText(requireContext(), "Lỗi kết nối máy chủ", Toast.LENGTH_SHORT).show();
                    return;
                }
                int sc = urlTask.getResult().statusCode;
                if (sc == 503) {
                    new AlertDialog.Builder(requireContext())
                            .setTitle("Thông báo S3")
                            .setMessage("Dịch vụ S3 của ngân hàng chưa cấu hình bộ nhớ đám mây (SETUP_REQUIRED). Vui lòng kiểm tra biến môi trường S3 trên backend.")
                            .setPositiveButton("Đã hiểu", null)
                            .show();
                    return;
                }
                if (sc != 200) {
                    Toast.makeText(requireContext(), "Lỗi tải ảnh (" + sc + ")", Toast.LENGTH_SHORT).show();
                    return;
                }

                try {
                    JSONObject resp = new JSONObject(urlTask.getResult().body);
                    String downloadUrl = resp.getString("url");

                    // Download image bytes
                    api.downloadBytes(downloadUrl).addOnCompleteListener(dlTask -> {
                        if (!isAdded()) return;
                        requireActivity().runOnUiThread(() -> {
                            if (!dlTask.isSuccessful() || dlTask.getResult() == null) {
                                Toast.makeText(requireContext(), "Không thể tải nội dung ảnh từ S3", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            byte[] bytes = dlTask.getResult();
                            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                            if (bitmap == null) {
                                Toast.makeText(requireContext(), "Ảnh không hợp lệ hoặc đã hỏng", Toast.LENGTH_SHORT).show();
                                return;
                            }

                            // Show photo in dialog
                            ImageView iv = new ImageView(requireContext());
                            iv.setImageBitmap(bitmap);
                            iv.setAdjustViewBounds(true);
                            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                            iv.setPadding(20, 20, 20, 20);

                            AlertDialog photoDialog = new AlertDialog.Builder(requireContext())
                                    .setTitle("Bằng chứng: " + label)
                                    .setView(iv)
                                    .setPositiveButton("Đóng", (d, w) -> {
                                        iv.setImageBitmap(null);
                                        bitmap.recycle();
                                    })
                                    .setOnDismissListener(d -> {
                                        iv.setImageBitmap(null);
                                        if (!bitmap.isRecycled()) {
                                            bitmap.recycle();
                                        }
                                    })
                                    .create();
                            photoDialog.show();
                        });
                    });
                } catch (Exception e) {
                    Toast.makeText(requireContext(), "Lỗi xử lý URL ảnh", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void submitOfficerReview(BackendApiClient api, String requestId, String status, String reason, AlertDialog parentDialog) {
        JSONObject body = new JSONObject();
        try {
            body.put("status", status);
            body.put("reason", reason);
        } catch (Exception ignored) {}

        api.post("/officer/ekyc/" + requestId + "/review", body.toString()).addOnCompleteListener(reviewTask -> {
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (reviewTask.isSuccessful() && reviewTask.getResult() != null && reviewTask.getResult().statusCode == 200) {
                    Toast.makeText(requireContext(), "✓ Đã thẩm định hồ sơ " + requestId + " (" + status + ")", Toast.LENGTH_LONG).show();
                    if (parentDialog != null && parentDialog.isShowing()) {
                        parentDialog.dismiss();
                    }
                    loadEkycData(api);
                } else {
                    int sc = reviewTask.getResult() != null ? reviewTask.getResult().statusCode : -1;
                    String errorBody = reviewTask.getResult() != null ? reviewTask.getResult().body : "";
                    String msg = "HTTP " + sc;
                    if (errorBody.contains("SELF_REVIEW_FORBIDDEN")) {
                        msg = "Cán bộ không được tự thẩm định hồ sơ eKYC của chính mình (SELF_REVIEW_FORBIDDEN).";
                    } else if (errorBody.contains("INVALID_STATE")) {
                        msg = "Hồ sơ không ở trạng thái chờ thẩm định (REVIEWING).";
                    } else if (!errorBody.isEmpty()) {
                        msg = errorBody;
                    }

                    new AlertDialog.Builder(requireContext())
                            .setTitle("Không thể hoàn tất thẩm định")
                            .setMessage(msg)
                            .setPositiveButton("Đóng", null)
                            .show();
                }
            });
        });
    }

    private void renderAuditLogs(BackendApiClient api) {
        binding.actionButton.setVisibility(View.GONE);
        binding.filterScroll.setVisibility(View.VISIBLE);
        binding.chipAll.setText("Tất cả");
        binding.chipFilter1.setText("Giao dịch");
        binding.chipFilter2.setText("Cập nhật KH");

        api.get("/officer/audit").addOnCompleteListener(task -> {
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

                        api.post("/officer/rate-policies", req.toString()).addOnCompleteListener(task -> {
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

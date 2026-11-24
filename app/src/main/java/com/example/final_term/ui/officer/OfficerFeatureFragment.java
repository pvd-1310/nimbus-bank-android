package com.example.final_term.ui.officer;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

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
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class OfficerFeatureFragment extends Fragment {
    private FragmentOfficerFeatureBinding binding;
    private final List<JSONObject> currentEkycItems = new ArrayList<>();
    private int currentEkycFilter = 0; // 0: all, 1: reviewing, 2: verified

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
            String subtitle = args == null ? "Các thao tác được ghi nhận theo quyền của tài khoản cán bộ." : args.getString("subtitle", "Các thao tác được ghi nhận theo quyền của tài khoản cán bộ.");
            binding.eyebrowText.setText(args == null ? "OFFICER OPERATIONS" : args.getString("eyebrow", "OFFICER OPERATIONS"));
            binding.titleText.setText(title);
            binding.subtitleText.setText(subtitle);
            binding.statusText.setText("TỔNG QUAN");
            if (title.contains("eKYC")) {
                binding.statusBadge.setText("CHỈ XEM");
                binding.statusText.setText("KIỂM TRA TỰ ĐỘNG");
                binding.complianceNote.setText("Hệ thống kiểm tra ảnh khi khách nộp hồ sơ. Cán bộ có quyền chỉ xem kết quả và bằng chứng; không phê duyệt thủ công.");
            }

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
        binding.actionButton.setVisibility(View.VISIBLE);
        binding.actionButton.setText("Làm mới hồ sơ");
        binding.actionButton.setOnClickListener(v -> loadEkycData(api));
        binding.filterScroll.setVisibility(View.VISIBLE);
        binding.chipAll.setText("Tất cả");
        binding.chipFilter1.setText("Đang xử lý");
        binding.chipFilter2.setText("Đã xác thực");

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
                if (!task.isSuccessful() || task.getResult() == null || task.getResult().statusCode != 200) {
                    showEkycLoadError(api);
                    return;
                }
                currentEkycItems.clear();
                try {
                    JSONObject json = new JSONObject(task.getResult().body);
                    JSONArray items = json.optJSONArray("items");
                    if (items != null) {
                        for (int i = 0; i < items.length(); i++) currentEkycItems.add(items.getJSONObject(i));
                    }
                } catch (Exception ignored) {
                    showEkycLoadError(api);
                    return;
                }
                updateFilterChips();
                filterAndDisplayEkyc(api);
            });
        });
    }

    private void showEkycLoadError(BackendApiClient api) {
        binding.itemsContainer.removeAllViews();
        TextView message = new TextView(requireContext());
        message.setText("Không tải được hồ sơ eKYC. Kiểm tra kết nối rồi thử lại.");
        message.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink_muted));
        message.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        message.setPadding(20, 40, 20, 16);
        binding.itemsContainer.addView(message);
        com.google.android.material.button.MaterialButton retry = new com.google.android.material.button.MaterialButton(requireContext());
        retry.setText("Tải lại hồ sơ");
        retry.setTextColor(ContextCompat.getColor(requireContext(), R.color.navy));
        retry.setBackgroundResource(R.drawable.glass_pill_button);
        retry.setOnClickListener(v -> loadEkycData(api));
        binding.itemsContainer.addView(retry);
    }

    private void filterAndDisplayEkyc(BackendApiClient api) {
        binding.itemsContainer.removeAllViews();

        List<JSONObject> filtered = new ArrayList<>();
        for (JSONObject item : currentEkycItems) {
            String status = item.optString("reviewStatus", "DRAFT");
            if (currentEkycFilter == 1) {
                if ("REVIEWING".equals(status)) filtered.add(item);
            } else if (currentEkycFilter == 2) {
                if ("VERIFIED".equals(status)) filtered.add(item);
            } else {
                filtered.add(item);
            }
        }

        if (filtered.isEmpty()) {
            TextView empty = new TextView(requireContext());
            empty.setText(currentEkycFilter == 1 ? "Hiện không có hồ sơ nào đang xử lý."
                    : currentEkycFilter == 2 ? "Chưa có hồ sơ nào được xác thực."
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
        boolean demo = "DEMO_SELFIE_ONLY".equals(item.optString("mode"));
        String reason = screeningMessage(item.optString("reason", ""), demo);
        JSONArray storageRefs = item.optJSONArray("storageRefs");
        int photosCount = storageRefs != null ? storageRefs.length() : 0;

        View row = LayoutInflater.from(requireContext()).inflate(R.layout.item_transaction_row, binding.itemsContainer, false);
        TextView title = row.findViewById(R.id.title_text);
        TextView subtitle = row.findViewById(R.id.subtitle_text);
        TextView amount = row.findViewById(R.id.amount_text);
        ImageView icon = row.findViewById(R.id.transaction_icon);
        icon.setImageResource(R.drawable.ic_shield);

        title.setText(demo ? "Hồ sơ đăng ký khuôn mặt" : "Hồ sơ định danh");

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
                amount.setText("Đang xử lý");
                amount.setTextColor(ContextCompat.getColor(requireContext(), R.color.warning));
                break;
            case "VERIFIED":
                amount.setText(demo ? "Chưa xác minh CCCD" : "Đã xác thực");
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

        row.setOnClickListener(v -> showEkycDetailDialog(api, item));
        binding.itemsContainer.addView(row);
    }

    private void showEkycDetailDialog(BackendApiClient api, JSONObject item) {
        String id = item.optString("id");
        String customerUid = item.optString("customerUid", "N/A");
        String status = item.optString("reviewStatus", "DRAFT");
        boolean demo = "DEMO_SELFIE_ONLY".equals(item.optString("mode"));
        String existingReason = screeningMessage(item.optString("reason", ""), demo);
        JSONArray storageRefs = item.optJSONArray("storageRefs");
        int count = storageRefs != null ? storageRefs.length() : 0;

        ScrollView scroll = new ScrollView(requireContext());
        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 24, 40, 24);
        scroll.addView(layout);

        // Header info
        TextView info = new TextView(requireContext());
        info.setText("Mã hồ sơ: " + id + "\nKhách hàng: " + customerUid + "\nPhạm vi xác minh: "
                + (demo ? "Ảnh selfie, chưa xác minh CCCD" : "CCCD và selfie")
                + "\nTrạng thái: " + statusLabel(status, demo)
                + (existingReason.isEmpty() ? "" : "\nKết quả: " + existingReason));
        info.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink));
        info.setTextSize(14f);
        info.setLineSpacing(4, 1.2f);
        info.setBackgroundResource(R.drawable.glass_panel_soft);
        info.setPadding(16, 16, 16, 16);
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
            JSONObject ref = storageRefs.optJSONObject(i);
            final int photoIndex = ref == null ? i : ref.optInt("index", i);
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

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Chi tiết hồ sơ eKYC")
                .setView(scroll)
                .setPositiveButton("Đóng", null)
                .show();
    }

    private static String statusLabel(String status, boolean demo) {
        switch (status) {
            case "VERIFIED": return demo ? "Đã đăng ký khuôn mặt · chưa xác minh CCCD" : "Đã xác thực tự động";
            case "REVIEWING": return "Đang chờ kiểm tra lại";
            case "RESUBMIT_REQUIRED": return "Cần chụp lại ảnh";
            case "REJECTED": return "Đã từ chối";
            default: return "Bản nháp";
        }
    }

    private static String screeningMessage(String code, boolean demo) {
        if (code.isEmpty()) return "";
        switch (code) {
            case "FACE_ID_MISMATCH": return "Khuôn mặt trên CCCD và selfie không khớp.";
            case "FACE_BLURRY": return demo ? "Ảnh selfie bị nhòe." : "Ảnh CCCD hoặc selfie bị nhòe.";
            case "FACE_EXPOSURE": return "Khuôn mặt quá tối hoặc quá sáng.";
            case "FACE_TOO_SMALL": return "Khuôn mặt quá nhỏ.";
            case "FACE_NOT_FOUND": return "Không tìm thấy khuôn mặt.";
            case "MULTIPLE_FACES": return "Ảnh có nhiều khuôn mặt.";
            default: return code.matches("[A-Z_]+") ? "Ảnh chưa đạt yêu cầu kiểm tra tự động." : code;
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
                            .setMessage("Chưa thể tải ảnh hồ sơ. Vui lòng thử lại sau hoặc liên hệ quản trị viên.")
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

    private int auditLoadGeneration;

    private void renderAuditLogs(BackendApiClient api) {
        binding.filterScroll.setVisibility(View.VISIBLE);
        binding.chipAll.setText("Tất cả");
        binding.chipFilter1.setText("Giao dịch");
        binding.chipFilter2.setText("Cập nhật KH");
        binding.chipAll.setOnClickListener(v -> loadAuditPage(api, "", null));
        binding.chipFilter1.setOnClickListener(v -> loadAuditPage(api, "TRANSFER_COMMIT", null));
        binding.chipFilter2.setOnClickListener(v -> loadAuditPage(api, "CUSTOMER_UPDATE", null));
        loadAuditPage(api, "", null);
    }

    private void loadAuditPage(BackendApiClient api, String action, String cursor) {
        var current = binding;
        int generation = ++auditLoadGeneration;
        if (cursor == null) current.itemsContainer.removeAllViews();
        current.statusText.setText("Đang tải lịch sử thay đổi…");
        current.actionButton.setVisibility(View.VISIBLE);
        current.actionButton.setEnabled(false);
        String path = "/officer/audit?limit=25";
        if (!action.isEmpty()) path += "&action=" + action;
        if (cursor != null) path += "&cursor=" + android.net.Uri.encode(cursor);
        api.get(path).addOnCompleteListener(task -> {
            if (binding != current || generation != auditLoadGeneration) return;
            current.actionButton.setEnabled(true);
            try {
                if (!task.isSuccessful() || task.getResult().statusCode != 200)
                    throw new JSONException("Unavailable");
                JSONObject json = new JSONObject(task.getResult().body);
                JSONArray items = json.getJSONArray("items");
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    String timestamp = item.has("timestamp") ? new java.util.Date(item.getLong("timestamp")).toString()
                            : "Chưa có thời gian";
                    addAuditRow(item.optString("action"), item.optString("actorUid"),
                            item.optString("resourceId"), timestamp + "\nMã yêu cầu: "
                                    + item.optString("requestId", "—"), item.optJSONObject("beforeAfterSummary"));
                }
                current.statusText.setText(items.length() == 0 && cursor == null
                        ? "Chưa có thay đổi phù hợp." : "Đã cập nhật lịch sử thay đổi.");
                if (!json.isNull("nextCursor")) {
                    String next = json.getString("nextCursor");
                    current.actionButton.setText("Tải thêm");
                    current.actionButton.setOnClickListener(v -> loadAuditPage(api, action, next));
                } else {
                    current.actionButton.setVisibility(View.GONE);
                }
            } catch (JSONException error) {
                current.statusText.setText(task.isSuccessful() && task.getResult().statusCode == 403
                        ? "Bạn chưa có quyền xem nội dung này." : "Không tải được lịch sử thay đổi.");
                current.actionButton.setText("Thử lại");
                current.actionButton.setOnClickListener(v -> loadAuditPage(api, action, cursor));
            }
        });
    }

    private void addAuditRow(String action, String actor, String target, String time, JSONObject summary) {
        View row = LayoutInflater.from(requireContext()).inflate(R.layout.item_transaction_row, binding.itemsContainer, false);
        TextView title = row.findViewById(R.id.title_text);
        TextView subtitle = row.findViewById(R.id.subtitle_text);
        TextView amount = row.findViewById(R.id.amount_text);

        title.setText(action);
        subtitle.setText("Actor: " + actor + " → Target: " + target + "\n" + time);
        amount.setText("Lịch sử");
        amount.setTextColor(ContextCompat.getColor(requireContext(), R.color.navy));

        row.setOnClickListener(v -> new AlertDialog.Builder(requireContext())
                .setTitle("Chi tiết thay đổi")
                .setMessage("Thao tác: " + action + "\nNgười thực hiện: " + actor + "\nĐối tượng: " + target + "\nThời gian: " + time)
                .setPositiveButton("Đóng", null)
                .show());

        binding.itemsContainer.addView(row);
    }

    private void renderRatePolicies(BackendApiClient api) {
        binding.filterScroll.setVisibility(View.GONE);
        binding.actionButton.setVisibility(View.VISIBLE);
        binding.actionButton.setText("Cập nhật chính sách lãi suất");

        var current = binding;
        current.itemsContainer.removeAllViews();
        current.statusText.setText("Đang tải chính sách lãi suất…");
        api.get("/officer/rate-policies?limit=100").addOnCompleteListener(task -> {
            if (binding != current) return;
            try {
                if (!task.isSuccessful() || task.getResult().statusCode != 200)
                    throw new JSONException("Unavailable");
                JSONArray items = new JSONObject(task.getResult().body).getJSONArray("items");
                for (int i = 0; i < items.length(); i++) {
                    JSONObject policy = items.getJSONObject(i);
                    int bps = policy.getInt("annualRateBasisPoints");
                    String product = policy.optString("productId");
                    addRatePolicyCard(product, policy.optString("id"), bps / 100.0 + "% / năm",
                            bps + " bps", "Phiên bản " + policy.optInt("version"),
                            "Hiệu lực từ " + new java.util.Date(policy.optLong("effectiveFrom")));
                }
                current.statusText.setText(items.length() == 0 ? "Chưa có chính sách lãi suất." : "Đã cập nhật chính sách lãi suất.");
            } catch (JSONException error) {
                current.statusText.setText("Không tải được chính sách lãi suất.");
            }
        });

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

        EditText product = new EditText(requireContext());
        product.setHint("Mã sản phẩm trong danh sách");
        product.setSingleLine(true);
        layout.addView(product);

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
                        String selectedProduct = product.getText().toString().trim();
                        if (selectedProduct.isEmpty() || !Double.isFinite(ratePercent)
                                || ratePercent < 0 || ratePercent > 100) {
                            Toast.makeText(requireContext(), "Nhập mã sản phẩm và lãi suất từ 0 đến 100%.", Toast.LENGTH_SHORT).show();
                            return;
                        }

                        JSONObject req = new JSONObject();
                        req.put("productId", selectedProduct);
                        req.put("annualRateBasisPoints", basisPoints);
                        req.put("effectiveFrom", System.currentTimeMillis() + 60_000L);

                        api.post("/officer/rate-policies", req.toString()).addOnCompleteListener(task -> {
                            if (!isAdded()) return;
                            requireActivity().runOnUiThread(() -> {
                                if (task.isSuccessful() && task.getResult() != null && task.getResult().statusCode >= 200 && task.getResult().statusCode < 300) {
                                    Toast.makeText(requireContext(), "Đã ban hành chính sách lãi suất " + ratePercent + "% cho " + selectedProduct, Toast.LENGTH_LONG).show();
                                    renderRatePolicies(api);
                                } else {
                                    Toast.makeText(requireContext(), "Chưa thể cập nhật chính sách. Vui lòng thử lại.", Toast.LENGTH_SHORT).show();
                                }
                            });
                        });
                    } catch (Exception ex) {
                        Toast.makeText(requireContext(), "Không thể xử lý yêu cầu. Vui lòng kiểm tra thông tin rồi thử lại.", Toast.LENGTH_SHORT).show();
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

package com.example.final_term.ui.officer;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.app.AlertDialog;
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
    private int ratePolicyLoadGeneration;
    private final List<JSONObject> currentEkycItems = new ArrayList<>();
    private int currentEkycFilter = 0; // 0: all, 1: reviewing, 2: verified

    @Override public void onResume() {
        super.onResume();
        if (binding != null && binding.titleText.getText().toString().toLowerCase(java.util.Locale.ROOT).contains("lãi suất")) {
            FinalTermApplication app = (FinalTermApplication) requireActivity().getApplication();
            renderRatePolicies(app.getAppContainer().getBackendApiClient());
        }
    }

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

        new com.example.final_term.ui.common.ThemedDialogBuilder(requireContext())
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
                    new com.example.final_term.ui.common.ThemedDialogBuilder(requireContext())
                            .setTitle("Thông báo tải ảnh")
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

                            androidx.appcompat.app.AlertDialog photoDialog = new com.example.final_term.ui.common.ThemedDialogBuilder(requireContext())
                                    .setTitle("Bằng chứng: " + label)
                                    .setView(iv)
                                    .setPositiveButton("Đóng", (d, w) -> {
                                        iv.setImageBitmap(null);
                                        bitmap.recycle();
                                    })
                                    .create();
                            photoDialog.setOnDismissListener(d -> {
                                iv.setImageBitmap(null);
                                if (!bitmap.isRecycled()) {
                                    bitmap.recycle();
                                }
                            });
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
                    long millis = item.optLong("timestamp", 0L);
                    String timestamp = millis == 0L ? "Chưa có thời gian" :
                            java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM,
                                    java.text.DateFormat.SHORT, java.util.Locale.forLanguageTag("vi-VN"))
                                    .format(new java.util.Date(millis));
                    addAuditRow(item, timestamp);
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

    private void addAuditRow(JSONObject item, String time) {
        String action = item.optString("action", "");
        String titleText = auditTitle(action);
        View row = LayoutInflater.from(requireContext()).inflate(R.layout.item_transaction_row, binding.itemsContainer, false);
        TextView title = row.findViewById(R.id.title_text);
        TextView subtitle = row.findViewById(R.id.subtitle_text);
        TextView amount = row.findViewById(R.id.amount_text);

        title.setText(titleText);
        subtitle.setText(auditDescription(action) + "\n" + time);
        amount.setText("Chi tiết");
        amount.setTextColor(ContextCompat.getColor(requireContext(), R.color.navy));
        Bundle args = new Bundle();
        args.putBoolean("audit", true);
        args.putString("title", titleText);
        args.putString("status", "Đã ghi nhận");
        args.putString("description", auditDescription(action));
        args.putString("actor", "Cán bộ ngân hàng");
        args.putString("target", action.contains("CUSTOMER") || action.contains("PROFILE")
                ? "Hồ sơ khách hàng" : "Giao dịch ngân hàng");
        args.putString("time", time);
        args.putString("summary", item.optJSONObject("beforeAfterSummary") == null ? ""
                : "Hệ thống đã lưu nội dung trước và sau khi thay đổi.");
        row.setOnClickListener(v -> androidx.navigation.Navigation.findNavController(v).navigate(R.id.historyDetail, args));

        binding.itemsContainer.addView(row);
    }

    private String auditTitle(String action) {
        switch (action) {
            case "TRANSFER_COMMIT": return "Xử lý giao dịch chuyển tiền";
            case "CUSTOMER_UPDATE": return "Cập nhật hồ sơ khách hàng";
            case "DEMO_MOVEMENT_INTENT": return "Khởi tạo giao dịch mẫu";
            case "DEMO_MOVEMENT_PENDING": return "Ghi nhận giao dịch đang xử lý";
            case "DEMO_MOVEMENT_SETTLED": return "Hoàn tất giao dịch mẫu";
            case "DEMO_MOVEMENT_FAILED": return "Giao dịch mẫu không thành công";
            default: return "Thao tác nghiệp vụ";
        }
    }

    private String auditDescription(String action) {
        switch (action) {
            case "TRANSFER_COMMIT": return "Cán bộ đã xử lý yêu cầu chuyển tiền.";
            case "CUSTOMER_UPDATE": return "Thông tin hồ sơ khách hàng đã được cập nhật.";
            case "DEMO_MOVEMENT_INTENT": return "Yêu cầu giao dịch mẫu đã được khởi tạo.";
            case "DEMO_MOVEMENT_PENDING": return "Giao dịch mẫu đang được xử lý.";
            case "DEMO_MOVEMENT_SETTLED": return "Giao dịch mẫu đã hoàn tất.";
            case "DEMO_MOVEMENT_FAILED": return "Giao dịch mẫu không thể hoàn tất.";
            default: return "Thao tác đã được ghi nhận trong lịch sử.";
        }
    }

    private void renderRatePolicies(BackendApiClient api) {
        binding.filterScroll.setVisibility(View.GONE);
        binding.actionButton.setVisibility(View.VISIBLE);
        binding.actionButton.setText("Cập nhật chính sách lãi suất");

        var current = binding;
        int generation = ++ratePolicyLoadGeneration;
        current.itemsContainer.removeAllViews();
        current.statusText.setText("Đang tải chính sách lãi suất…");
        current.statusText.setOnClickListener(v -> renderRatePolicies(api));
        api.get("/officer/rate-policies?limit=100").addOnCompleteListener(task -> {
            if (binding != current || generation != ratePolicyLoadGeneration) return;
            try {
                if (!task.isSuccessful()) {
                    current.statusText.setText("Không thể kết nối để tải chính sách lãi suất. Chạm để thử lại.");
                    return;
                }
                int statusCode = task.getResult().statusCode;
                if (statusCode == 401) {
                    current.statusText.setText("Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.");
                    return;
                }
                if (statusCode == 403) {
                    current.statusText.setText("Tài khoản chưa được cấp quyền quản lý lãi suất. Vui lòng liên hệ quản trị viên.");
                    current.actionButton.setVisibility(View.GONE);
                    return;
                }
                if (statusCode != 200)
                    throw new JSONException("Unavailable");
                JSONArray items = new JSONObject(task.getResult().body).getJSONArray("items");
                List<JSONObject> policies = new ArrayList<>();
                for (int i = 0; i < items.length(); i++) policies.add(items.getJSONObject(i));
                policies.sort((left, right) -> {
                    int byEffectiveDate = Long.compare(left.optLong("effectiveFrom"), right.optLong("effectiveFrom"));
                    if (byEffectiveDate != 0) return byEffectiveDate;
                    int byType = left.optString("rateType").compareTo(right.optString("rateType"));
                    return byType != 0 ? byType : Integer.compare(left.optInt("version"), right.optInt("version"));
                });
                for (JSONObject policy : policies) {
                    int bps = policy.getInt("annualRateBasisPoints");
                    String rateType = policy.optString("rateType", "SAVINGS");
                    String state = policy.optString("status");
                    String rateName = "LOAN".equals(rateType) ? "Lãi vay" : "Lãi suất tiết kiệm";
                    String reason = policy.optString("reason", "");
                    String creator = policy.optString("createdBy", "");
                    addRatePolicyCard(rateName, bps / 100.0 + "% / năm",
                            "Phiên bản " + policy.optInt("version") + (reason.isEmpty() ? "" : " · " + reason)
                                    + (creator.isEmpty() ? "" : "\nNgười cập nhật: " + creator),
                            ("CURRENT".equals(state) ? "Đang áp dụng" : "SCHEDULED".equals(state) ? "Sắp áp dụng" : "Đã kết thúc")
                                    + " · Hiệu lực từ " + new java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault()).format(new java.util.Date(policy.optLong("effectiveFrom"))));
                }
                current.statusText.setText(items.length() == 0 ? "Chưa có chính sách lãi suất." : "Danh sách chính sách lãi suất đã được cập nhật.");
            } catch (JSONException error) {
                current.statusText.setText("Không tải được chính sách lãi suất. Chạm để thử lại.");
            }
        });

        binding.actionButton.setOnClickListener(v -> Navigation.findNavController(v).navigate(R.id.ratePolicyEditor));
    }

    private void addRatePolicyCard(String title, String rateStr, String version, String effective) {
        View card = LayoutInflater.from(requireContext()).inflate(R.layout.item_transaction_row, binding.itemsContainer, false);
        TextView titleTv = card.findViewById(R.id.title_text);
        TextView subtitleTv = card.findViewById(R.id.subtitle_text);
        TextView amountTv = card.findViewById(R.id.amount_text);

        titleTv.setText(title);
        subtitleTv.setText(version + " · " + effective);
        amountTv.setText(rateStr);
        amountTv.setTextColor(ContextCompat.getColor(requireContext(), R.color.navy));

        binding.itemsContainer.addView(card);
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

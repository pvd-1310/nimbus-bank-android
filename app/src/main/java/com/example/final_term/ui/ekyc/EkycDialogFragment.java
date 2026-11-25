package com.example.final_term.ui.ekyc;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModelProvider;

import com.example.final_term.R;
import com.example.final_term.core.FinalTermApplication;
import com.example.final_term.data.BackendApiClient;
import com.example.final_term.databinding.DialogEkycBinding;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class EkycDialogFragment extends DialogFragment {
    private static final int PHOTO_COUNT = 3;
    private static final int SELFIE = 2;
    private static final String CAMERA_TAG = "ekyc-in-app-camera";
    private static final String[] PHOTO_LABELS = {"Mặt trước CCCD", "Mặt sau CCCD", "Chân dung Selfie"};
    private static final String[] INSTRUCTIONS = {
            "Chọn ảnh rõ toàn bộ mặt trước CCCD, thấy ảnh chân dung trên thẻ, tránh lóa và rung.",
            "Chọn ảnh rõ toàn bộ mặt sau CCCD, đủ sáng và không bị che khuất.",
            "Chỉ một người trong ảnh; nhìn thẳng, đủ sáng và giữ máy ổn định."
    };
    private DialogEkycBinding binding;
    private CaptureState state;
    private boolean cameraDialogOpen;
    private boolean identityDialogOpen;

    private final ActivityResultLauncher<String> gallery = registerForActivityResult(
            new ActivityResultContracts.GetContent(), uri -> {
                if (state == null) return;
                if (uri == null) state.cancelSelection(null);
                else state.gallerySelected(uri);
            });

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NO_TITLE, 0);
        state = new ViewModelProvider(this).get(CaptureState.class);
        if (!state.started) state.load();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = DialogEkycBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        binding.btnBack.setOnClickListener(v -> dismiss());
        binding.btnCancelConsent.setOnClickListener(v -> dismiss());
        binding.btnConsent.setOnClickListener(v -> state.startDraft());
        getChildFragmentManager().setFragmentResultListener(InAppCameraDialogFragment.RESULT_KEY,
                getViewLifecycleOwner(), (key, result) -> {
                    if (state == null) return;
                    if (result.containsKey("switch_slot")) {
                        cameraDialogOpen = true;
                        int selectedSlot = result.getInt("switch_slot", -1);
                        state.cancelSelection(null);
                        state.select(selectedSlot);
                        View root = getView();
                        if (root != null) root.post(() -> {
                            cameraDialogOpen = false;
                            render();
                        });
                        return;
                    }
                    cameraDialogOpen = false;
                    String path = result.getString("path");
                    String error = result.getString("error");
                    if (path != null) state.cameraFileSelected(path);
                    else state.cameraFailed(error == null ? "Đã hủy chụp ảnh." : error);
                });
        binding.btnHelp.setOnClickListener(v -> new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Trợ giúp định danh eKYC")
                .setMessage(state.isDemo()
                        ? "Chụp selfie ngay trong ứng dụng, đủ sáng và chỉ có một khuôn mặt. Bước này chưa xác minh CCCD hoặc danh tính pháp lý."
                        : "Chụp hoặc tải ảnh mặt trước, mặt sau CCCD; sau đó chụp khuôn mặt ngay trong ứng dụng. Bạn có thể xem và chụp lại ảnh trước khi nộp.")
                .setPositiveButton("Đóng", null)
                .show());
        binding.btnShutter.setContentDescription("Chụp ảnh bằng camera trong ứng dụng");
        binding.btnShutter.setFocusable(true);
        binding.btnShutter.setOnClickListener(v -> requestCapture());
        binding.btnUpload.setOnClickListener(v -> requestGallery());
        binding.btnRetake.setOnClickListener(v -> requestCapture());
        binding.btnRetakeUpload.setOnClickListener(v -> requestGallery());
        binding.chipFront.setOnClickListener(v -> state.select(0));
        binding.chipBack.setOnClickListener(v -> state.select(1));
        binding.chipSelfie.setOnClickListener(v -> state.select(SELFIE));
        binding.btnSubmitEkyc.setOnClickListener(v -> state.submit());
        cameraDialogOpen = getChildFragmentManager().findFragmentByTag(CAMERA_TAG) != null;
        binding.statusHeadline.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        binding.shutterHint.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        render();
        state.changes.observe(getViewLifecycleOwner(), ignored -> render());
    }

    @Override
    public void onStart() {
        super.onStart();
        Window window = requireDialog().getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawableResource(android.R.color.transparent);
            if (getView() != null) com.example.final_term.ui.common.FullscreenInsets.apply(window, getView());
        }
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }

    private void requestCapture() {
        if (!state.reserveSelection()) return;
        showCameraForReservedSlot();
    }

    private void showCameraForReservedSlot() {
        cameraDialogOpen = true;
        render();
        int pendingSlot = state.pendingSlot();
        if (pendingSlot == SELFIE) {
            FaceScanDialogFragment.newForEkycSelfie()
                    .show(getChildFragmentManager(), CAMERA_TAG);
        } else {
            InAppCameraDialogFragment.newInstance(pendingSlot, state.canSelect(0), state.canSelect(1))
                    .show(getChildFragmentManager(), CAMERA_TAG);
        }
    }

    private void maybeOpenCameraForCurrentStep() {
        if (state.needsIdentity()) { state.autoCameraPending = false; return; }
        if (cameraDialogOpen || !state.autoCameraPending || state.photoBusy() || state.progress != null) return;
        if (state.slot < 0 || state.slot >= PHOTO_COUNT || state.previews[state.slot] != null) {
            state.autoCameraPending = false;
            return;
        }
        state.autoCameraPending = false;
        cameraDialogOpen = true;
        if (!state.reserveSelection()) {
            cameraDialogOpen = false;
            state.autoCameraPending = true;
            return;
        }
        render();
        showCameraForReservedSlot();
    }

    private void requestGallery() {
        if (state == null || state.pendingSlot() == SELFIE || !state.reserveSelection()) return;
        gallery.launch("image/*");
    }

    private void render() {
        if (binding == null) return;
        binding.dialogTitle.setText(state.isDemo() ? "Đăng ký khuôn mặt" : "Định danh eKYC");
        setCancelable(!state.submitting);
        binding.btnBack.setEnabled(!state.submitting);
        if (state.profileChanged) {
            state.profileChanged = false;
            getParentFragmentManager().setFragmentResult("ekyc_status_changed", Bundle.EMPTY);
        }

        boolean showStatus = state.progress != null || state.error != null
                || (!state.consent && !state.isDraft());
        // The legacy capture view is retained for final review/submission, but must not
        // flash underneath the full-screen Camera2 flow between automatically opened steps.
        boolean cameraHandoff = cameraDialogOpen || state.autoCameraPending || state.photoBusy();
        boolean showCapture = !showStatus && !state.consent && !cameraHandoff;
        visible(binding.layoutStatus, showStatus);
        visible(binding.layoutConsent, !showStatus && state.consent);
        visible(binding.layoutCapture, showCapture);
        if (showCapture) renderCapture();
        else if (showStatus) renderStatus();
        else if (state.consent) renderConsent();

        // Auto-open must run from render(), since the capture layout is deliberately
        // hidden while the camera handoff is pending.
        if (state.needsIdentity() && !cameraDialogOpen && !state.photoBusy() && state.progress == null) showIdentityPreview();
        else maybeOpenCameraForCurrentStep();
    }

    private void showIdentityPreview() {
        if (identityDialogOpen || !isAdded()) return;
        identityDialogOpen = true;
        state.autoCameraPending = false;
        state.beginProgress("Đang đọc thông tin CCCD…");
        String draftId = state.saved.get(CaptureState.DRAFT_ID);
        Tasks.call(state.files, () -> Files.readAllBytes(state.photo(0).toPath()))
                .continueWithTask(task -> {
                    if (!task.isSuccessful() || draftId == null) throw new IOException("Cannot read image");
                    return state.api.recognizeIdentity(draftId, task.getResult());
                }).addOnCompleteListener(task -> {
                    if (draftId == null || !state.currentDraft(draftId)) { identityDialogOpen = false; state.finishProgress(); return; }
                    state.finishProgress();
                    if (binding == null || !isAdded()) { identityDialogOpen = false; return; }
                    try {
                        if (!task.isSuccessful() || task.getResult().statusCode != 200) throw new IOException("Recognition failed");
                        displayIdentityForm(new JSONObject(task.getResult().body));
                    } catch (Exception error) {
                        new MaterialAlertDialogBuilder(requireContext()).setTitle("Không đọc được CCCD")
                                .setMessage("Kiểm tra kết nối và dịch vụ nhận diện, hoặc chụp lại mặt trước CCCD rõ nét, đủ bốn góc.")
                                .setCancelable(false)
                                .setNegativeButton("Chụp lại", (d, w) -> { state.select(0); requestCapture(); identityDialogOpen = false; })
                                .setPositiveButton("Thử lại", (d, w) -> { identityDialogOpen = false; showIdentityPreview(); })
                                .show();
                    }
                });
    }

    private void displayIdentityForm(JSONObject values) {
        if (binding == null || !isAdded()) { identityDialogOpen = false; return; }
        LinearLayout form = new LinearLayout(requireContext());
        form.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        form.setPadding(padding, 0, padding, 0);
        TextView help = new TextView(requireContext());
        help.setText("Kiểm tra và chỉnh sửa thông tin theo CCCD. FPT.AI có thể đọc sai hoặc bỏ sót.");
        help.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink_muted));
        help.setTextSize(14);
        help.setPadding(0, 0, 0, (int) (16 * getResources().getDisplayMetrics().density));
        form.addView(help);
        String[] labels = {"Số định danh *", "Họ và tên *", "Ngày sinh *", "Giới tính", "Quốc tịch", "Địa chỉ *", "Ngày hết hạn"};
        String[] keys = {"nationalIdNumber", "fullName", "dateOfBirth", "gender", "nationality", "address", "identityDocumentExpiry"};
        EditText[] fields = new EditText[keys.length];
        LinearLayout table = new LinearLayout(requireContext());
        table.setOrientation(LinearLayout.VERTICAL);
        table.setBackgroundResource(R.drawable.glass_panel_soft);
        float density = getResources().getDisplayMetrics().density;
        for (int i = 0; i < keys.length; i++) {
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding((int) (12 * density), (int) (4 * density), (int) (12 * density), (int) (4 * density));
            TextView label = new TextView(requireContext());
            label.setText(labels[i]);
            label.setTextSize(13);
            label.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink_muted));
            row.addView(label, new LinearLayout.LayoutParams((int) (100 * density), ViewGroup.LayoutParams.WRAP_CONTENT));
            fields[i] = new EditText(requireContext());
            fields[i].setSingleLine(!keys[i].equals("address"));
            fields[i].setMinHeight((int) (48 * density));
            fields[i].setTextSize(15);
            fields[i].setTextColor(ContextCompat.getColor(requireContext(), R.color.ink));
            fields[i].setBackgroundColor(android.graphics.Color.TRANSPARENT);
            fields[i].setPadding((int) (8 * density), 0, 0, 0);
            fields[i].setHint(keys[i].equals("dateOfBirth") || keys[i].equals("identityDocumentExpiry") ? "YYYY-MM-DD" : "Nhấn để nhập");
            fields[i].setContentDescription(labels[i]);
            if (keys[i].equals("nationalIdNumber")) fields[i].setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            fields[i].setText(values.optString(keys[i], ""));
            row.addView(fields[i], new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            table.addView(row);
            if (i < keys.length - 1) {
                View divider = new View(requireContext());
                divider.setBackgroundColor(0x22345678);
                LinearLayout.LayoutParams separator = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, (int) density));
                separator.setMargins((int) (12 * density), 0, (int) (12 * density), 0);
                table.addView(divider, separator);
            }
        }
        form.addView(table);
        ScrollView scroll = new ScrollView(requireContext());
        scroll.addView(form);
        var dialog = new MaterialAlertDialogBuilder(requireContext()).setTitle("Kiểm tra thông tin CCCD")
                .setView(scroll).setCancelable(false).setNegativeButton("Chụp lại CCCD", (d, w) -> {
                    state.select(0); requestCapture(); identityDialogOpen = false;
                }).setPositiveButton("Xác nhận", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            JSONObject identity = new JSONObject();
            try {
                for (int i = 0; i < keys.length; i++) identity.put(keys[i], fields[i].getText().toString().trim());
                if (identity.optString("fullName").length() < 2 || !identity.optString("nationalIdNumber").matches("[0-9]{12}")
                        || !validDate(identity.optString("dateOfBirth")) || identity.optString("address").isEmpty()
                        || (!identity.optString("identityDocumentExpiry").isEmpty() && !validDate(identity.optString("identityDocumentExpiry")))) {
                    help.setText("Vui lòng nhập đủ thông tin bắt buộc, số định danh 12 số và ngày hợp lệ theo YYYY-MM-DD."); return;
                }
                state.confirmedIdentity = identity;
                identityDialogOpen = false;
                dialog.dismiss();
                state.slot = SELFIE;
                state.saved.set("slot", SELFIE);
                state.autoCameraPending = true;
                state.changed();
            } catch (JSONException ignored2) { help.setText("Không thể lưu thông tin. Vui lòng thử lại."); }
        }));
        dialog.show();
    }

    private static boolean validDate(String value) {
        try { java.time.LocalDate.parse(value, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE); return true; }
        catch (Exception ignored) { return false; }
    }

    private void renderConsent() {
        if (state.isDemo()) {
            binding.consentHeadline.setText("Đăng ký khuôn mặt");
            binding.consentSummary.setText("Bạn chưa cần cung cấp ảnh CCCD. Ảnh selfie được lưu riêng tư để đăng ký và đăng nhập bằng khuôn mặt. Phương thức này chưa xác minh danh tính.");
            binding.consentPurpose.setText("Chụp một ảnh selfie bằng camera ngay trong ứng dụng để tạo mẫu khuôn mặt cho tài khoản này.");
            binding.consentStorage.setText("Ảnh được tải lên vùng lưu trữ riêng tư bằng URL có thời hạn. Hệ thống kiểm tra chất lượng khuôn mặt; cán bộ có quyền chỉ xem bằng chứng.");
            binding.consentLimit.setText("Bước này chưa xác minh CCCD, danh tính pháp lý hoặc phát hiện ảnh giả.");
        } else {
            binding.consentHeadline.setText("Thu thập & Xử lý dữ liệu định danh");
            binding.consentSummary.setText("Ảnh định danh được lưu trong vùng riêng tư và chỉ được dùng cho bước xác thực bạn đồng ý thực hiện.");
            binding.consentPurpose.setText("Thu thập ảnh CCCD mặt trước, mặt sau và selfie để lưu hồ sơ định danh, đồng thời tạo mẫu khuôn mặt dùng cho bước xác thực giao dịch.");
            binding.consentStorage.setText("Ảnh CCCD mặt trước được gửi đến FPT.AI để trích xuất thông tin; ảnh được tải lên bộ nhớ riêng tư làm bằng chứng. Selfie được dùng để tạo mẫu khuôn mặt.");
            binding.consentLimit.setText("Bạn cần kiểm tra và xác nhận thông tin được trích xuất. Hệ thống chưa xác thực tính thật của CCCD, chưa đối chiếu khuôn mặt với thẻ hoặc phát hiện ảnh giả.");
        }
    }

    private void renderCapture() {
        int slot = state.slot;
        Bitmap preview = state.previews[slot];
        boolean ready = preview != null;
        boolean canEdit = !state.photoBusy();
        boolean demo = state.isDemo();
        visible(binding.captureStepper, !demo);
        visible(binding.layoutStepChips, !demo);
        binding.ekycInstructionTitle.setText(slot == SELFIE ? "Chụp khuôn mặt" : "Chụp hoặc tải ảnh Căn cước công dân");
        binding.ekycInstructionSubtitle.setText(INSTRUCTIONS[slot]);
        binding.frameLabel.setText(PHOTO_LABELS[slot]);
        binding.ivCapturedPreview.setContentDescription(PHOTO_LABELS[slot] + " đã chọn");
        binding.ivCapturedPreview.setImageBitmap(preview);
        visible(binding.ivCapturedPreview, ready);
        visible(binding.frameCccd, !ready && slot != SELFIE);
        visible(binding.frameFace, !ready && slot == SELFIE);
        visible(binding.badgeCaptured, ready);
        visible(binding.layoutRetakeAction, ready);
        visible(binding.layoutCaptureActions, !ready);
        visible(binding.btnUpload, !ready && slot != SELFIE && !demo);
        binding.btnShutter.setText(slot == SELFIE ? "Quét khuôn mặt tự động" : "Chụp bằng camera");
        binding.btnShutter.setEnabled(canEdit);
        binding.btnUpload.setEnabled(canEdit);
        binding.btnRetake.setEnabled(canEdit);
        visible(binding.btnRetakeUpload, ready && slot != SELFIE && !demo);
        binding.btnRetakeUpload.setEnabled(canEdit);
        String notice = state.notice;
        visible(binding.shutterHint, !ready || notice != null || state.processingPhoto);
        binding.shutterHint.setText(notice != null ? notice : state.processingPhoto
                ? "Đang chuẩn bị ảnh…" : slot == SELFIE
                ? "Đưa mặt vào khung hình và chụp bằng camera trong ứng dụng"
                : "Đưa thẻ vào khung camera hoặc tải ảnh có sẵn lên");

        updateIndicator(binding.step1Indicator, slot != SELFIE, false);
        updateIndicator(binding.step2Indicator, slot == SELFIE, false);
        updateIndicator(binding.chipFront, slot == 0, true);
        updateIndicator(binding.chipBack, slot == 1, true);
        updateIndicator(binding.chipSelfie, slot == SELFIE, true);
        binding.chipFront.setEnabled(canEdit && state.canSelect(0));
        binding.chipBack.setEnabled(canEdit && state.canSelect(1));
        binding.chipSelfie.setEnabled(canEdit && state.canSelect(SELFIE));
        visible(binding.btnSubmitEkyc, state.allPhotosReady());
        binding.btnSubmitEkyc.setText(demo ? "Đăng ký khuôn mặt" : "Nộp và kiểm tra ảnh (3/3)");
        binding.btnSubmitEkyc.setEnabled(canEdit);
    }

    private void updateIndicator(TextView view, boolean selected, boolean chip) {
        view.setSelected(selected);
        if (chip || selected) {
            view.setBackgroundResource(selected ? R.drawable.navy_button_pill : R.drawable.glass_panel_soft);
        } else {
            view.setBackground(null);
        }
        view.setTextColor(ContextCompat.getColor(requireContext(), selected ? R.color.white
                : chip ? R.color.ink : R.color.ink_faint));
        view.setTypeface(view.getTypeface(), selected ? Typeface.BOLD : Typeface.NORMAL);
    }

    private void renderStatus() {
        boolean working = state.progress != null;
        boolean rejected = "REJECTED".equals(state.reviewStatus());
        boolean resubmit = "RESUBMIT_REQUIRED".equals(state.reviewStatus());
        String reason = "";
        String headline;
        String detail = "";
        String action = "Đóng";
        Runnable onAction = this::dismiss;
        int icon = R.drawable.ic_shield;
        int tint = R.color.navy;
        if (working) {
            headline = state.progress;
            detail = state.submitting ? "Vui lòng không đóng ứng dụng trong khi nộp hồ sơ." : "Vui lòng chờ trong giây lát…";
        } else if (state.error != null) {
            headline = "Không thể hoàn tất thao tác";
            detail = state.error;
            action = state.errorAction;
            onAction = state.retry;
            icon = R.drawable.ic_close;
            tint = R.color.danger;
        } else if (rejected || resubmit) {
            String rawReason = state.latest.isNull("reason") ? "" : state.latest.optString("reason").trim();
            boolean serviceUnavailable = "FACE_SETUP_REQUIRED".equals(rawReason)
                    || "FACE_TIMEOUT".equals(rawReason) || "FACE_BUSY".equals(rawReason)
                    || "SETUP_REQUIRED".equals(rawReason);
            headline = rejected ? "Hồ sơ bị từ chối" : serviceUnavailable
                    ? "Chưa thể kiểm tra hồ sơ" : "Cần chọn lại ảnh";
            detail = rejected ? "Hồ sơ không đạt yêu cầu xác minh."
                    : serviceUnavailable ? "Dịch vụ nhận diện khuôn mặt đang gặp sự cố. Hồ sơ đã được trả lại để bạn gửi lại khi dịch vụ hoạt động."
                    : state.isDemo()
                    ? "Ảnh selfie chưa đạt yêu cầu. Bắt đầu lượt mới và chọn ảnh khuôn mặt rõ nét trong ánh sáng đều."
                    : "Bạn có thể bắt đầu hồ sơ mới để gửi lại ảnh định danh. Hãy bảo đảm hai mặt CCCD rõ nét và selfie có một khuôn mặt, đủ sáng.";
            reason = rejected ? rawReason : explainAutoReview(rawReason, state.isDemo());
            action = "Bắt đầu hồ sơ mới";
            onAction = state::showConsent;
            icon = rejected ? R.drawable.ic_close : R.drawable.ic_shield;
            tint = rejected ? R.color.danger : R.color.warning;
        } else if ("VERIFIED".equals(state.reviewStatus())) {
            headline = state.isDemo() ? "Đã đăng ký khuôn mặt" : "Đã ghi nhận hồ sơ eKYC";
            detail = state.isDemo()
                    ? "Hệ thống đã kiểm tra ảnh selfie và đăng ký mẫu khuôn mặt. Chưa xác minh CCCD hoặc danh tính pháp lý."
                    : "Hệ thống đã kiểm tra chất lượng selfie và tạo mẫu khuôn mặt. Ảnh CCCD được lưu làm bằng chứng; hệ thống chưa OCR hoặc xác thực tính thật của giấy tờ.";
            icon = R.drawable.ic_check;
            tint = R.color.success;
        } else {
            headline = "Hồ sơ đang xử lý";
            detail = "Hồ sơ chưa có kết quả cuối cùng. Chạm bên dưới để kiểm tra lại ảnh đã nộp.";
            action = "Thử kiểm tra lại";
            onAction = state::retryReview;
        }
        if (!working && state.error == null && state.latest != null && !state.latest.isNull("requestId")) {
            detail += "\nMã hồ sơ: " + state.latest.optString("requestId");
        }
        visible(binding.statusProgressBar, working);
        visible(binding.statusIconImage, !working);
        binding.statusIconImage.setImageResource(icon);
        binding.statusIconImage.setColorFilter(ContextCompat.getColor(requireContext(), tint));
        binding.statusHeadline.setText(headline);
        binding.statusDetail.setText(detail);
        visible(binding.cardOfficerReason, !reason.isEmpty());
        binding.tvOfficerReason.setText(reason);
        visible(binding.btnStatusAction, !working);
        binding.btnStatusAction.setText(action);
        final Runnable statusAction = onAction;
        binding.btnStatusAction.setOnClickListener(v -> statusAction.run());
    }

    private static String explainAutoReview(String code, boolean demo) {
        switch (code) {
            case "FACE_SETUP_REQUIRED":
            case "SETUP_REQUIRED": return "Dịch vụ nhận diện khuôn mặt chưa sẵn sàng. Hồ sơ đã được trả lại để bạn gửi lại sau.";
            case "FACE_TIMEOUT": return "Dịch vụ nhận diện khuôn mặt phản hồi quá thời gian. Hồ sơ đã được trả lại để bạn gửi lại sau.";
            case "FACE_BUSY": return "Dịch vụ nhận diện khuôn mặt đang bận. Hồ sơ đã được trả lại để bạn gửi lại sau.";
            case "FACE_TEMPLATE_REVOKED": return "Mẫu khuôn mặt đã được thu hồi. Bắt đầu hồ sơ mới để đăng ký lại eKYC.";
            case "FACE_BLURRY": return "Ảnh selfie bị nhòe. Giữ máy ổn định và chụp trong ánh sáng đều.";
            case "FACE_EXPOSURE": return "Khuôn mặt quá tối hoặc quá sáng. Hãy chọn ảnh có ánh sáng đều.";
            case "FACE_TOO_SMALL": return "Khuôn mặt quá nhỏ trong ảnh. Hãy chọn ảnh chân dung gần hơn.";
            case "FACE_CROPPED": return "Khuôn mặt bị cắt khỏi khung hình. Hãy chọn ảnh thấy trọn khuôn mặt.";
            case "FACE_ID_MISMATCH": return "Khuôn mặt trên mặt trước CCCD và selfie không khớp. Hãy kiểm tra và chọn lại ảnh.";
            case "MULTIPLE_FACES": return "Ảnh có nhiều khuôn mặt. Hãy chọn ảnh chỉ có một người.";
            case "FACE_NOT_FOUND": return "Không tìm thấy khuôn mặt trong ảnh selfie. Hãy chụp lại rõ mặt.";
            default: return demo ? "Ảnh selfie chưa đạt yêu cầu. Hãy chọn ảnh khác rõ nét hơn."
                    : "Ảnh selfie chưa đạt yêu cầu. Hãy chụp lại rõ nét, đủ sáng và chỉ có một khuôn mặt.";
        }
    }

    private static void visible(View view, boolean show) {
        view.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    // The dialog owns this state; configuration changes keep pending gallery and network work alive.
    public static final class CaptureState extends AndroidViewModel {
        private static final int MAX_BYTES = 5_000_000;
        private static final String JPEG = "image/jpeg";
        private static final String LATEST = "/ekyc?limit=1";
        private static final String PENDING_SLOT = "pendingSlot";
        private static final String RAW_READY = "rawReady";
        private static final String DRAFT_ID = "draftId";

        private final SavedStateHandle saved;
        private final BackendApiClient api;
        private final ExecutorService files = Executors.newSingleThreadExecutor();
        private final File directory;
        private final File rawPhoto;
        private final boolean[] confirmed = new boolean[PHOTO_COUNT];
        private final Bitmap[] previews = new Bitmap[PHOTO_COUNT];
        private final MutableLiveData<Boolean> changes = new MutableLiveData<>();

        private JSONObject latest;
        private JSONObject confirmedIdentity;
        private String captureMode = "FULL_ID";
        private String submissionStatus = "";
        private boolean started;
        private boolean closed;
        private boolean restoredPhotos;
        private boolean autoCameraPending;
        private boolean consent;
        private boolean processingPhoto;
        private boolean submitting;
        private boolean profileChanged;
        private int slot;
        private String progress;
        private String error;
        private String errorAction;
        private String notice;
        private Runnable retry;

        public CaptureState(@NonNull Application application, @NonNull SavedStateHandle saved) {
            super(application);
            this.saved = saved;
            api = ((FinalTermApplication) application).getAppContainer().getBackendApiClient();
            String session = saved.get("session");
            if (session == null) {
                session = UUID.randomUUID().toString();
                saved.set("session", session);
            }
            directory = new File(application.getCacheDir(), "ekyc-gallery/" + session);
            rawPhoto = new File(directory, "selected-image");
            Integer selected = saved.get("slot");
            slot = selected == null ? 0 : selected;
        }

        private void load() {
            started = true;
            beginProgress("Đang tải trạng thái eKYC…");
            api.get(LATEST).addOnCompleteListener(task -> {
                if (closed) return;
                try {
                    acceptLatest(readLatest(task));
                    profileChanged = true;
                    if (!"VERIFIED".equals(reviewStatus())) {
                        if ("STORAGE_SETUP_REQUIRED".equals(submissionStatus)) {
                            fail("Chưa thể tải ảnh hồ sơ. Vui lòng thử lại sau.", "Thử lại", this::load);
                            return;
                        }
                        if ("FACE_SETUP_REQUIRED".equals(submissionStatus)) {
                            fail("Chưa thể kiểm tra khuôn mặt lúc này. Vui lòng thử lại sau.", "Thử lại", this::load);
                            return;
                        }
                    }
                    finishProgress();
                } catch (IOException | JSONException e) {
                    fail("Không tải được trạng thái hồ sơ. Vui lòng thử lại.", "Thử lại", this::load);
                }
            });
        }

        private void retryReview() {
            if (latest == null) { load(); return; }
            String id = latest.optString("id");
            if (id.isEmpty()) { load(); return; }
            saved.set(DRAFT_ID, id);
            submitDraft(id);
        }

        private void showConsent() {
            consent = true;
            changed();
        }

        private void startDraft() {
            if (progress != null || !consent) return;
            beginProgress("Đang lưu xác nhận đồng ý…");
            api.post("/ekyc", "{\"consented\":true}").addOnCompleteListener(task -> {
                if (closed) return;
                try {
                    JSONObject draft = readRecord(responseJson(task));
                    if (!"DRAFT".equals(draft.getString("reviewStatus"))) throw new JSONException("Not a draft");
                    acceptLatest(draft);
                    slot = nextRequiredSlot();
                    saved.set("slot", slot);
                    autoCameraPending = missingRequiredSlot() != -1;
                    profileChanged = true;
                    finishProgress();
                } catch (IOException | JSONException e) {
                    fail("Không thể mở hồ sơ eKYC. Vui lòng kiểm tra kết nối và thử lại.", "Thử lại", this::load);
                }
            });
        }

        private void acceptLatest(@Nullable JSONObject record) {
            latest = record;
            if (record != null) captureMode = record.optString("mode", "FULL_ID");
            consent = record == null;
            String previousDraft = saved.get(DRAFT_ID);
            String activeDraft = isDraft() ? latest.optString("id") : null;
            if (activeDraft == null || !activeDraft.equals(previousDraft)) {
                saved.set(DRAFT_ID, activeDraft);
                clearPhotos();
            } else if (!restoredPhotos) {
                restorePhotos(activeDraft);
            }
            if (isDemo()) {
                slot = SELFIE;
                saved.set("slot", SELFIE);
            }
        }

        private boolean isDemo() {
            return "DEMO_SELFIE_ONLY".equals(captureMode);
        }

        private void restorePhotos(String draftId) {
            restoredPhotos = true;
            processingPhoto = true;
            Tasks.call(files, () -> {
                Bitmap[] restored = new Bitmap[PHOTO_COUNT];
                for (int i = 0; i < PHOTO_COUNT; i++) {
                    File photo = photo(i);
                    if (!photo.isFile()) continue;
                    try {
                        restored[i] = decodeBounded(photo, 640);
                    } catch (IOException | IllegalArgumentException e) {
                        photo.delete();
                    }
                }
                return restored;
            }).addOnCompleteListener(task -> {
                if (!currentDraft(draftId)) return;
                processingPhoto = false;
                if (task.isSuccessful()) {
                    Bitmap[] restored = task.getResult();
                    for (int i = 0; i < PHOTO_COUNT; i++) {
                        if (previews[i] == null) previews[i] = restored[i];
                    }
                }
                if (Boolean.TRUE.equals(saved.get(RAW_READY))) {
                    processSelectedPhoto();
                } else {
                    slot = nextRequiredSlot();
                    saved.set("slot", slot);
                    autoCameraPending = missingRequiredSlot() != -1;
                    changed();
                }
            });
        }

        private boolean isDraft() {
            return "DRAFT".equals(reviewStatus());
        }

        private String reviewStatus() {
            return latest == null ? "" : latest.optString("reviewStatus");
        }

        private boolean currentDraft(String draftId) {
            return !closed && draftId.equals(saved.get(DRAFT_ID));
        }

        private void select(int index) {
            if (photoBusy() || !canSelect(index)) return;
            slot = index;
            saved.set("slot", index);
            autoCameraPending = previews[index] == null;
            notice = null;
            changed();
        }

        private int nextRequiredSlot() {
            if (isDemo()) return SELFIE;
            for (int i = 0; i < PHOTO_COUNT; i++) if (previews[i] == null) return i;
            return SELFIE;
        }

        private int missingRequiredSlot() {
            if (isDemo()) return previews[SELFIE] == null ? SELFIE : -1;
            for (int i = 0; i < PHOTO_COUNT; i++) if (previews[i] == null) return i;
            return -1;
        }

        private boolean canSelect(int index) {
            if (index < 0 || index >= PHOTO_COUNT) return false;
            if (isDemo()) return index == SELFIE;
            if (index == SELFIE && confirmedIdentity == null) return false;
            for (int i = 0; i < PHOTO_COUNT; i++) {
                if (previews[i] == null) return index <= i;
            }
            return true;
        }

        private int pendingSlot() {
            Integer pending = saved.get(PENDING_SLOT);
            return pending == null ? -1 : pending;
        }

        private boolean photoBusy() {
            return processingPhoto || pendingSlot() != -1;
        }

        private boolean reserveSelection() {
            if (closed || !isDraft() || photoBusy() || progress != null) return false;
            saved.set(PENDING_SLOT, slot);
            notice = null;
            changed();
            return true;
        }

        private void cancelSelection(@Nullable String message) {
            if (closed || pendingSlot() == -1) return;
            rawPhoto.delete();
            saved.remove(PENDING_SLOT);
            saved.remove(RAW_READY);
            notice = message;
            changed();
        }

        private void gallerySelected(Uri uri) {
            if (closed || pendingSlot() == -1) return;
            processingPhoto = true;
            changed();
            Tasks.call(files, () -> {
                Files.createDirectories(directory.toPath());
                try (InputStream input = getApplication().getContentResolver().openInputStream(uri);
                     FileOutputStream output = new FileOutputStream(rawPhoto)) {
                    if (input == null) throw new IOException("Image is unavailable");
                    byte[] buffer = new byte[8192];
                    long total = 0;
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        total += read;
                        if (total > 30_000_000L) throw new IOException("Image is too large");
                        output.write(buffer, 0, read);
                    }
                    if (total == 0) throw new IOException("Image is empty");
                }
                return true;
            }).addOnCompleteListener(task -> {
                if (closed || pendingSlot() == -1) return;
                processingPhoto = false;
                if (task.isSuccessful()) {
                    saved.set(RAW_READY, true);
                    if (isDraft()) processSelectedPhoto();
                } else {
                    cancelSelection("Không đọc được ảnh đã chọn. Hãy thử chọn ảnh khác.");
                }
            });
        }

        private void cameraFileSelected(String path) {
            if (closed || pendingSlot() == -1) return;
            processingPhoto = true;
            changed();
            Tasks.call(files, () -> {
                File captured = new File(path);
                try {
                    if (!captured.isFile() || captured.length() == 0 || captured.length() > 30_000_000L) {
                        throw new IOException("Invalid camera image");
                    }
                    Files.createDirectories(directory.toPath());
                    Files.copy(captured.toPath(), rawPhoto.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    captured.delete();
                }
                if (!rawPhoto.isFile()) {
                    throw new IOException("Invalid camera image");
                }
                return true;
            }).addOnCompleteListener(task -> {
                if (closed || pendingSlot() == -1) return;
                processingPhoto = false;
                if (task.isSuccessful()) {
                    saved.set(RAW_READY, true);
                    if (isDraft()) processSelectedPhoto();
                } else {
                    cancelSelection("Không lưu được ảnh camera. Vui lòng chụp lại.");
                }
            });
        }

        private void cameraFailed(String message) {
            cancelSelection(message);
        }

        private void processSelectedPhoto() {
            String draftId = saved.get(DRAFT_ID);
            int index = pendingSlot();
            if (draftId == null || index == -1 || processingPhoto) return;
            processingPhoto = true;
            changed();
            Tasks.call(files, () -> normalizePhoto(index)).addOnCompleteListener(task -> {
                if (!currentDraft(draftId)) return;
                processingPhoto = false;
                saved.remove(PENDING_SLOT);
                saved.remove(RAW_READY);
                if (task.isSuccessful()) {
                    if (index != SELFIE) confirmedIdentity = null;
                    clearLaterPhotos(index);
                    if (previews[index] != null) previews[index].recycle();
                    previews[index] = task.getResult();
                    confirmed[index] = false;
                    slot = nextRequiredSlot();
                    saved.set("slot", slot);
                    autoCameraPending = missingRequiredSlot() != -1 && !needsIdentity();
                    notice = null;
                    changed();
                } else {
                    notice = "Không đọc được ảnh hoặc ảnh quá lớn. Vui lòng chọn ảnh khác.";
                    changed();
                }
            });
        }

        private void clearLaterPhotos(int index) {
            for (int i = index + 1; i < PHOTO_COUNT; i++) {
                if (previews[i] != null) previews[i].recycle();
                previews[i] = null;
                confirmed[i] = false;
                photo(i).delete();
            }
        }

        private Bitmap normalizePhoto(int index) throws IOException {
            File staged = new File(directory, "normalized.jpg");
            try {
                Bitmap image = decodeBounded(rawPhoto, 2400);
                try {
                    encodeJpeg(image, staged, 90);
                    if (staged.length() > MAX_BYTES) encodeJpeg(image, staged, 75);
                } finally {
                    image.recycle();
                }
                if (staged.length() < 1 || staged.length() > MAX_BYTES) throw new IOException("Invalid photo size");
                Bitmap preview = decodeBounded(staged, 640);
                Files.move(staged.toPath(), photo(index).toPath(), StandardCopyOption.REPLACE_EXISTING);
                return preview;
            } finally {
                staged.delete();
                rawPhoto.delete();
            }
        }

        private static Bitmap decodeBounded(File photo, int maxEdge) throws IOException {
            if (Build.VERSION.SDK_INT >= 28) return ImageDecoder.decodeBitmap(ImageDecoder.createSource(photo), (decoder, info, source) -> {
                int width = info.getSize().getWidth();
                int height = info.getSize().getHeight();
                if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid image");
                double scale = Math.min(1.0, (double) maxEdge / Math.max(width, height));
                decoder.setTargetSize(Math.max(1, (int) (width * scale)), Math.max(1, (int) (height * scale)));
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(photo.getPath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Invalid image");
            BitmapFactory.Options options = new BitmapFactory.Options();
            while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > maxEdge * 2) options.inSampleSize *= 2;
            Bitmap original = BitmapFactory.decodeFile(photo.getPath(), options);
            if (original == null) throw new IOException("Invalid image");
            int orientation = new ExifInterface(photo.getPath()).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            Matrix matrix = new Matrix();
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90: matrix.setRotate(90); break;
                case ExifInterface.ORIENTATION_ROTATE_180: matrix.setRotate(180); break;
                case ExifInterface.ORIENTATION_ROTATE_270: matrix.setRotate(270); break;
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.setScale(-1, 1); break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL: matrix.setScale(1, -1); break;
                case ExifInterface.ORIENTATION_TRANSPOSE: matrix.setScale(-1, 1); matrix.postRotate(270); break;
                case ExifInterface.ORIENTATION_TRANSVERSE: matrix.setScale(-1, 1); matrix.postRotate(90); break;
                default: break;
            }
            Bitmap oriented = Bitmap.createBitmap(original, 0, 0, original.getWidth(), original.getHeight(), matrix, true);
            if (oriented != original) original.recycle();
            int largest = Math.max(oriented.getWidth(), oriented.getHeight());
            if (largest <= maxEdge) return oriented;
            float scale = (float) maxEdge / largest;
            Bitmap resized = Bitmap.createScaledBitmap(oriented,
                    Math.max(1, Math.round(oriented.getWidth() * scale)),
                    Math.max(1, Math.round(oriented.getHeight() * scale)), true);
            if (resized != oriented) oriented.recycle();
            return resized;
        }

        private static void encodeJpeg(Bitmap image, File destination, int quality) throws IOException {
            try (FileOutputStream out = new FileOutputStream(destination)) {
                if (!image.compress(Bitmap.CompressFormat.JPEG, quality, out)) throw new IOException("JPEG encode failed");
            }
        }

        private File photo(int index) {
            return new File(directory, "photo-" + index + ".jpg");
        }

        private boolean allPhotosReady() {
            if (isDemo()) return previews[SELFIE] != null;
            if (confirmedIdentity == null) return false;
            for (Bitmap preview : previews) if (preview == null) return false;
            return true;
        }

        private void submit() {
            if (!isDraft() || photoBusy() || !allPhotosReady() || progress != null) return;
            submitting = true;
            uploadNext(latest.optString("id"), 0);
        }

        private void uploadNext(String draftId, int index) {
            while (index < PHOTO_COUNT && ((isDemo() && index != SELFIE) || confirmed[index])) index++;
            if (index == PHOTO_COUNT) {
                submitDraft(draftId);
                return;
            }
            final int slot = index;
            beginProgress(isDemo() ? "Đang tải ảnh selfie…" : "Đang tải ảnh " + (slot + 1) + "/" + PHOTO_COUNT + "…");
            Tasks.call(files, () -> {
                File evidence = photo(slot);
                long length = evidence.length();
                if (length < 1 || length > MAX_BYTES) throw new IOException("Invalid evidence size");
                return Files.readAllBytes(evidence.toPath());
            }).addOnCompleteListener(task -> {
                if (!currentDraft(draftId)) return;
                if (!task.isSuccessful()) {
                    previews[slot] = null;
                    select(slot);
                    submitFailed("Không đọc được ảnh đã chọn. Vui lòng chọn lại ảnh này.");
                    return;
                }
                byte[] bytes = task.getResult();
                String body = "{\"mime\":\"" + JPEG + "\",\"bytes\":" + bytes.length + ",\"index\":" + slot + "}";
                api.post("/ekyc/" + Uri.encode(draftId) + "/uploads", body).addOnCompleteListener(ticketTask -> {
                    if (!currentDraft(draftId)) return;
                    try {
                        JSONObject ticket = responseJson(ticketTask);
                        JSONObject upload = ticket.getJSONObject("upload");
                        String uploadId = ticket.getString("id");
                        api.uploadToPresignedPost(upload.getString("url"), upload.getJSONObject("fields"), JPEG, bytes)
                                .addOnCompleteListener(uploadTask -> confirmUpload(draftId, slot, uploadId, uploadTask));
                    } catch (IOException | JSONException e) {
                        submitFailed("Không thể tạo lượt tải ảnh. Vui lòng thử nộp lại.");
                    }
                });
            });
        }

        private void confirmUpload(String draftId, int index, String uploadId, Task<BackendApiClient.Response> task) {
            if (!currentDraft(draftId)) return;
            if (!successful(task)) {
                submitFailed("Tải ảnh lên không thành công. Vui lòng thử nộp lại.");
                return;
            }
            api.post("/uploads/" + Uri.encode(uploadId) + "/confirm", "{}").addOnCompleteListener(confirmTask -> {
                if (!currentDraft(draftId)) return;
                if (!successful(confirmTask)) {
                    submitFailed("Máy chủ chưa xác nhận ảnh. Vui lòng thử nộp lại.");
                    return;
                }
                confirmed[index] = true;
                uploadNext(draftId, index + 1);
            });
        }

        private void submitDraft(String draftId) {
            beginProgress(isDemo() ? "Đang kiểm tra ảnh selfie…" : "Đang kiểm tra ảnh định danh…");
            JSONObject payload = new JSONObject();
            try { if (confirmedIdentity != null) payload.put("identity", confirmedIdentity); }
            catch (JSONException ignored) { submitFailed("Không thể chuẩn bị thông tin CCCD."); return; }
            api.post("/ekyc/" + Uri.encode(draftId) + "/submit", payload.toString()).addOnCompleteListener(task -> {
                if (!currentDraft(draftId)) return;
                try {
                    if (applySubmitted(readRecord(responseJson(task)), draftId)) return;
                } catch (IOException | JSONException ignored) {
                    // The server may have saved the submission before the connection failed.
                }
                api.get(LATEST).addOnCompleteListener(statusTask -> {
                    if (!currentDraft(draftId)) return;
                    try {
                        if (applySubmitted(readLatest(statusTask), draftId)) return;
                    } catch (IOException | JSONException ignored) {
                        // Keep the captures for retry if the current server state cannot be read.
                    }
                    submitFailed("Chưa xác nhận được việc nộp hồ sơ. Vui lòng thử nộp lại.");
                });
            });
        }

        private boolean needsIdentity() {
            return isDraft() && !isDemo() && previews[0] != null && previews[1] != null && confirmedIdentity == null;
        }

        private boolean applySubmitted(@Nullable JSONObject record, String draftId) {
            if (record == null || !draftId.equals(record.optString("id")) || "DRAFT".equals(record.optString("reviewStatus"))) {
                return false;
            }
            acceptLatest(record);
            profileChanged = true;
            finishProgress();
            return true;
        }

        private void submitFailed(String message) {
            fail(message, "Quay lại ảnh", () -> {
                error = null;
                retry = null;
                changed();
            });
        }

        private void beginProgress(String message) {
            progress = message;
            error = null;
            retry = null;
            changed();
        }

        private void finishProgress() {
            submitting = false;
            progress = null;
            changed();
        }

        private void fail(String message, String actionLabel, Runnable action) {
            error = message;
            errorAction = actionLabel;
            retry = action;
            finishProgress();
        }

        private void changed() {
            if (!closed) changes.setValue(true);
        }

        @Nullable
        private JSONObject readLatest(Task<BackendApiClient.Response> task) throws IOException, JSONException {
            JSONObject payload = responseJson(task);
            captureMode = payload.optString("captureMode", "FULL_ID");
            submissionStatus = payload.optString("submissionStatus");
            JSONArray items = payload.getJSONArray("items");
            return items.length() == 0 ? null : readRecord(items.getJSONObject(0));
        }

        private static JSONObject readRecord(JSONObject record) throws JSONException {
            if (record.getString("id").isEmpty()) throw new JSONException("Missing request id");
            switch (record.getString("reviewStatus")) {
                case "DRAFT":
                case "REVIEWING":
                case "VERIFIED":
                case "REJECTED":
                case "RESUBMIT_REQUIRED":
                    return record;
                default:
                    throw new JSONException("Unknown review status");
            }
        }

        private static JSONObject responseJson(Task<BackendApiClient.Response> task) throws IOException, JSONException {
            if (!successful(task)) throw new IOException("eKYC request failed");
            return new JSONObject(task.getResult().body);
        }

        private static boolean successful(Task<BackendApiClient.Response> task) {
            if (!task.isSuccessful() || task.getResult() == null) return false;
            int code = task.getResult().statusCode;
            return code >= 200 && code < 300;
        }

        private void clearPhotos() {
            confirmedIdentity = null;
            saved.remove(PENDING_SLOT);
            saved.remove(RAW_READY);
            slot = 0;
            saved.set("slot", slot);
            restoredPhotos = true;
            processingPhoto = false;
            notice = null;
            for (int i = 0; i < PHOTO_COUNT; i++) {
                previews[i] = null;
                confirmed[i] = false;
            }
            autoCameraPending = isDraft();
            deleteLocalFiles();
        }

        private void deleteLocalFiles() {
            File[] photos = directory.listFiles();
            if (photos != null) for (File file : photos) file.delete();
            directory.delete();
        }

        @Override
        protected void onCleared() {
            closed = true;
            if (directory.exists()) files.execute(this::deleteLocalFiles);
            files.shutdown();
        }
    }
}

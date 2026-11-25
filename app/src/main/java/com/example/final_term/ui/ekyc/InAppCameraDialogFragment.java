package com.example.final_term.ui.ekyc;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;
import com.example.final_term.R;
import com.google.android.material.button.MaterialButton;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** In-app eKYC capture using CameraX. Result contract remains compatible with EkycDialogFragment. */
public final class InAppCameraDialogFragment extends DialogFragment {
    public static final String RESULT_KEY = "ekyc_in_app_camera_result";
    private static final String ARG_SLOT = "slot", ARG_FRONT_CAMERA = "front_camera", ARG_ALLOW_FRONT = "allow_front", ARG_ALLOW_BACK = "allow_back";
    private static final String RESULT_SWITCH_SLOT = "switch_slot", RESULT_PATH = "path", RESULT_ERROR = "error";
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ActivityResultLauncher<String> permission = registerForActivityResult(new ActivityResultContracts.RequestPermission(), ok -> { if (ok) startCamera(); else finishWithError("Cần quyền camera để chụp ảnh định danh."); });
    private final ActivityResultLauncher<String> gallery = registerForActivityResult(new ActivityResultContracts.GetContent(), this::gallerySelected);
    private LinearLayout sideSwitcher; private TextView previewStatus; private View captureButton; private MaterialButton flashButton;
    private PreviewView preview; private ImageView capturedPhoto; private TextView message; private View cameraControls, reviewControls; private MaterialButton capture, flash;
    private ProcessCameraProvider provider; private ImageCapture imageCapture; private Camera camera; private File pendingPhoto; private boolean resultSent, flashEnabled;

    public static InAppCameraDialogFragment newInstance(int slot, boolean allowFront, boolean allowBack) { InAppCameraDialogFragment f = new InAppCameraDialogFragment(); Bundle b = new Bundle(); b.putInt(ARG_SLOT, slot); b.putBoolean(ARG_FRONT_CAMERA, slot == 2); b.putBoolean(ARG_ALLOW_FRONT, allowFront); b.putBoolean(ARG_ALLOW_BACK, allowBack); f.setArguments(b); return f; }
    @Override public void onCreate(@Nullable Bundle state) { super.onCreate(state); setStyle(STYLE_NO_TITLE, R.style.Theme_Final_term); setCancelable(true); }
    @Nullable
    @Override
    public View onCreateView(@NonNull android.view.LayoutInflater inflater,
                             @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        FrameLayout root = new FrameLayout(requireContext());
        int[] colors = {
                color(R.color.bg_mist), color(R.color.bg_fog),
                color(R.color.bg_slate_soft), color(R.color.bg_slate),
        };
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors));


        LinearLayout page = new LinearLayout(requireContext());
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(12), dp(20), dp(20));
        root.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout header = new LinearLayout(requireContext());
        header.setGravity(Gravity.CENTER_VERTICAL);
        MaterialButton back = iconButton(R.drawable.ic_arrow_back, "Quay lại");
        back.setOnClickListener(v -> dismiss());
        header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView title = text("Định danh eKYC", 18, color(R.color.ink), true);
        title.setGravity(Gravity.CENTER);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        MaterialButton help = iconButton(R.drawable.ic_help, "Trợ giúp định danh");
        help.setOnClickListener(v -> new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("Hướng dẫn chụp ảnh")
                .setMessage(isFrontCamera()
                        ? "Giữ điện thoại ngang tầm mắt, nhìn thẳng và đưa toàn bộ khuôn mặt vào khung."
                        : "Đặt toàn bộ mặt thẻ vào khung, giữ máy ổn định và tránh ánh sáng phản chiếu.")
                .setPositiveButton("Đã hiểu", null)
                .show());
        header.addView(help, new LinearLayout.LayoutParams(dp(44), dp(44)));
        page.addView(header);

        LinearLayout stepper = new LinearLayout(requireContext());
        stepper.setOrientation(LinearLayout.HORIZONTAL);
        stepper.setGravity(Gravity.CENTER);
        stepper.setPadding(dp(4), dp(4), dp(4), dp(4));
        stepper.setBackground(rounded(0x80FFFFFF, dp(28), 0x99FFFFFF, dp(1)));
        TextView idStep = text("1. Chụp CCCD", 12,
                isFrontCamera() ? color(R.color.ink_muted) : Color.WHITE, true);
        idStep.setGravity(Gravity.CENTER);
        idStep.setPadding(dp(14), dp(8), dp(14), dp(8));
        idStep.setBackground(rounded(isFrontCamera() ? 0x00FFFFFF : color(R.color.navy),
                dp(24), isFrontCamera() ? Color.TRANSPARENT : color(R.color.navy), dp(1)));
        TextView faceStep = text("2. Khuôn mặt", 12,
                isFrontCamera() ? Color.WHITE : color(R.color.ink_muted), true);
        faceStep.setGravity(Gravity.CENTER);
        faceStep.setPadding(dp(14), dp(8), dp(14), dp(8));
        faceStep.setBackground(rounded(isFrontCamera() ? color(R.color.navy) : 0x00FFFFFF,
                dp(24), isFrontCamera() ? color(R.color.navy) : Color.TRANSPARENT, dp(1)));
        stepper.addView(idStep);
        stepper.addView(faceStep);
        LinearLayout.LayoutParams stepperParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stepperParams.gravity = Gravity.CENTER_HORIZONTAL;
        stepperParams.topMargin = dp(14);
        page.addView(stepper, stepperParams);

        LinearLayout stage = new LinearLayout(requireContext());
        stage.setOrientation(LinearLayout.VERTICAL);
        stage.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams stageParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        stageParams.topMargin = dp(12);
        page.addView(stage, stageParams);

        TextView heading = text(isFrontCamera() ? "Xác thực khuôn mặt" : "Chụp ảnh Căn cước công dân",
                21, color(R.color.ink), true);
        heading.setGravity(Gravity.CENTER);
        stage.addView(heading, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView subtitle = text(isFrontCamera()
                ? "Giữ điện thoại ngang tầm mắt và nhìn thẳng vào camera"
                : "Đặt thẻ CCCD ngay ngắn trong khung hình", 13, color(R.color.ink_muted), false);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = dp(4);
        stage.addView(subtitle, subtitleParams);

        FrameLayout viewport = new FrameLayout(requireContext());
        viewport.setBackground(rounded(0x44FFFFFF, isFrontCamera() ? dp(120) : dp(24),
                0x99FFFFFF, dp(1)));
        viewport.setClipToOutline(true);
        viewport.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline outline) {
                if (isFrontCamera()) outline.setOval(0, 0, view.getWidth(), view.getHeight());
                else outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(24));
            }
        });
        preview = new PreviewView(requireContext());
        preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        preview.setOutlineProvider(viewport.getOutlineProvider());
        preview.setClipToOutline(true);
        viewport.addView(preview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        capturedPhoto = new ImageView(requireContext());
        capturedPhoto.setScaleType(ImageView.ScaleType.CENTER_CROP);
        capturedPhoto.setVisibility(View.GONE);
        capturedPhoto.setOutlineProvider(viewport.getOutlineProvider());
        capturedPhoto.setClipToOutline(true);
        viewport.addView(capturedPhoto, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View guide = new View(requireContext());
        GradientDrawable border = new GradientDrawable();
        border.setColor(Color.TRANSPARENT);
        border.setStroke(dp(2), 0xD9FFFFFF);
        if (isFrontCamera()) border.setShape(GradientDrawable.OVAL);
        else border.setCornerRadius(dp(18));
        guide.setBackground(border);
        FrameLayout.LayoutParams guideParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        guideParams.setMargins(dp(8), dp(8), dp(8), dp(8));
        viewport.addView(guide, guideParams);
        TextView loading = text("Đang mở camera…", 13, Color.WHITE, true);
        loading.setGravity(Gravity.CENTER);
        loading.setBackground(rounded(0x66000000, dp(16), Color.TRANSPARENT, 0));
        viewport.addView(loading, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40), Gravity.CENTER));
        previewStatus = loading;
        LinearLayout.LayoutParams viewportParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, isFrontCamera() ? dp(220) : dp(202));
        viewportParams.topMargin = dp(18);
        viewportParams.bottomMargin = dp(4);
        if (isFrontCamera()) {
            viewportParams.width = dp(220);
            viewportParams.gravity = Gravity.CENTER_HORIZONTAL;
        }
        stage.addView(viewport, viewportParams);

        sideSwitcher = new LinearLayout(requireContext());
        sideSwitcher.setGravity(Gravity.CENTER);
        sideSwitcher.setPadding(dp(4), dp(4), dp(4), dp(4));
        sideSwitcher.setBackground(rounded(0x80FFFFFF, dp(24), 0x99FFFFFF, dp(1)));
        MaterialButton frontSide = switchButton("Mặt trước", getSlot() == 0);
        frontSide.setEnabled(getSlot() != 0 && allowsSlot(0));
        frontSide.setOnClickListener(v -> publishSlotSwitch(0));
        MaterialButton backSide = switchButton("Mặt sau", getSlot() == 1);
        backSide.setEnabled(getSlot() != 1 && allowsSlot(1));
        backSide.setOnClickListener(v -> publishSlotSwitch(1));
        sideSwitcher.addView(frontSide);
        sideSwitcher.addView(backSide);
        LinearLayout.LayoutParams switchParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        switchParams.topMargin = dp(14);
        stage.addView(sideSwitcher, switchParams);
        sideSwitcher.setVisibility(isFrontCamera() ? View.GONE : View.VISIBLE);

        message = text(isFrontCamera()
                ? "Chỉ một khuôn mặt trong ảnh; nhìn thẳng và giữ máy ổn định."
                : "Đảm bảo ảnh rõ nét, không bị lóa sáng và không mất góc.", 12, color(R.color.ink_muted), false);
        message.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        messageParams.topMargin = dp(12);
        stage.addView(message, messageParams);

        cameraControls = new LinearLayout(requireContext());
        ((LinearLayout) cameraControls).setGravity(Gravity.CENTER_VERTICAL);
        ((LinearLayout) cameraControls).setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams controlsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        controlsParams.topMargin = dp(14);
        page.addView(cameraControls, controlsParams);

        flashButton = new MaterialButton(requireContext());
        flash = flashButton;
        flashButton.setIcon(ContextCompat.getDrawable(requireContext(), R.drawable.ic_flash_off));
        flashButton.setIconTint(android.content.res.ColorStateList.valueOf(color(R.color.ink)));
        flashButton.setIconSize(dp(22));
        flashButton.setIconPadding(0);
        flashButton.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        flashButton.setGravity(Gravity.CENTER);
        flashButton.setPadding(0, 0, 0, 0);
        flashButton.setMinWidth(0);
        flashButton.setInsetTop(0);
        flashButton.setInsetBottom(0);
        flashButton.setBackground(rounded(0x88FFFFFF, dp(24), 0x99FFFFFF, dp(1)));
        flashButton.setBackgroundTintList(null);
        flashButton.setEnabled(false);
        flashButton.setContentDescription("Bật hoặc tắt đèn flash");
        flashButton.setOnClickListener(v -> toggleFlash());
        LinearLayout flashColumn = controlColumn(flashButton);
        ((LinearLayout) cameraControls).addView(flashColumn,
                new LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT));

        MaterialButton capture = new MaterialButton(requireContext());
        capture.setBackground(rounded(color(R.color.navy), dp(40), Color.WHITE, dp(4)));
        capture.setBackgroundTintList(null);
        capture.setIcon(ContextCompat.getDrawable(requireContext(), R.drawable.ic_photo_camera));
        capture.setIconTint(android.content.res.ColorStateList.valueOf(Color.WHITE));
        capture.setIconSize(dp(28));
        capture.setIconPadding(0);
        capture.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        capture.setGravity(Gravity.CENTER);
        capture.setPadding(0, 0, 0, 0);
        capture.setMinWidth(0);
        capture.setInsetTop(0);
        capture.setInsetBottom(0);
        capture.setContentDescription("Chụp ảnh bằng camera trong ứng dụng");
        capture.setOnClickListener(v -> capturePhoto());
        captureButton = capture;
        this.capture = capture;
        LinearLayout captureColumn = controlColumn(capture);
        LinearLayout.LayoutParams captureColumnParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ((LinearLayout) cameraControls).addView(captureColumn, captureColumnParams);

        MaterialButton library = new MaterialButton(requireContext());
        library.setIcon(ContextCompat.getDrawable(requireContext(), R.drawable.ic_photo_library));
        library.setIconTint(android.content.res.ColorStateList.valueOf(color(R.color.ink)));
        library.setIconSize(dp(22));
        library.setIconPadding(0);
        library.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        library.setGravity(Gravity.CENTER);
        library.setPadding(0, 0, 0, 0);
        library.setMinWidth(0);
        library.setInsetTop(0);
        library.setInsetBottom(0);
        library.setBackground(rounded(0x88FFFFFF, dp(24), 0x99FFFFFF, dp(1)));
        library.setBackgroundTintList(null);
        library.setContentDescription("Tải ảnh CCCD từ thư viện");
        library.setOnClickListener(v -> gallery.launch("image/*"));
        LinearLayout libraryColumn = controlColumn(library);
        ((LinearLayout) cameraControls).addView(libraryColumn,
                new LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT));
        libraryColumn.setVisibility(isFrontCamera() ? View.GONE : View.VISIBLE);
        flashColumn.setVisibility(isFrontCamera() ? View.GONE : View.VISIBLE);

        reviewControls = new LinearLayout(requireContext());
        ((LinearLayout) reviewControls).setOrientation(LinearLayout.HORIZONTAL);
        ((LinearLayout) reviewControls).setGravity(Gravity.CENTER);
        MaterialButton retake = switchButton("Chụp lại", false);
        retake.setOnClickListener(v -> retakePhoto());
        MaterialButton usePhoto = switchButton("Dùng ảnh", true);
        usePhoto.setContentDescription("Xác nhận ảnh và tiếp tục");
        usePhoto.setOnClickListener(v -> acceptPhoto());
        ((LinearLayout) reviewControls).addView(retake, new LinearLayout.LayoutParams(0, dp(48), 1f));
        LinearLayout.LayoutParams useParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        useParams.leftMargin = dp(10);
        ((LinearLayout) reviewControls).addView(usePhoto, useParams);
        reviewControls.setVisibility(View.GONE);
        LinearLayout.LayoutParams reviewParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        reviewParams.topMargin = dp(14);
        page.addView(reviewControls, reviewParams);

        String restoredPath = savedInstanceState == null ? null : savedInstanceState.getString("pending_photo");
        if (restoredPath != null) showPhoto(new File(restoredPath));

        return root;
    }

    @Override public void onStart() { super.onStart(); if (getDialog() != null && getDialog().getWindow() != null) getDialog().getWindow().setLayout(-1, -1); }
    @Override public void onResume() { super.onResume(); if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera(); else permission.launch(Manifest.permission.CAMERA); }
    @Override public void onPause() { if (provider != null) provider.unbindAll(); imageCapture = null; camera = null; super.onPause(); }
    @Override public void onSaveInstanceState(@NonNull Bundle out) { super.onSaveInstanceState(out); if (pendingPhoto != null) out.putString("pending_photo", pendingPhoto.getAbsolutePath()); }
    @Override public void onDestroyView() { if (provider != null) provider.unbindAll(); io.shutdownNow(); preview = null; super.onDestroyView(); }
    @Override public void onDismiss(@NonNull android.content.DialogInterface d) { if (!resultSent) { if (pendingPhoto != null) pendingPhoto.delete(); publishResult(null, "Đã hủy chụp ảnh."); } super.onDismiss(d); }
    private void startCamera() { if (!isAdded() || preview == null || imageCapture != null) return; ListenableFuture<ProcessCameraProvider> f = ProcessCameraProvider.getInstance(requireContext()); f.addListener(() -> { try { provider = f.get(); bindCamera(); } catch (Exception e) { finishWithError("Không thể khởi động camera. Vui lòng thử lại."); } }, ContextCompat.getMainExecutor(requireContext())); }
    private void bindCamera() { if (previewStatus != null) previewStatus.setVisibility(View.GONE); if (provider == null || preview == null) return; provider.unbindAll(); CameraSelector selector = new CameraSelector.Builder().requireLensFacing(isFrontCamera() ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK).build(); Preview p = new Preview.Builder().build(); p.setSurfaceProvider(preview.getSurfaceProvider()); imageCapture = new ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).setJpegQuality(90).build(); camera = provider.bindToLifecycle(this, selector, p, imageCapture); flash.setEnabled(camera.getCameraInfo().hasFlashUnit() && !isFrontCamera()); }
    private void capturePhoto() { if (imageCapture == null || pendingPhoto != null) return; capture.setEnabled(false); File file = new File(requireContext().getCacheDir(), "ekyc-capture-" + UUID.randomUUID() + ".jpg"); ImageCapture.OutputFileOptions o = new ImageCapture.OutputFileOptions.Builder(file).build(); imageCapture.takePicture(o, ContextCompat.getMainExecutor(requireContext()), new ImageCapture.OnImageSavedCallback() { @Override public void onImageSaved(@NonNull ImageCapture.OutputFileResults r) { showPhoto(file); } @Override public void onError(@NonNull ImageCaptureException e) { capture.setEnabled(true); message.setText("Chụp chưa thành công. Hãy thử lại."); } }); }
    private void toggleFlash() {
        flashEnabled = !flashEnabled;
        if (imageCapture != null) imageCapture.setFlashMode(flashEnabled ? ImageCapture.FLASH_MODE_ON : ImageCapture.FLASH_MODE_OFF);
        flash.setIcon(ContextCompat.getDrawable(requireContext(), flashEnabled ? R.drawable.ic_flash_on : R.drawable.ic_flash_off));
        flash.setContentDescription(flashEnabled ? "Tắt đèn flash" : "Bật đèn flash");
    }
    private void showPhoto(File f) {
        if (!f.isFile()) return;
        Bitmap bitmap = decodeReview(f);
        if (bitmap == null) { message.setText("Không đọc được ảnh. Hãy chụp hoặc chọn ảnh khác."); f.delete(); capture.setEnabled(true); return; }
        pendingPhoto = f;
        capturedPhoto.setImageBitmap(bitmap);
        capturedPhoto.setVisibility(View.VISIBLE); preview.setVisibility(View.GONE); cameraControls.setVisibility(View.GONE); reviewControls.setVisibility(View.VISIBLE); message.setText("Kiểm tra ảnh trước khi tiếp tục.");
    }
    @Nullable private Bitmap decodeReview(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 1280) options.inSampleSize *= 2;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (bitmap == null) return null;
        int orientation;
        try { orientation = new ExifInterface(file.getAbsolutePath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL); }
        catch (IOException e) { return bitmap; }
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90: matrix.setRotate(90); break;
            case ExifInterface.ORIENTATION_ROTATE_180: matrix.setRotate(180); break;
            case ExifInterface.ORIENTATION_ROTATE_270: matrix.setRotate(270); break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.setScale(-1, 1); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: matrix.setScale(1, -1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE: matrix.setScale(-1, 1); matrix.postRotate(270); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: matrix.setScale(-1, 1); matrix.postRotate(90); break;
            default: return bitmap;
        }
        Bitmap oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (oriented != bitmap) bitmap.recycle();
        return oriented;
    }
    private void retakePhoto() { if (pendingPhoto != null) pendingPhoto.delete(); pendingPhoto = null; capturedPhoto.setImageDrawable(null); capturedPhoto.setVisibility(View.GONE); preview.setVisibility(View.VISIBLE); cameraControls.setVisibility(View.VISIBLE); reviewControls.setVisibility(View.GONE); capture.setEnabled(true); message.setText("Đảm bảo ảnh rõ nét, không bị lóa sáng và không mất góc."); }
    private void acceptPhoto() { if (pendingPhoto != null && pendingPhoto.isFile()) { String p = pendingPhoto.getAbsolutePath(); pendingPhoto = null; publishResult(p, null); } }
    private void gallerySelected(@Nullable Uri uri) { if (uri == null || isFrontCamera()) return; File f = new File(requireContext().getCacheDir(), "ekyc-upload-" + UUID.randomUUID() + ".jpg"); io.execute(() -> { try (InputStream in = requireContext().getContentResolver().openInputStream(uri); FileOutputStream out = new FileOutputStream(f)) { if (in == null) throw new IOException(); byte[] b = new byte[8192]; int n; long total = 0; while ((n = in.read(b)) != -1) { total += n; if (total > 30_000_000) throw new IOException(); out.write(b, 0, n); } requireActivity().runOnUiThread(() -> showPhoto(f)); } catch (Exception e) { f.delete(); requireActivity().runOnUiThread(() -> message.setText("Không đọc được ảnh. Hãy chọn ảnh khác.")); } }); }
    private void publishSlotSwitch(int slot) { if (!allowsSlot(slot)) return; Bundle b = new Bundle(); b.putInt(RESULT_SWITCH_SLOT, slot); getParentFragmentManager().setFragmentResult(RESULT_KEY, b); resultSent = true; dismiss(); }
    private void publishResult(@Nullable String path, @Nullable String error) { if (resultSent) return; resultSent = true; Bundle b = new Bundle(); b.putInt(ARG_SLOT, getSlot()); if (path != null) b.putString(RESULT_PATH, path); if (error != null) b.putString(RESULT_ERROR, error); getParentFragmentManager().setFragmentResult(RESULT_KEY, b); if (isAdded()) dismiss(); }
    private void finishWithError(String e) { if (isAdded()) publishResult(null, e); }
    private boolean isFrontCamera() { return getArguments() != null && getArguments().getBoolean(ARG_FRONT_CAMERA); }
    private int getSlot() { return getArguments() == null ? -1 : getArguments().getInt(ARG_SLOT, -1); }
    private boolean allowsSlot(int s) { return getArguments() != null && (s == 0 ? getArguments().getBoolean(ARG_ALLOW_FRONT) : s == 1 && getArguments().getBoolean(ARG_ALLOW_BACK)); }
    private MaterialButton iconButton(int icon, String description) {
        MaterialButton button = new MaterialButton(requireContext());
        button.setBackground(rounded(0x88FFFFFF, dp(24), 0x99FFFFFF, dp(1)));
        button.setBackgroundTintList(null);
        button.setIcon(ContextCompat.getDrawable(requireContext(), icon));
        button.setIconTint(android.content.res.ColorStateList.valueOf(color(R.color.ink)));
        button.setIconSize(dp(20));
        button.setIconPadding(0);
        button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        button.setGravity(Gravity.CENTER);
        button.setPadding(0, 0, 0, 0);
        button.setMinWidth(0);
        button.setContentDescription(description);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        return button;
    }

    private MaterialButton switchButton(String label, boolean selected) {
        MaterialButton button = new MaterialButton(requireContext());
        button.setText(label);
        button.setTextSize(13);
        button.setTextColor(selected ? Color.WHITE : color(R.color.ink));
        button.setBackground(rounded(selected ? color(R.color.navy) : 0x00FFFFFF,
                dp(22), selected ? color(R.color.navy) : Color.TRANSPARENT, dp(1)));
        button.setBackgroundTintList(null);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setMinWidth(dp(112));
        button.setContentDescription(label);
        return button;
    }

    private LinearLayout controlColumn(View control) {
        LinearLayout column = new LinearLayout(requireContext());
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        int controlSize = control == captureButton ? dp(72) : dp(52);
        column.addView(control, new LinearLayout.LayoutParams(controlSize, controlSize));
        return column;
    }

    private GradientDrawable rounded(int fill, int radius, int strokeColor, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radius);
        if (strokeWidth > 0) drawable.setStroke(strokeWidth, strokeColor);
        return drawable;
    }

    private MaterialButton button(String s) { MaterialButton b = new MaterialButton(requireContext()); b.setText(s); b.setTextSize(12); b.setInsetTop(0); b.setInsetBottom(0); return b; }
    private TextView text(String s, int size, int c, boolean bold) { TextView t = new TextView(requireContext()); t.setText(s); t.setTextSize(size); t.setTextColor(c); if (bold) t.setTypeface(t.getTypeface(), 1); return t; }
    private int color(int id) { return ContextCompat.getColor(requireContext(), id); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}

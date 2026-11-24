package com.example.final_term.ui.ekyc;

import android.Manifest;
import android.content.Context;
import android.content.ContentResolver;
import android.content.pm.PackageManager;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.media.ExifInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.net.Uri;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;

import com.example.final_term.R;
import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Full-screen Camera2 preview used for capturing eKYC evidence inside the app. */
public final class InAppCameraDialogFragment extends DialogFragment {
    public static final String RESULT_KEY = "ekyc_in_app_camera_result";
    private static final String ARG_SLOT = "slot";
    private static final String ARG_FRONT_CAMERA = "front_camera";
    private static final String ARG_ALLOW_FRONT = "allow_front";
    private static final String ARG_ALLOW_BACK = "allow_back";
    private static final String RESULT_SWITCH_SLOT = "switch_slot";
    private static final String RESULT_PATH = "path";
    private static final String RESULT_ERROR = "error";
    private static final ExecutorService PHOTO_IO = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<String> permission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                permissionRequestPending = false;
                if (granted) {
                    startCameraThread();
                    openCamera();
                } else finishWithError("Cần quyền camera để chụp ảnh định danh.");
            });

    private TextureView preview;
    private TextView message;
    private View captureButton;
    private View cameraControls;
    private View reviewControls;
    private LinearLayout sideSwitcher;
    private ImageView capturedPhoto;
    private TextView previewStatus;
    private MaterialButton flashButton;
    private HandlerThread cameraThread;
    private volatile Handler cameraHandler;
    private volatile CameraDevice camera;
    private volatile CameraCaptureSession session;
    private volatile ImageReader imageReader;
    private CaptureRequest.Builder previewRequest;
    private Size previewSize;
    private File cacheDirectory;
    private File pendingPhoto;
    private int sensorOrientation;
    private String cameraId;
    private volatile boolean capturing;
    private volatile boolean openingCamera;
    private volatile boolean flashAvailable;
    private boolean flashEnabled;
    private boolean permissionRequestPending;
    private boolean resultSent;
    private final Semaphore cameraLock = new Semaphore(1);

    private final ActivityResultLauncher<String> gallery = registerForActivityResult(
            new ActivityResultContracts.GetContent(), this::gallerySelected);

    public static InAppCameraDialogFragment newInstance(int slot, boolean allowFront, boolean allowBack) {
        InAppCameraDialogFragment fragment = new InAppCameraDialogFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_SLOT, slot);
        args.putBoolean(ARG_FRONT_CAMERA, slot == 2);
        args.putBoolean(ARG_ALLOW_FRONT, allowFront);
        args.putBoolean(ARG_ALLOW_BACK, allowBack);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NO_TITLE, R.style.Theme_Final_term);
        setCancelable(true);
    }

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
        cacheDirectory = requireContext().getCacheDir();

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
        preview = new TextureView(requireContext());
        preview.setSurfaceTextureListener(surfaceListener);
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
        flashButton.setText("Flash");
        flashButton.setTextSize(11);
        flashButton.setTextColor(color(R.color.ink));
        flashButton.setBackground(rounded(0x88FFFFFF, dp(24), 0x99FFFFFF, dp(1)));
        flashButton.setBackgroundTintList(null);
        flashButton.setEnabled(false);
        flashButton.setContentDescription("Bật hoặc tắt đèn flash");
        flashButton.setOnClickListener(v -> toggleFlash());
        LinearLayout flashColumn = controlColumn(flashButton, "Flash", dp(72));
        ((LinearLayout) cameraControls).addView(flashColumn,
                new LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT));

        MaterialButton capture = new MaterialButton(requireContext());
        capture.setBackground(rounded(color(R.color.navy), dp(40), Color.WHITE, dp(4)));
        capture.setBackgroundTintList(null);
        capture.setIcon(ContextCompat.getDrawable(requireContext(), R.drawable.ic_photo_camera));
        capture.setIconTint(android.content.res.ColorStateList.valueOf(Color.WHITE));
        capture.setIconSize(dp(28));
        capture.setContentDescription("Chụp ảnh bằng camera trong ứng dụng");
        capture.setOnClickListener(v -> capturePhoto());
        captureButton = capture;
        LinearLayout captureColumn = controlColumn(capture, "Chụp ảnh", dp(88));
        LinearLayout.LayoutParams captureColumnParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ((LinearLayout) cameraControls).addView(captureColumn, captureColumnParams);

        MaterialButton library = new MaterialButton(requireContext());
        library.setText("Mở thư viện");
        library.setTextSize(11);
        library.setTextColor(color(R.color.ink));
        library.setBackground(rounded(0x88FFFFFF, dp(24), 0x99FFFFFF, dp(1)));
        library.setBackgroundTintList(null);
        library.setContentDescription("Tải ảnh CCCD từ thư viện");
        library.setOnClickListener(v -> gallery.launch("image/*"));
        LinearLayout libraryColumn = controlColumn(library, "Thư viện", dp(82));
        ((LinearLayout) cameraControls).addView(libraryColumn,
                new LinearLayout.LayoutParams(dp(82), ViewGroup.LayoutParams.WRAP_CONTENT));
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
        if (restoredPath != null) showPhotoForReview(new File(restoredPath));

        return root;
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (pendingPhoto != null && pendingPhoto.isFile()) outState.putString("pending_photo", pendingPhoto.getAbsolutePath());
    }

    @Override
    public void onStart() {
        super.onStart();
        Window window = requireDialog().getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(color(R.color.bg_mist));
            window.setNavigationBarColor(color(R.color.bg_slate));
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        startCameraThread();
        if (preview != null && preview.isAvailable()) openCamera();
    }

    @Override
    public void onPause() {
        closeCamera();
        stopCameraThread();
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        preview = null;
        message = null;
        captureButton = null;
        cameraControls = null;
        reviewControls = null;
        sideSwitcher = null;
        capturedPhoto = null;
        previewStatus = null;
        super.onDestroyView();
    }

    @Override
    public void onDismiss(@NonNull android.content.DialogInterface dialog) {
        super.onDismiss(dialog);
        if (!resultSent) {
            if (pendingPhoto != null) pendingPhoto.delete();
            publishResult(null, "Đã hủy chụp ảnh.");
        }
    }

    private final TextureView.SurfaceTextureListener surfaceListener = new TextureView.SurfaceTextureListener() {
        @Override public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
            openCamera();
        }
        @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
            configureTransform(width, height);
        }
        @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) { return true; }
        @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
            if (previewStatus != null) previewStatus.setVisibility(View.GONE);
        }
    };

    private final CameraDevice.StateCallback cameraState = new CameraDevice.StateCallback() {
        @Override public void onOpened(@NonNull CameraDevice opened) {
            openingCamera = false;
            if (!isAdded()) {
                cameraLock.release();
                opened.close();
                return;
            }
            camera = opened;
            cameraLock.release();
            createPreviewSession();
        }
        @Override public void onDisconnected(@NonNull CameraDevice disconnected) {
            openingCamera = false;
            cameraLock.release();
            disconnected.close();
            if (camera == disconnected) camera = null;
            finishWithError("Camera đã ngắt kết nối. Vui lòng thử lại.");
        }
        @Override public void onError(@NonNull CameraDevice failed, int error) {
            openingCamera = false;
            cameraLock.release();
            failed.close();
            if (camera == failed) camera = null;
            finishWithError("Không thể mở camera. Vui lòng thử lại.");
        }
    };

    private void startCameraThread() {
        if (cameraThread != null) return;
        cameraThread = new HandlerThread("ekyc-camera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void stopCameraThread() {
        if (cameraThread == null) return;
        cameraThread.quitSafely();
        try {
            cameraThread.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        cameraThread = null;
        cameraHandler = null;
    }

    private void openCamera() {
        if (!isAdded() || preview == null || !preview.isAvailable()) return;
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            if (!permissionRequestPending) {
                permissionRequestPending = true;
                permission.launch(Manifest.permission.CAMERA);
            }
            return;
        }
        if (camera != null || openingCamera || cameraHandler == null) return;
        boolean lockAcquired = false;
        try {
            CameraManager manager = (CameraManager) requireContext().getSystemService(Context.CAMERA_SERVICE);
            cameraId = findCamera(manager);
            if (cameraId == null) {
                finishWithError("Thiết bị không có camera phù hợp.");
                return;
            }
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            sensorOrientation = valueOrZero(characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION));
            flashAvailable = Boolean.TRUE.equals(characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE));
            Activity activity = getActivity();
            if (activity != null) activity.runOnUiThread(() -> {
                if (flashButton != null) flashButton.setEnabled(flashAvailable && !isFrontCamera());
            });
            android.hardware.camera2.params.StreamConfigurationMap map =
                    characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) throw new IOException("Camera output is unavailable");
            previewSize = choosePreviewSize(map.getOutputSizes(SurfaceTexture.class),
                    preview.getWidth(), preview.getHeight(), isPreviewRotationRequired());
            Size jpegSize = chooseSize(map.getOutputSizes(android.graphics.ImageFormat.JPEG), 2560);
            if (previewSize == null || jpegSize == null) throw new IOException("Camera size is unavailable");
            imageReader = ImageReader.newInstance(jpegSize.getWidth(), jpegSize.getHeight(),
                    android.graphics.ImageFormat.JPEG, 2);
            imageReader.setOnImageAvailableListener(this::saveCapturedImage, cameraHandler);
            if (!cameraLock.tryAcquire(1500, TimeUnit.MILLISECONDS)) {
                throw new IOException("Camera is busy");
            }
            lockAcquired = true;
            openingCamera = true;
            manager.openCamera(cameraId, cameraState, cameraHandler);
            lockAcquired = false;
        } catch (SecurityException e) {
            if (lockAcquired) cameraLock.release();
            openingCamera = false;
            finishWithError("Cần quyền camera để chụp ảnh định danh.");
        } catch (CameraAccessException | IOException e) {
            if (lockAcquired) cameraLock.release();
            openingCamera = false;
            finishWithError("Không thể khởi động camera. Vui lòng thử lại.");
        } catch (InterruptedException e) {
            if (lockAcquired) cameraLock.release();
            openingCamera = false;
            Thread.currentThread().interrupt();
            finishWithError("Không thể khởi động camera. Vui lòng thử lại.");
        }
    }

    private String findCamera(CameraManager manager) throws CameraAccessException {
        int wanted = isFrontCamera() ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
        String fallback = null;
        for (String id : manager.getCameraIdList()) {
            Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == wanted) return id;
            if (fallback == null) fallback = id;
        }
        return fallback;
    }

    private void createPreviewSession() {
        try {
            if (camera == null || preview == null || preview.getSurfaceTexture() == null || imageReader == null) return;
            SurfaceTexture texture = preview.getSurfaceTexture();
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            configureTransform(preview.getWidth(), preview.getHeight());
            Surface surface = new Surface(texture);
            previewRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequest.addTarget(surface);
            camera.createCaptureSession(Arrays.asList(surface, imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(@NonNull CameraCaptureSession configured) {
                            if (camera == null) return;
                            session = configured;
                            try {
                                previewRequest.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                                previewRequest.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                session.setRepeatingRequest(previewRequest.build(), null, cameraHandler);
                            } catch (CameraAccessException e) {
                                finishWithError("Không thể hiển thị camera. Vui lòng thử lại.");
                            }
                        }
                        @Override public void onConfigureFailed(@NonNull CameraCaptureSession failed) {
                            finishWithError("Không thể cấu hình camera. Vui lòng thử lại.");
                        }
                    }, cameraHandler);
        } catch (CameraAccessException | RuntimeException e) {
            finishWithError("Không thể hiển thị camera. Vui lòng thử lại.");
        }
    }

    private void capturePhoto() {
        if (camera == null || session == null || imageReader == null || capturing) return;
        capturing = true;
        if (captureButton != null) captureButton.setEnabled(false);
        if (message != null) message.setText("Đang chụp ảnh…");
        try {
            CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            request.addTarget(imageReader.getSurface());
            request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            request.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation());
            session.stopRepeating();
            session.capture(request.build(), new CameraCaptureSession.CaptureCallback() {
                @Override public void onCaptureFailed(@NonNull CameraCaptureSession failedSession,
                        @NonNull CaptureRequest failedRequest,
                        @NonNull CaptureFailure failure) {
                    capturing = false;
                    Activity activity = getActivity();
                    if (activity == null) return;
                    activity.runOnUiThread(() -> {
                        if (captureButton != null) captureButton.setEnabled(true);
                        if (message != null) message.setText("Chụp chưa thành công. Hãy thử lại.");
                        resumePreview();
                    });
                }
            }, cameraHandler);
        } catch (CameraAccessException | IllegalStateException e) {
            capturing = false;
            if (captureButton != null) captureButton.setEnabled(true);
            if (message != null) message.setText("Chụp chưa thành công. Hãy thử lại.");
            resumePreview();
        }
    }

    private void saveCapturedImage(ImageReader reader) {
        Image image = reader.acquireLatestImage();
        if (image == null) return;
        try (Image captured = image) {
            if (!capturing) return;
            ByteBuffer buffer = captured.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            File destination = new File(cacheDirectory, "ekyc-capture-" + UUID.randomUUID() + ".jpg");
            Files.write(destination.toPath(), bytes);
            Activity activity = getActivity();
            if (activity == null) destination.delete();
            else activity.runOnUiThread(() -> showPhotoForReview(destination));
        } catch (IOException | IllegalStateException e) {
            capturing = false;
            Activity activity = getActivity();
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                if (captureButton != null) captureButton.setEnabled(true);
                if (message != null) message.setText("Không lưu được ảnh. Hãy thử chụp lại.");
                resumePreview();
            });
        }
    }

    private void gallerySelected(@Nullable Uri uri) {
        if (uri == null || isFrontCamera()) return;
        if (message != null) message.setText("Đang đọc ảnh đã chọn…");
        ContentResolver resolver = requireContext().getContentResolver();
        PHOTO_IO.execute(() -> {
            File selected = new File(cacheDirectory, "ekyc-upload-" + UUID.randomUUID() + ".jpg");
            try (InputStream input = resolver.openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(selected)) {
                if (input == null) throw new IOException("Image unavailable");
                byte[] chunk = new byte[8192];
                long total = 0;
                int count;
                while ((count = input.read(chunk)) != -1) {
                    total += count;
                    if (total > 30_000_000L) throw new IOException("Image is too large");
                    output.write(chunk, 0, count);
                }
                if (total == 0) throw new IOException("Image is empty");
                new Handler(Looper.getMainLooper()).post(() -> showPhotoForReview(selected));
            } catch (IOException | SecurityException e) {
                selected.delete();
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (message != null) message.setText("Không đọc được ảnh. Hãy chọn ảnh khác.");
                });
            }
        });
    }

    private void showPhotoForReview(File image) {
        pendingPhoto = image;
        capturing = false;
        if (cameraControls != null) cameraControls.setVisibility(View.GONE);
        if (reviewControls != null) reviewControls.setVisibility(View.VISIBLE);
        if (message != null) message.setText("Kiểm tra ảnh trước khi tiếp tục.");
        if (previewStatus != null) previewStatus.setVisibility(View.GONE);
        PHOTO_IO.execute(() -> {
            Bitmap bitmap = decodePreview(image);
            new Handler(Looper.getMainLooper()).post(() -> {
                if (capturedPhoto == null || !isAdded()) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                if (bitmap == null) {
                    if (message != null) message.setText("Không xem được ảnh. Hãy chụp hoặc chọn ảnh khác.");
                    return;
                }
                Bitmap previous = capturedPhoto.getDrawable() instanceof android.graphics.drawable.BitmapDrawable
                        ? ((android.graphics.drawable.BitmapDrawable) capturedPhoto.getDrawable()).getBitmap() : null;
                capturedPhoto.setImageBitmap(bitmap);
                capturedPhoto.setVisibility(View.VISIBLE);
                if (previous != null && previous != bitmap) previous.recycle();
            });
        });
    }

    @Nullable
    private static Bitmap decodePreview(File image) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(image.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 1280) {
            options.inSampleSize *= 2;
        }
        Bitmap decoded = BitmapFactory.decodeFile(image.getAbsolutePath(), options);
        if (decoded == null) return null;

        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try {
            orientation = new ExifInterface(image.getAbsolutePath()).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException ignored) {
            // Images without readable EXIF are displayed in their encoded orientation.
        }
        Matrix orientationMatrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                orientationMatrix.setScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                orientationMatrix.setRotate(180f);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                orientationMatrix.setScale(1f, -1f);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                orientationMatrix.setScale(-1f, 1f);
                orientationMatrix.postRotate(270f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                orientationMatrix.setRotate(90f);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                orientationMatrix.setScale(-1f, 1f);
                orientationMatrix.postRotate(90f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                orientationMatrix.setRotate(270f);
                break;
            default:
                break;
        }
        Bitmap oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(),
                orientationMatrix, true);
        if (oriented != decoded) decoded.recycle();
        return oriented;
    }

    private void acceptPhoto() {
        if (pendingPhoto == null || !pendingPhoto.isFile()) return;
        String path = pendingPhoto.getAbsolutePath();
        pendingPhoto = null;
        publishResult(path, null);
    }

    private void retakePhoto() {
        if (pendingPhoto != null) pendingPhoto.delete();
        pendingPhoto = null;
        if (capturedPhoto != null) {
            if (capturedPhoto.getDrawable() instanceof android.graphics.drawable.BitmapDrawable) {
                Bitmap bitmap = ((android.graphics.drawable.BitmapDrawable) capturedPhoto.getDrawable()).getBitmap();
                capturedPhoto.setImageDrawable(null);
                bitmap.recycle();
            }
            capturedPhoto.setVisibility(View.GONE);
        }
        if (reviewControls != null) reviewControls.setVisibility(View.GONE);
        if (cameraControls != null) cameraControls.setVisibility(View.VISIBLE);
        if (captureButton != null) captureButton.setEnabled(true);
        if (message != null) message.setText(isFrontCamera()
                ? "Chỉ một khuôn mặt trong ảnh; nhìn thẳng và giữ máy ổn định."
                : "Đảm bảo ảnh rõ nét, không bị lóa sáng và không mất góc.");
        capturing = false;
        resumePreview();
    }

    private void toggleFlash() {
        if (!flashAvailable || isFrontCamera() || previewRequest == null || session == null) return;
        flashEnabled = !flashEnabled;
        try {
            previewRequest.set(CaptureRequest.FLASH_MODE,
                    flashEnabled ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
            session.setRepeatingRequest(previewRequest.build(), null, cameraHandler);
            flashButton.setText(flashEnabled ? "Flash: bật" : "Flash");
        } catch (CameraAccessException | IllegalStateException e) {
            flashEnabled = false;
            flashButton.setText("Flash");
        }
    }

    private void publishSlotSwitch(int slot) {
        if (!allowsSlot(slot)) return;
        resultSent = true;
        Bundle result = new Bundle();
        result.putInt(RESULT_SWITCH_SLOT, slot);
        getParentFragmentManager().setFragmentResult(RESULT_KEY, result);
        dismiss();
    }

    private void resumePreview() {
        if (session == null || previewRequest == null || cameraHandler == null) return;
        try {
            session.setRepeatingRequest(previewRequest.build(), null, cameraHandler);
        } catch (CameraAccessException | IllegalStateException ignored) {
            // Keep the capture screen open so the user can retry or cancel.
        }
    }

    private int jpegOrientation() {
        int rotation = displayRotation();
        int degrees = rotation == Surface.ROTATION_90 ? 90
                : rotation == Surface.ROTATION_180 ? 180 : rotation == Surface.ROTATION_270 ? 270 : 0;
        return isFrontCamera() ? (sensorOrientation - degrees + 360) % 360
                : (sensorOrientation + degrees) % 360;
    }

    private boolean isPreviewRotationRequired() {
        int degrees = displayRotation() * 90;
        int facingSign = isFrontCamera() ? 1 : -1;
        int relativeRotation = (sensorOrientation - degrees * facingSign + 360) % 360;
        return relativeRotation % 180 != 0;
    }

    private void configureTransform(int width, int height) {
        if (preview == null || previewSize == null || width == 0 || height == 0) return;
        int rotation = displayRotation();
        int rotationDegrees = rotation * 90;
        boolean rotationRequired = isPreviewRotationRequired();
        float centerX = width / 2f;
        float centerY = height / 2f;

        // TextureView stretches its buffer to the view by default. Undo that
        // non-uniform scaling, then apply one uniform center-crop factor. This
        // accounts for both sensor orientation and display rotation.
        float scaleX;
        float scaleY;
        if (sensorOrientation % 180 == 0) {
            scaleX = width / (float) (rotationRequired ? previewSize.getWidth() : previewSize.getHeight());
            scaleY = height / (float) (rotationRequired ? previewSize.getHeight() : previewSize.getWidth());
        } else {
            scaleX = width / (float) (rotationRequired ? previewSize.getHeight() : previewSize.getWidth());
            scaleY = height / (float) (rotationRequired ? previewSize.getWidth() : previewSize.getHeight());
        }
        float finalScale = Math.max(scaleX, scaleY);
        android.graphics.Matrix transform = new android.graphics.Matrix();
        if (rotationRequired) {
            transform.setScale(1f / scaleX * finalScale, 1f / scaleY * finalScale, centerX, centerY);
        } else {
            transform.setScale(height / (float) width / scaleY * finalScale,
                    width / (float) height / scaleX * finalScale, centerX, centerY);
        }
        transform.postRotate(-rotationDegrees, centerX, centerY);
        preview.setTransform(transform);
    }

    private void closeCamera() {
        try {
            cameraLock.acquire();
            if (session != null) {
                session.close();
                session = null;
            }
            if (camera != null) {
                camera.close();
                camera = null;
            }
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
            openingCamera = false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (cameraLock.availablePermits() == 0) cameraLock.release();
        }
    }

    @SuppressWarnings("deprecation")
    private int displayRotation() {
        WindowManager manager = (WindowManager) requireContext().getSystemService(Context.WINDOW_SERVICE);
        return manager.getDefaultDisplay().getRotation();
    }

    private void publishResult(@Nullable String path, @Nullable String error) {
        if (resultSent) return;
        resultSent = true;
        Bundle result = new Bundle();
        result.putInt(ARG_SLOT, getSlot());
        if (path != null) result.putString(RESULT_PATH, path);
        if (error != null) result.putString(RESULT_ERROR, error);
        getParentFragmentManager().setFragmentResult(RESULT_KEY, result);
        if (isAdded()) dismiss();
    }

    private void finishWithError(String error) {
        Activity activity = getActivity();
        if (activity != null && android.os.Looper.myLooper() != activity.getMainLooper()) {
            activity.runOnUiThread(() -> finishWithError(error));
            return;
        }
        if (message != null) message.setText(error);
        if (isAdded()) publishResult(null, error);
    }

    private boolean isFrontCamera() {
        return getArguments() != null && getArguments().getBoolean(ARG_FRONT_CAMERA, false);
    }

    private int getSlot() {
        return getArguments() == null ? -1 : getArguments().getInt(ARG_SLOT, -1);
    }

    private boolean allowsSlot(int slot) {
        if (getArguments() == null) return false;
        return slot == 0 ? getArguments().getBoolean(ARG_ALLOW_FRONT, false)
                : slot == 1 && getArguments().getBoolean(ARG_ALLOW_BACK, false);
    }

    private MaterialButton iconButton(int icon, String description) {
        MaterialButton button = new MaterialButton(requireContext());
        button.setBackground(rounded(0x88FFFFFF, dp(24), 0x99FFFFFF, dp(1)));
        button.setBackgroundTintList(null);
        button.setIcon(ContextCompat.getDrawable(requireContext(), icon));
        button.setIconTint(android.content.res.ColorStateList.valueOf(color(R.color.ink)));
        button.setIconSize(dp(20));
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

    private LinearLayout controlColumn(View control, String label, int width) {
        LinearLayout column = new LinearLayout(requireContext());
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        int controlSize = control == captureButton ? dp(72) : dp(52);
        int controlWidth = control == captureButton ? dp(72) : width;
        column.addView(control, new LinearLayout.LayoutParams(controlWidth, controlSize));
        TextView caption = text(label, 11, color(R.color.ink), false);
        caption.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams captionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(20));
        captionParams.topMargin = dp(3);
        column.addView(caption, captionParams);
        return column;
    }

    private GradientDrawable rounded(int fill, int radius, int strokeColor, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radius);
        if (strokeWidth > 0) drawable.setStroke(strokeWidth, strokeColor);
        return drawable;
    }

    private int color(int resource) {
        return ContextCompat.getColor(requireContext(), resource);
    }

    private TextView text(String value, int sizeSp, int color, boolean bold) {
        TextView view = new TextView(requireContext());
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int valueOrZero(@Nullable Integer value) {
        return value == null ? 0 : value;
    }

    @Nullable
    private static Size choosePreviewSize(@Nullable Size[] sizes, int viewWidth, int viewHeight,
                                          boolean rotationRequired) {
        if (sizes == null || sizes.length == 0) return null;
        if (viewWidth <= 0 || viewHeight <= 0) return chooseSize(sizes, 1920);
        float targetRatio = rotationRequired ? (float) viewHeight / viewWidth : (float) viewWidth / viewHeight;
        Size best = null;
        double bestRatioError = Double.MAX_VALUE;
        long bestArea = -1;
        for (Size candidate : sizes) {
            int width = candidate.getWidth();
            int height = candidate.getHeight();
            if (Math.max(width, height) > 1920) continue;
            double ratioError = Math.abs((double) width / height - targetRatio);
            long area = (long) width * height;
            if (ratioError < bestRatioError || (Math.abs(ratioError - bestRatioError) < 0.0001 && area > bestArea)) {
                best = candidate;
                bestRatioError = ratioError;
                bestArea = area;
            }
        }
        return best != null ? best : chooseSize(sizes, 1920);
    }

    @Nullable
    private static Size chooseSize(@Nullable Size[] sizes, int maxEdge) {
        if (sizes == null || sizes.length == 0) return null;
        return Arrays.stream(sizes)
                .filter(size -> Math.max(size.getWidth(), size.getHeight()) <= maxEdge)
                .max(Comparator.comparingLong(size -> (long) size.getWidth() * size.getHeight()))
                .orElseGet(() -> Arrays.stream(sizes)
                        .min(Comparator.comparingLong(size -> (long) size.getWidth() * size.getHeight()))
                        .orElse(null));
    }
}

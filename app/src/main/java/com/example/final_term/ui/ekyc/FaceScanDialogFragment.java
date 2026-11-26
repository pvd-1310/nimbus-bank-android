package com.example.final_term.ui.ekyc;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.media.Image;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.animation.LinearInterpolator;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;

import com.example.final_term.R;
import com.example.final_term.databinding.DialogFaceScanBinding;
import com.example.final_term.ui.common.FullscreenInsets;
import com.example.final_term.ui.common.ThemedDialogBuilder;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen auto-scanning front camera dialog styled after eKYC face capture.
 * Streams CameraX ImageAnalysis frames through ML Kit Face Detection and
 * automatically triggers ImageCapture once a single well-lit, centered face holds steady.
 */
public final class FaceScanDialogFragment extends DialogFragment {
    public static final String TAG = "face-auto-scan";
    public static final String RESULT_KEY = "face_auto_scan_result";
    public static final String RESULT_PATH = "path";
    public static final String RESULT_ERROR = "error";
    private static final String ARG_TITLE = "title";
    private static final String ARG_RESULT_KEY = "result_key";
    private static final String ARG_ALLOW_GALLERY = "allow_gallery";
    private static final String ARG_DISMISS_ERROR = "dismiss_error";
    private static final String ARG_SLOT = "slot";
    private static final String ARG_REQUIRED_STABLE_FRAMES = "required_stable_frames";

    private static final long ANALYSIS_INTERVAL_MS = 150L;
    private static final int DEFAULT_REQUIRED_STABLE_FRAMES = 3;
    private static final int EKYC_REQUIRED_STABLE_FRAMES = 9;
    private static final int MAX_DIMENSION_PX = 1080;

    private DialogFaceScanBinding binding;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private ProcessCameraProvider cameraProvider;
    private ImageCapture imageCapture;
    private ImageAnalysis imageAnalysis;
    private FaceDetector faceDetector;
    private ObjectAnimator scanLineAnimator;

    private volatile boolean capturing;
    private volatile boolean pickingGallery;
    private volatile boolean resultSent;
    private long lastAnalysisTimeMs;
    private int stableFrames;
    private int requiredStableFrames = DEFAULT_REQUIRED_STABLE_FRAMES;
    private int currentRingColor;

    private final ActivityResultLauncher<String> cameraPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            granted -> {
                if (granted) startCamera();
                else publishResult(null, "Cần cấp quyền camera để đăng nhập bằng khuôn mặt.");
            }
    );

    private final ActivityResultLauncher<String> galleryPicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            this::handleGalleryUri
    );

    public static void open(@NonNull Fragment host) {
        if (!host.isAdded()) return;
        if (host.getParentFragmentManager().findFragmentByTag(TAG) != null) return;
        new FaceScanDialogFragment().show(host.getParentFragmentManager(), TAG);
    }

    public static FaceScanDialogFragment newForEkycSelfie() {
        FaceScanDialogFragment fragment = new FaceScanDialogFragment();
        Bundle args = new Bundle();
        args.putString(ARG_TITLE, "Định danh eKYC");
        args.putString(ARG_RESULT_KEY, InAppCameraDialogFragment.RESULT_KEY);
        args.putBoolean(ARG_ALLOW_GALLERY, false);
        args.putString(ARG_DISMISS_ERROR, "Đã hủy chụp ảnh.");
        args.putInt(ARG_SLOT, 2);
        args.putInt(ARG_REQUIRED_STABLE_FRAMES, EKYC_REQUIRED_STABLE_FRAMES);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NO_TITLE, R.style.Theme_Final_term);
        setCancelable(true);
        Bundle args = getArguments();
        requiredStableFrames = args != null
                ? Math.max(1, args.getInt(ARG_REQUIRED_STABLE_FRAMES, DEFAULT_REQUIRED_STABLE_FRAMES))
                : DEFAULT_REQUIRED_STABLE_FRAMES;
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setMinFaceSize(0.22f)
                .build();
        faceDetector = FaceDetection.getClient(options);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = DialogFaceScanBinding.inflate(inflater, container, false);
        configureCircularViewport();
        Bundle args = getArguments();
        String title = args != null ? args.getString(ARG_TITLE, "Đăng nhập khuôn mặt") : "Đăng nhập khuôn mặt";
        boolean allowGallery = args == null || args.getBoolean(ARG_ALLOW_GALLERY, true);
        binding.faceScanTitle.setText(title);
        if (!allowGallery) {
            binding.btnGallery.setVisibility(View.GONE);
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) binding.btnCancel.getLayoutParams();
            lp.setMarginStart(0);
            binding.btnCancel.setLayoutParams(lp);
        }
        binding.btnBack.setOnClickListener(v -> dismiss());
        binding.btnCancel.setOnClickListener(v -> dismiss());
        binding.btnHelp.setOnClickListener(v -> new ThemedDialogBuilder(requireContext())
                .setTitle("Hướng dẫn quét khuôn mặt")
                .setMessage("Giữ điện thoại ngang tầm mắt, đưa toàn bộ khuôn mặt vào giữa vòng tròn và giữ yên khoảng 1 giây. Hệ thống sẽ tự động chụp khi ảnh rõ nét.")
                .setPositiveButton("Đã hiểu", null)
                .show());
        binding.btnGallery.setOnClickListener(v -> {
            pickingGallery = true;
            galleryPicker.launch("image/*");
        });
        return binding.getRoot();
    }

    private void configureCircularViewport() {
        ViewOutlineProvider ovalOutline = new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        };
        binding.faceViewportCircle.setOutlineProvider(ovalOutline);
        binding.faceViewportCircle.setClipToOutline(true);
        binding.facePreviewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        binding.facePreviewView.setOutlineProvider(ovalOutline);
        binding.facePreviewView.setClipToOutline(true);
        binding.faceCapturedPreview.setOutlineProvider(ovalOutline);
        binding.faceCapturedPreview.setClipToOutline(true);

        applyGuideRingColor(ContextCompat.getColor(requireContext(), R.color.glass_border), 2);
        startScanLineAnimation();
    }

    private void applyGuideRingColor(int color, int strokeDp) {
        if (binding == null) return;
        currentRingColor = color;
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(Color.TRANSPARENT);
        ring.setStroke(dp(strokeDp), color);
        binding.faceGuideRing.setBackground(ring);
    }

    private void startScanLineAnimation() {
        if (binding == null) return;
        float travel = dp(176);
        scanLineAnimator = ObjectAnimator.ofFloat(binding.faceScanLine, "translationY", 0f, travel);
        scanLineAnimator.setDuration(1400L);
        scanLineAnimator.setRepeatMode(ValueAnimator.REVERSE);
        scanLineAnimator.setRepeatCount(ValueAnimator.INFINITE);
        scanLineAnimator.setInterpolator(new LinearInterpolator());
        scanLineAnimator.start();
    }

    @Override
    public void onStart() {
        super.onStart();
        Window window = requireDialog().getWindow();
        if (window != null && getView() != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            FullscreenInsets.apply(window, getView());
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!capturing && !pickingGallery) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                cameraPermission.launch(Manifest.permission.CAMERA);
            }
        }
    }

    @Override
    public void onPause() {
        unbindCamera();
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        if (scanLineAnimator != null) {
            scanLineAnimator.cancel();
            scanLineAnimator = null;
        }
        unbindCamera();
        binding = null;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (faceDetector != null) {
            faceDetector.close();
            faceDetector = null;
        }
        analysisExecutor.shutdownNow();
        ioExecutor.shutdownNow();
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        if (!resultSent) {
            Bundle args = getArguments();
            String dismissError = args != null ? args.getString(ARG_DISMISS_ERROR) : null;
            publishResult(null, dismissError);
        }
        super.onDismiss(dialog);
    }

    private void unbindCamera() {
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        imageAnalysis = null;
        imageCapture = null;
    }

    private void startCamera() {
        if (!isAdded() || binding == null || imageCapture != null) return;
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(requireContext());
        future.addListener(() -> {
            if (!isAdded() || binding == null) return;
            try {
                cameraProvider = future.get();
                bindCameraUseCases();
            } catch (Exception e) {
                publishResult(null, "Không thể mở camera trước. Vui lòng thử lại hoặc chọn ảnh thư viện.");
            }
        }, ContextCompat.getMainExecutor(requireContext()));
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null || binding == null) return;
        cameraProvider.unbindAll();

        CameraSelector selector = CameraSelector.DEFAULT_FRONT_CAMERA;
        try {
            if (!cameraProvider.hasCamera(selector)) {
                publishResult(null, "Thiết bị không có camera trước hỗ trợ quét khuôn mặt.");
                return;
            }
        } catch (Exception ignored) {
            // Fall through and let bindToLifecycle attempt front camera binding.
        }

        int rotation = binding.facePreviewView.getDisplay() != null
                ? binding.facePreviewView.getDisplay().getRotation()
                : Surface.ROTATION_0;

        Preview preview = new Preview.Builder()
                .setTargetRotation(rotation)
                .build();
        preview.setSurfaceProvider(binding.facePreviewView.getSurfaceProvider());

        imageCapture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setJpegQuality(92)
                .setTargetRotation(rotation)
                .build();

        imageAnalysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetRotation(rotation)
                .build();
        imageAnalysis.setAnalyzer(analysisExecutor, this::analyzeFrame);

        cameraProvider.bindToLifecycle(this, selector, preview, imageCapture, imageAnalysis);
        binding.facePreviewLoading.setVisibility(View.GONE);
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyzeFrame(@NonNull ImageProxy imageProxy) {
        if (capturing || pickingGallery || faceDetector == null || !isAdded()) {
            imageProxy.close();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastAnalysisTimeMs < ANALYSIS_INTERVAL_MS) {
            imageProxy.close();
            return;
        }
        lastAnalysisTimeMs = now;

        Image mediaImage = imageProxy.getImage();
        if (mediaImage == null) {
            imageProxy.close();
            return;
        }

        double luma = estimateAverageLuma(imageProxy);
        if (luma < 42.0) {
            stableFrames = 0;
            updateScanUi(false, 0, "Ánh sáng hơi yếu, hãy hướng mặt ra chỗ sáng", false);
            imageProxy.close();
            return;
        }
        if (luma > 235.0) {
            stableFrames = 0;
            updateScanUi(false, 0, "Ánh sáng quá chói, tránh nguồn sáng phía sau", true);
            imageProxy.close();
            return;
        }

        int rotation = imageProxy.getImageInfo().getRotationDegrees();
        int uprightW = (rotation == 90 || rotation == 270) ? imageProxy.getHeight() : imageProxy.getWidth();
        int uprightH = (rotation == 90 || rotation == 270) ? imageProxy.getWidth() : imageProxy.getHeight();
        InputImage inputImage = InputImage.fromMediaImage(mediaImage, rotation);

        faceDetector.process(inputImage)
                .addOnSuccessListener(faces -> handleDetectedFaces(faces, uprightW, uprightH))
                .addOnFailureListener(e -> {
                    stableFrames = 0;
                    updateScanUi(true, 0, "Đưa khuôn mặt vào giữa vòng tròn", false);
                })
                .addOnCompleteListener(task -> imageProxy.close());
    }

    private void handleDetectedFaces(List<Face> faces, int uprightW, int uprightH) {
        if (capturing || binding == null || !isAdded()) return;
        if (faces == null || faces.isEmpty()) {
            stableFrames = 0;
            updateScanUi(true, 0, "Đưa khuôn mặt vào giữa khung tròn", false);
            return;
        }
        if (faces.size() > 1) {
            stableFrames = 0;
            updateScanUi(true, 0, "Chỉ giữ một khuôn mặt trong khung hình", false);
            return;
        }

        Face face = faces.get(0);
        Rect box = face.getBoundingBox();
        float widthRatio = box.width() / (float) Math.max(1, uprightW);
        float heightRatio = box.height() / (float) Math.max(1, uprightH);
        float minRatio = Math.min(widthRatio, heightRatio);

        if (minRatio < 0.24f) {
            stableFrames = 0;
            updateScanUi(true, 15, "Đưa khuôn mặt lại gần camera hơn chút", false);
            return;
        }

        boolean cropped = box.left < uprightW * 0.03f
                || box.top < uprightH * 0.03f
                || box.right > uprightW * 0.97f
                || box.bottom > uprightH * 0.97f;
        if (cropped) {
            stableFrames = 0;
            updateScanUi(true, 20, "Giữ toàn bộ khuôn mặt nằm gọn trong vòng tròn", false);
            return;
        }

        float cx = box.exactCenterX() / (float) Math.max(1, uprightW);
        float cy = box.exactCenterY() / (float) Math.max(1, uprightH);
        if (cx < 0.25f || cx > 0.75f || cy < 0.22f || cy > 0.78f) {
            stableFrames = 0;
            updateScanUi(true, 25, "Căn khuôn mặt vào chính giữa vòng tròn", false);
            return;
        }

        if (Math.abs(face.getHeadEulerAngleY()) > 18f || Math.abs(face.getHeadEulerAngleZ()) > 18f) {
            stableFrames = 0;
            updateScanUi(true, 30, "Vui lòng nhìn thẳng vào camera", false);
            return;
        }

        stableFrames++;
        int progress = Math.min(100, (stableFrames * 100) / requiredStableFrames);
        updateScanUi(true, progress,
                stableFrames >= requiredStableFrames
                        ? "Đã nhận diện rõ! Đang xác thực..."
                        : "Giữ yên giây lát, đang quét khuôn mặt...",
                false);

        if (stableFrames >= requiredStableFrames && !capturing) {
            capturing = true;
            mainHandler.post(this::triggerAutoCapture);
        }
    }

    private void updateScanUi(boolean lightOk, int progress, String statusText, boolean glare) {
        mainHandler.post(() -> {
            if (binding == null || !isAdded()) return;
            binding.faceScanStatus.setText(statusText);
            binding.faceScanProgress.setProgressCompat(progress, true);

            int successColor = ContextCompat.getColor(requireContext(), R.color.success);
            int navyColor = ContextCompat.getColor(requireContext(), R.color.navy);
            int dangerColor = ContextCompat.getColor(requireContext(), R.color.danger);

            if (progress >= 60) {
                binding.faceScanProgress.setIndicatorColor(successColor);
                if (currentRingColor != successColor) applyGuideRingColor(successColor, 3);
            } else {
                binding.faceScanProgress.setIndicatorColor(navyColor);
                int idleRing = ContextCompat.getColor(requireContext(), R.color.glass_border);
                if (currentRingColor != idleRing) applyGuideRingColor(idleRing, 2);
            }

            if (lightOk) {
                binding.badgeLighting.setBackgroundResource(R.drawable.badge_status_success);
                binding.badgeLighting.setText("☀ Đủ ánh sáng");
                binding.badgeLighting.setTextColor(successColor);
            } else {
                binding.badgeLighting.setBackgroundResource(R.drawable.badge_status_warning);
                binding.badgeLighting.setText(glare ? "☀ Hơi chói" : "☀ Thiếu sáng");
                binding.badgeLighting.setTextColor(dangerColor);
            }
        });
    }

    private void triggerAutoCapture() {
        if (binding == null || imageCapture == null || !isAdded()) {
            capturing = false;
            stableFrames = 0;
            return;
        }
        binding.faceScanLine.setVisibility(View.GONE);
        binding.faceScanSubtitle.setText("Đang chụp và chuẩn hóa ảnh khuôn mặt...");
        File rawFile = new File(requireContext().getCacheDir(), "face-raw-" + UUID.randomUUID() + ".jpg");
        ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions.Builder(rawFile).build();
        imageCapture.takePicture(outputOptions, ioExecutor, new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                normalizeAndDeliver(rawFile);
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                rawFile.delete();
                mainHandler.post(() -> {
                    if (binding == null || !isAdded()) return;
                    capturing = false;
                    stableFrames = 0;
                    binding.faceScanLine.setVisibility(View.VISIBLE);
                    binding.faceScanSubtitle.setText("Đưa khuôn mặt vào khung tròn và nhìn thẳng vào camera");
                    binding.faceScanStatus.setText("Không chụp được khung hình, đang quét lại...");
                });
            }
        });
    }

    private void normalizeAndDeliver(@NonNull File rawFile) {
        try {
            File normalized = saveNormalizedJpeg(rawFile);
            rawFile.delete();
            if (normalized == null) {
                mainHandler.post(() -> {
                    capturing = false;
                    stableFrames = 0;
                    if (binding != null) binding.faceScanLine.setVisibility(View.VISIBLE);
                });
                return;
            }
            mainHandler.post(() -> publishResult(normalized.getAbsolutePath(), null));
        } catch (Exception e) {
            rawFile.delete();
            mainHandler.post(() -> {
                capturing = false;
                stableFrames = 0;
                if (binding != null) binding.faceScanLine.setVisibility(View.VISIBLE);
            });
        }
    }

    private void handleGalleryUri(@Nullable Uri uri) {
        pickingGallery = false;
        if (uri == null || !isAdded()) {
            return;
        }
        capturing = true;
        if (binding != null) {
            binding.faceScanLine.setVisibility(View.GONE);
            binding.faceScanStatus.setText("Đang xử lý ảnh từ thư viện...");
        }
        ioExecutor.execute(() -> {
            File temp = new File(requireContext().getCacheDir(), "face-gal-" + UUID.randomUUID() + ".jpg");
            try (InputStream in = requireContext().getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(temp)) {
                if (in == null) throw new IOException("Null stream");
                byte[] buffer = new byte[8192];
                int read;
                long total = 0;
                while ((read = in.read(buffer)) != -1) {
                    total += read;
                    if (total > 20_000_000L) throw new IOException("File too large");
                    out.write(buffer, 0, read);
                }
                File normalized = saveNormalizedJpeg(temp);
                temp.delete();
                if (normalized == null) throw new IOException("Decode failed");
                mainHandler.post(() -> publishResult(normalized.getAbsolutePath(), null));
            } catch (Exception e) {
                temp.delete();
                mainHandler.post(() -> {
                    capturing = false;
                    stableFrames = 0;
                    if (binding != null) {
                        binding.faceScanLine.setVisibility(View.VISIBLE);
                        binding.faceScanStatus.setText("Không đọc được ảnh đã chọn. Hãy đưa mặt vào camera.");
                    }
                });
            }
        });
    }

    @Nullable
    private File saveNormalizedJpeg(@NonNull File source) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(source.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
        decodeOpts.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / decodeOpts.inSampleSize > MAX_DIMENSION_PX * 2) {
            decodeOpts.inSampleSize *= 2;
        }
        Bitmap bitmap = BitmapFactory.decodeFile(source.getAbsolutePath(), decodeOpts);
        if (bitmap == null) return null;

        Bitmap oriented = applyExifOrientation(bitmap, source.getAbsolutePath());
        Bitmap scaled = scaleDownIfNeeded(oriented, MAX_DIMENSION_PX);
        if (scaled != oriented) oriented.recycle();

        File outFile = new File(requireContext().getCacheDir(), "face-scan-" + UUID.randomUUID() + ".jpg");
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, 92, fos)) {
                outFile.delete();
                return null;
            }
        } finally {
            scaled.recycle();
        }
        return outFile;
    }

    private static Bitmap applyExifOrientation(Bitmap bitmap, String path) {
        int orientation;
        try {
            orientation = new ExifInterface(path).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException e) {
            return bitmap;
        }
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
        Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (rotated != bitmap) bitmap.recycle();
        return rotated;
    }

    private static Bitmap scaleDownIfNeeded(Bitmap bitmap, int maxDim) {
        int max = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (max <= maxDim) return bitmap;
        float scale = maxDim / (float) max;
        int w = Math.max(160, Math.round(bitmap.getWidth() * scale));
        int h = Math.max(160, Math.round(bitmap.getHeight() * scale));
        return Bitmap.createScaledBitmap(bitmap, w, h, true);
    }

    private static double estimateAverageLuma(@NonNull ImageProxy imageProxy) {
        ImageProxy.PlaneProxy[] planes = imageProxy.getPlanes();
        if (planes.length == 0) return 128.0;
        ByteBuffer buffer = planes[0].getBuffer();
        int remaining = buffer.remaining();
        if (remaining == 0) return 128.0;
        long sum = 0;
        int count = 0;
        int step = Math.max(16, remaining / 400);
        for (int i = 0; i < remaining; i += step) {
            sum += (buffer.get(i) & 0xFF);
            count++;
        }
        return count == 0 ? 128.0 : sum / (double) count;
    }

    private void publishResult(@Nullable String path, @Nullable String error) {
        if (resultSent) return;
        resultSent = true;
        Bundle args = getArguments();
        String resultKey = args != null ? args.getString(ARG_RESULT_KEY, RESULT_KEY) : RESULT_KEY;
        int slot = args != null ? args.getInt(ARG_SLOT, -1) : -1;
        Bundle result = new Bundle();
        if (slot >= 0) result.putInt(ARG_SLOT, slot);
        if (path != null) result.putString(RESULT_PATH, path);
        if (error != null) result.putString(RESULT_ERROR, error);
        if (isAdded()) {
            getParentFragmentManager().setFragmentResult(resultKey, result);
            dismissAllowingStateLoss();
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

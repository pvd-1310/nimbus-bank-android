package com.example.final_term.ui.ekyc;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.graphics.ImageDecoder;
import android.os.Build;
import android.net.Uri;
import android.os.Bundle;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;

import com.google.android.gms.tasks.Tasks;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Captures a full-resolution camera image, returning only a bounded JPEG. */
public final class FaceCamera {
    private static final ExecutorService PHOTO_WORK = Executors.newSingleThreadExecutor();
    private static final String SAVED_FILE = "face_camera_file";

    private final Fragment fragment;
    private final ActivityResultLauncher<Uri> camera;
    private final ActivityResultLauncher<String> permission;
    private final Consumer<byte[]> captured;
    private final Consumer<String> failed;
    private File pending;

    public FaceCamera(Fragment fragment, Consumer<byte[]> captured, Consumer<String> failed) {
        this.fragment = fragment;
        this.captured = captured;
        this.failed = failed;
        camera = fragment.registerForActivityResult(new ActivityResultContracts.TakePicture(), this::finish);
        permission = fragment.registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) launch();
            else failed.accept("Cần quyền camera để chụp khuôn mặt.");
        });
    }

    public void open() {
        if (pending != null) return;
        if (ContextCompat.checkSelfPermission(fragment.requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) launch();
        else permission.launch(Manifest.permission.CAMERA);
    }

    public void restore(Bundle state) {
        if (state == null) return;
        String name = state.getString(SAVED_FILE);
        if (name != null) pending = new File(directory(), name);
    }

    public void save(Bundle state) {
        if (pending != null) state.putString(SAVED_FILE, pending.getName());
    }

    private File directory() {
        return new File(fragment.requireContext().getCacheDir(), "ekyc-camera");
    }

    private void launch() {
        if (!fragment.isAdded()) return;
        Context context = fragment.requireContext();
        try {
            File directory = directory();
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Camera cache unavailable");
            pending = File.createTempFile("face-", ".jpg", directory);
            camera.launch(FileProvider.getUriForFile(context, context.getPackageName() + ".ekyc-camera", pending));
        } catch (IOException | RuntimeException error) {
            discard();
            failed.accept("Không thể mở camera. Vui lòng thử lại.");
        }
    }

    private void discard() {
        if (pending != null) pending.delete();
        pending = null;
    }

    private Bitmap decode(File file) throws IOException {
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file), (decoder, info, source) -> {
                int width = info.getSize().getWidth();
                int height = info.getSize().getHeight();
                double scale = Math.min(1, 1600.0 / Math.max(width, height));
                decoder.setTargetSize(Math.max(1, (int) (width * scale)), Math.max(1, (int) (height * scale)));
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), options);
        if (options.outWidth <= 0 || options.outHeight <= 0) throw new IOException("Invalid camera image");
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 1600) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        ExifInterface exif = new ExifInterface(file.getPath());
        Bitmap original = BitmapFactory.decodeFile(file.getPath(), options);
        if (original == null) throw new IOException("Invalid camera image");
        int tag = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        Matrix orientation = new Matrix();
        switch (tag) {
            case ExifInterface.ORIENTATION_ROTATE_90: orientation.setRotate(90); break;
            case ExifInterface.ORIENTATION_ROTATE_180: orientation.setRotate(180); break;
            case ExifInterface.ORIENTATION_ROTATE_270: orientation.setRotate(270); break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: orientation.setScale(-1, 1); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: orientation.setScale(1, -1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE: orientation.setScale(-1, 1); orientation.postRotate(270); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: orientation.setScale(-1, 1); orientation.postRotate(90); break;
            default: return original;
        }
        Bitmap rotated = Bitmap.createBitmap(original, 0, 0, original.getWidth(), original.getHeight(), orientation, true);
        if (rotated != original) original.recycle();
        return rotated;
    }

    private void finish(boolean ok) {
        if (!ok || pending == null) {
            discard();
            failed.accept("Đã hủy chụp ảnh.");
            return;
        }
        File file = pending;
        pending = null;
        Tasks.call(PHOTO_WORK, () -> {
            try {
                if (file.length() == 0 || file.length() > 30_000_000) throw new IOException("Invalid camera file");
                Bitmap bitmap = decode(file);
                try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)
                            || output.size() == 0 || output.size() > 5_000_000) throw new IOException("Invalid JPEG");
                    return output.toByteArray();
                } finally {
                    bitmap.recycle();
                }
            } finally {
                file.delete();
            }
        }).addOnSuccessListener(captured::accept)
          .addOnFailureListener(error -> failed.accept("Không đọc được ảnh. Vui lòng chụp lại."));
    }
}

package com.example.final_term.ui.ekyc;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.Fragment;

import com.google.android.gms.tasks.Tasks;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Selects a gallery image and returns a bounded, correctly oriented JPEG. */
public final class FaceGalleryPicker {
    private static final ExecutorService IMAGE_WORK = Executors.newSingleThreadExecutor();
    private static final int MAX_DIMENSION = 1600;
    private static final int MAX_JPEG_BYTES = 5_000_000;

    private final Fragment fragment;
    private final ActivityResultLauncher<String> picker;
    private final Consumer<byte[]> selected;
    private final Consumer<String> failed;

    public FaceGalleryPicker(Fragment fragment, Consumer<byte[]> selected, Consumer<String> failed) {
        this.fragment = fragment;
        this.selected = selected;
        this.failed = failed;
        picker = fragment.registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri == null) {
                failed.accept("Chưa chọn ảnh. Vui lòng thử lại.");
                return;
            }
            process(uri);
        });
    }

    public void open() {
        picker.launch("image/*");
    }

    private void process(Uri uri) {
        Tasks.call(IMAGE_WORK, () -> encode(uri))
                .addOnSuccessListener(selected::accept)
                .addOnFailureListener(error -> failed.accept("Không đọc được ảnh. Vui lòng chọn ảnh khác."));
    }

    private byte[] encode(Uri uri) throws IOException {
        Bitmap bitmap = decode(uri);
        try {
            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)
                        || output.size() == 0 || output.size() > MAX_JPEG_BYTES) {
                    throw new IOException("Image cannot be encoded as a bounded JPEG");
                }
                return output.toByteArray();
            }
        } finally {
            bitmap.recycle();
        }
    }

    private Bitmap decode(Uri uri) throws IOException {
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.Source source = ImageDecoder.createSource(fragment.requireContext().getContentResolver(), uri);
            return ImageDecoder.decodeBitmap(source, (decoder, info, sourceInfo) -> {
                int width = info.getSize().getWidth();
                int height = info.getSize().getHeight();
                double scale = Math.min(1, (double) MAX_DIMENSION / Math.max(width, height));
                decoder.setTargetSize(Math.max(1, (int) (width * scale)), Math.max(1, (int) (height * scale)));
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
        }

        android.content.ContentResolver resolver = fragment.requireContext().getContentResolver();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) throw new IOException("Image is unavailable");
            BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Invalid image");

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > MAX_DIMENSION * 2) {
            options.inSampleSize *= 2;
        }
        Bitmap original;
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) throw new IOException("Image is unavailable");
            original = BitmapFactory.decodeStream(input, null, options);
        }
        if (original == null) throw new IOException("Invalid image");

        int orientation;
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) throw new IOException("Image is unavailable");
            orientation = new ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
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
            default: return scaleDown(original);
        }
        Bitmap oriented = Bitmap.createBitmap(original, 0, 0,
                original.getWidth(), original.getHeight(), matrix, true);
        if (oriented != original) original.recycle();
        return scaleDown(oriented);
    }

    private Bitmap scaleDown(Bitmap bitmap) {
        int largest = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (largest <= MAX_DIMENSION) return bitmap;
        float scale = (float) MAX_DIMENSION / largest;
        Bitmap resized = Bitmap.createScaledBitmap(bitmap,
                Math.max(1, Math.round(bitmap.getWidth() * scale)),
                Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
        if (resized != bitmap) bitmap.recycle();
        return resized;
    }
}

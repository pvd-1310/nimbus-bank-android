package com.example.final_term.ui.ekyc;

import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Shared in-app CameraX selfie capture used by transfer, top-up and face login flows. */
public final class FaceCamera {
    private static final String TAG = "camerax-selfie";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private final Fragment fragment;
    private final Consumer<byte[]> captured;
    private final Consumer<String> failed;

    public FaceCamera(Fragment fragment, Consumer<byte[]> captured, Consumer<String> failed) {
        this.fragment = fragment; this.captured = captured; this.failed = failed;
    }

    public void attach() {
        if (!fragment.isAdded()) return;
        fragment.getParentFragmentManager().setFragmentResultListener(InAppCameraDialogFragment.RESULT_KEY, fragment,
                (key, result) -> { String path = result.getString("path"); if (path == null) return; IO.execute(() -> { try { byte[] bytes = Files.readAllBytes(new File(path).toPath()); new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> captured.accept(bytes)); } catch (IOException e) { failed.accept("Không đọc được ảnh. Vui lòng chụp lại."); } finally { new File(path).delete(); } }); });
    }
    public void open() {
        if (!fragment.isAdded()) return;
        if (fragment.getParentFragmentManager().findFragmentByTag(TAG) != null) return;
        InAppCameraDialogFragment.newInstance(2, false, false).show(fragment.getParentFragmentManager(), TAG);
    }
    public void restore(@Nullable Bundle state) { }
    public void save(Bundle state) { }
}

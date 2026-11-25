package com.example.final_term.ui.profile;

import android.Manifest;
import android.content.Context;
import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.appcompat.app.AlertDialog;

import com.example.final_term.R;
import com.example.final_term.auth.AuthState;
import com.example.final_term.core.FinalTermApplication;
import com.example.final_term.data.AvatarCache;
import com.example.final_term.data.BackendApiClient;
import com.example.final_term.databinding.FragmentProfileBinding;
import com.example.final_term.databinding.DialogProfileContactBinding;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public final class ProfileFragment extends Fragment {
    private static final String TAG = "ProfileAvatar";
    private FragmentProfileBinding binding;
    private BackendApiClient apiClient;
    private boolean profileLoaded;
    private JSONObject currentProfile = new JSONObject();
    private ActivityResultLauncher<String> avatarPicker;
    private boolean avatarUploading;
    private String currentUserId;
    private static final ExecutorService AVATAR_PROCESSOR = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        avatarPicker = registerForActivityResult(new ActivityResultContracts.GetContent(), this::onAvatarPicked);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle state) {
        binding = FragmentProfileBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        FinalTermApplication app = (FinalTermApplication) requireActivity().getApplication();
        apiClient = app.getAppContainer().getBackendApiClient();

        View.OnClickListener openEkycListener = v -> {
            com.example.final_term.ui.ekyc.EkycDialogFragment dialog = new com.example.final_term.ui.ekyc.EkycDialogFragment();
            dialog.show(getParentFragmentManager(), "ekyc_dialog");
        };

        binding.itemPersonalInfo.setOnClickListener(v -> openProfileEditor());
        binding.profileAvatarContainer.setOnClickListener(v -> {
            if (!avatarUploading && avatarPicker != null) avatarPicker.launch("image/*");
        });
        binding.ekycBadge.setOnClickListener(openEkycListener);

        app.getAppContainer().getAuthRepository().observeAuthState()
                .observe(getViewLifecycleOwner(), authState -> {
                    render(authState);
                    if (authState != null && authState.getUser() != null) {
                        boolean customer = authState.getUser().getRole()
                                == com.example.final_term.auth.UserRole.CUSTOMER;
                        binding.ekycBadge.setVisibility(customer ? View.VISIBLE : View.GONE);
                        if (customer) fetchEkycStatus();
                        binding.itemPersonalInfo.setVisibility(customer ? View.VISIBLE : View.GONE);
                        binding.itemPersonalInfo.setOnClickListener(customer ? v -> openProfileEditor() : null);
                        binding.ekycBadge.setOnClickListener(customer ? openEkycListener : null);
                        loadProfile();
                    }
                });

        getParentFragmentManager().setFragmentResultListener("ekyc_status_changed", getViewLifecycleOwner(),
                (requestKey, result) -> fetchEkycStatus());

        binding.logoutButton.setOnClickListener(v -> app.getAppContainer().getAuthRepository().signOut());

        binding.itemCameraSecurity.setOnClickListener(v -> showCameraAndBiometricDialog());

        binding.itemNotifications.setOnClickListener(v -> openProfileEditor());
        binding.itemLanguage.setOnClickListener(v -> openProfileEditor());

        fetchCameraAndCapabilitiesStatus();
    }

    private void fetchCameraAndCapabilitiesStatus() {
        if (!isAdded() || binding == null) return;
        boolean cameraPermission = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;

        if (apiClient == null) {
            binding.cameraStatusText.setText(cameraPermission
                    ? "Chưa thể kiểm tra đăng nhập khuôn mặt."
                    : "Cho phép máy ảnh để dùng xác thực khuôn mặt.");
            return;
        }

        apiClient.get("/capabilities").addOnCompleteListener(task -> {
            if (!isAdded() || binding == null) return;
            boolean faceAvailable = false;
            if (task.isSuccessful() && task.getResult() != null && task.getResult().statusCode == 200) {
                try {
                    JSONObject cap = new JSONObject(task.getResult().body);
                    faceAvailable = "ENABLED".equals(cap.optString("face"));
                } catch (Exception ignored) { }
            }
            binding.cameraStatusText.setText(!cameraPermission
                    ? "Cho phép máy ảnh để dùng xác thực khuôn mặt."
                    : faceAvailable ? "Xác thực khuôn mặt đã sẵn sàng."
                    : "Xác thực khuôn mặt hiện chưa khả dụng.");
        });
    }

    private void showCameraAndBiometricDialog() {
        boolean cameraPermission = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;

        new AlertDialog.Builder(requireContext())
                .setTitle("Bảo mật khuôn mặt")
                .setMessage((cameraPermission
                        ? "Máy ảnh đã được cấp quyền. "
                        : "Máy ảnh chưa được cấp quyền. Bạn có thể bật quyền này trong Cài đặt thiết bị. ") +
                        "Ảnh selfie dùng cho đăng ký và đăng nhập bằng khuôn mặt; phương thức này chưa xác minh CCCD.")
                .setNeutralButton("Xóa dữ liệu khuôn mặt", (dialog, which) -> confirmRevokeFaceTemplate())
                .setPositiveButton("Đóng", null)
                .show();
    }

    private void confirmRevokeFaceTemplate() {
        new AlertDialog.Builder(requireContext())
                .setTitle("Xóa dữ liệu khuôn mặt")
                .setMessage("Thao tác này sẽ tắt đăng nhập khuôn mặt, đặt eKYC về trạng thái cần đăng ký lại và đưa ảnh eKYC đã tải lên vào hàng chờ xóa. Tài khoản của bạn vẫn hoạt động; bạn cần hoàn tất eKYC mới để bật lại đăng nhập khuôn mặt. Tiếp tục thu hồi?")
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Thu hồi ngay", (d, which) -> {
                    if (apiClient == null) return;
                    Toast.makeText(requireContext(), "Đang gửi yêu cầu thu hồi…", Toast.LENGTH_SHORT).show();
                    apiClient.post("/me/face/revoke", "{}").addOnCompleteListener(task -> {
                        if (!isAdded() || binding == null) return;
                        if (task.isSuccessful() && task.getResult() != null && task.getResult().statusCode == 200) {
                            String message = "Đã gửi yêu cầu thu hồi.";
                            try {
                                JSONObject result = new JSONObject(task.getResult().body);
                                if ("RESUBMIT_REQUIRED".equals(result.optString("ekycStatus"))) {
                                    message = "Đã thu hồi mẫu khuôn mặt và đặt lại eKYC.";
                                    JSONObject cleanup = result.optJSONObject("evidenceCleanup");
                                    if (cleanup != null && "PENDING".equals(cleanup.optString("status"))) {
                                        message += " Ảnh đã được đưa vào hàng chờ xóa.";
                                    } else if (cleanup != null && "NOT_REQUIRED".equals(cleanup.optString("status"))) {
                                        message += " Không có ảnh eKYC nào cần xóa.";
                                    }
                                } else {
                                    message = "Chưa thể cập nhật trạng thái định danh. Vui lòng thử lại sau.";
                                }
                            } catch (Exception ignored) { }
                            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
                            fetchEkycStatus();
                            fetchCameraAndCapabilitiesStatus();
                        } else {
                            String message = task.isSuccessful()
                                    ? "Không thể xóa dữ liệu khuôn mặt. Vui lòng thử lại sau."
                                    : "Chưa thể kết nối để xóa dữ liệu khuôn mặt. Vui lòng thử lại.";
                            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
                        }
                    });
                })
                .show();
    }

    private void loadProfile() {
        if (apiClient == null || binding == null) return;
        var current = binding;
        profileLoaded = false;
        current.personalInfoSummary.setText("Đang tải thông tin…");
        apiClient.get("/me").addOnCompleteListener(task -> {
            if (binding != current) return;
            try {
                if (!task.isSuccessful() || task.getResult().statusCode != 200)
                    throw new org.json.JSONException("Unavailable");
                currentProfile = new JSONObject(task.getResult().body);
                profileLoaded = true;
                loadAvatar();
            } catch (org.json.JSONException ignored) {
                currentProfile = new JSONObject();
            }
            renderPreferenceSummaries();
        });
    }

    private void openProfileEditor() {
        if (!isAdded()) return;
        if (profileLoaded) {
            showContactEditor();
        } else if (apiClient != null) {
            Toast.makeText(requireContext(), "Đang tải thông tin cá nhân…", Toast.LENGTH_SHORT).show();
            loadProfile();
        } else {
            Toast.makeText(requireContext(), "Chưa thể tải thông tin cá nhân. Vui lòng thử lại.", Toast.LENGTH_SHORT).show();
        }
    }

    private void renderPreferenceSummaries() {
        if (binding == null) return;
        if (profileLoaded && currentProfile != null) {
            String customerNo = currentProfile.optString("customerNo", "").trim();
            if (!customerNo.isEmpty()) binding.customerNoText.setText("Mã KH: " + customerNo);
            String tier = currentProfile.optString("tier", "").trim();
            if (!tier.isEmpty()) {
                binding.customerTierText.setText("PLATINUM".equalsIgnoreCase(tier) ? "Hạng Platinum" : "Hạng " + tier);
            }
        }
        if (!profileLoaded) {
            binding.personalInfoSummary.setText("Chạm để cập nhật email hoặc số điện thoại");
            binding.notificationsStatusText.setText("—");
            binding.languageStatusText.setText("—");
            return;
        }
        String email = currentProfile.optString("contactEmail", "").trim();
        String phone = currentProfile.optString("contactPhone", "").trim();
        if (!email.isEmpty() && !phone.isEmpty()) {
            binding.personalInfoSummary.setText("Email và số điện thoại đã cập nhật");
        } else if (!email.isEmpty()) {
            binding.personalInfoSummary.setText("Email đã cập nhật");
        } else if (!phone.isEmpty()) {
            binding.personalInfoSummary.setText("Số điện thoại đã cập nhật");
        } else {
            binding.personalInfoSummary.setText("Thêm email hoặc số điện thoại liên hệ");
        }
        JSONObject preferences = currentProfile.optJSONObject("preferences");
        boolean notificationsEnabled = preferences == null
                || preferences.optBoolean("notificationsEnabled", true);
        String language = preferences == null ? "vi" : preferences.optString("language", "vi");
        binding.notificationsStatusText.setText(notificationsEnabled ? "Bật" : "Tắt");
        binding.languageStatusText.setText("en".equalsIgnoreCase(language) ? "English" : "Tiếng Việt");
    }

    private void showContactEditor() {
        DialogProfileContactBinding form = DialogProfileContactBinding.inflate(getLayoutInflater());
        JSONObject preferences = currentProfile.optJSONObject("preferences");
        form.contactEmailInput.setText(currentProfile.optString("contactEmail", ""));
        form.contactPhoneInput.setText(currentProfile.optString("contactPhone", ""));
        form.notificationsSwitch.setChecked(preferences == null
                || preferences.optBoolean("notificationsEnabled", true));
        String languageCode = preferences == null ? "vi" : preferences.optString("language", "vi");
        String[] languageLabels = {"Tiếng Việt", "English"};
        form.languageInput.setSimpleItems(languageLabels);
        form.languageInput.setText("en".equalsIgnoreCase(languageCode) ? "English" : "Tiếng Việt", false);

        BottomSheetDialog dialog = new BottomSheetDialog(requireContext());
        dialog.setContentView(form.getRoot());
        dialog.setOnShowListener(ignored -> {
            FrameLayout sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet != null) sheet.setBackgroundColor(Color.TRANSPARENT);
            dialog.getBehavior().setSkipCollapsed(true);
            dialog.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
        });
        form.closeButton.setOnClickListener(v -> dialog.dismiss());
        form.cancelButton.setOnClickListener(v -> dialog.dismiss());
        form.saveButton.setOnClickListener(v -> {
                String email = form.contactEmailInput.getText() == null
                        ? "" : form.contactEmailInput.getText().toString().trim();
                String phone = form.contactPhoneInput.getText() == null
                        ? "" : form.contactPhoneInput.getText().toString().trim();
                form.contactEmailLayout.setError(null);
                form.contactPhoneLayout.setError(null);
                form.formStatusText.setVisibility(View.GONE);

                if (!email.isEmpty() && !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                    form.contactEmailLayout.setError("Nhập email hợp lệ.");
                    form.contactEmailInput.requestFocus();
                    return;
                }
                if (!phone.isEmpty() && !phone.matches("\\+[1-9][0-9]{7,14}")) {
                    form.contactPhoneLayout.setError("Dùng định dạng quốc tế, ví dụ +84901234567.");
                    form.contactPhoneInput.requestFocus();
                    return;
                }

                View currentFocus = dialog.getCurrentFocus();
                if (currentFocus != null) {
                    InputMethodManager imm = (InputMethodManager) requireContext()
                            .getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.hideSoftInputFromWindow(currentFocus.getWindowToken(), 0);
                    currentFocus.clearFocus();
                }

                try {
                    JSONObject updatedPreferences = new JSONObject(preferences == null ? "{}" : preferences.toString());
                    String selectedLanguage = String.valueOf(form.languageInput.getText());
                    updatedPreferences.put("language", "English".equals(selectedLanguage) ? "en" : "vi");
                    updatedPreferences.put("notificationsEnabled", form.notificationsSwitch.isChecked());
                    JSONObject body = new JSONObject().put("preferences", updatedPreferences);
                    if (!email.isEmpty()) body.put("contactEmail", email);
                    if (!phone.isEmpty()) body.put("contactPhone", phone);

                    form.saveButton.setEnabled(false);
                    form.cancelButton.setEnabled(false);
                    form.closeButton.setEnabled(false);
                    form.saveButton.setText("Đang lưu…");
                    form.savingProgress.setVisibility(View.VISIBLE);
                    form.formStatusText.setText("Đang lưu thay đổi…");
                    form.formStatusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink_muted));
                    form.formStatusText.setVisibility(View.VISIBLE);
                    dialog.setCancelable(false);
                    dialog.getBehavior().setDraggable(false);

                    apiClient.patch("/me", body.toString()).addOnCompleteListener(task -> {
                        if (!isAdded() || binding == null) return;
                        form.savingProgress.setVisibility(View.GONE);
                        form.saveButton.setEnabled(true);
                        form.cancelButton.setEnabled(true);
                        form.closeButton.setEnabled(true);
                        form.saveButton.setText("Lưu thay đổi");
                        dialog.setCancelable(true);
                        dialog.getBehavior().setDraggable(true);
                        if (task.isSuccessful() && task.getResult() != null
                                && task.getResult().statusCode >= 200 && task.getResult().statusCode < 300) {
                            Toast.makeText(requireContext(), "Thông tin cá nhân đã được cập nhật.", Toast.LENGTH_SHORT).show();
                            dialog.dismiss();
                            loadProfile();
                            return;
                        }

                        String message = "Chưa thể lưu thay đổi. Vui lòng kiểm tra kết nối rồi thử lại.";
                        if (task.isSuccessful() && task.getResult() != null) {
                            try {
                                String error = new JSONObject(task.getResult().body).optString("error");
                                if ("CONTACT_PHONE_EXISTS".equals(error)) {
                                    form.contactPhoneLayout.setError("Số điện thoại này đã được sử dụng.");
                                    form.contactPhoneInput.requestFocus();
                                    message = "Hãy kiểm tra lại số điện thoại liên hệ.";
                                } else if ("CONTACT_EMAIL_EXISTS".equals(error)) {
                                    form.contactEmailLayout.setError("Email này đã được sử dụng.");
                                    form.contactEmailInput.requestFocus();
                                    message = "Hãy kiểm tra lại email liên hệ.";
                                } else {
                                    message = "Chưa thể lưu thay đổi. Vui lòng thử lại sau.";
                                }
                            } catch (org.json.JSONException ignoredResponse) {
                                message = "Chưa thể lưu thay đổi. Vui lòng thử lại sau.";
                            }
                        }
                        form.formStatusText.setText(message);
                        form.formStatusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.danger));
                        form.formStatusText.setVisibility(View.VISIBLE);
                    });
                } catch (Exception e) {
                    form.saveButton.setEnabled(true);
                    form.cancelButton.setEnabled(true);
                    form.closeButton.setEnabled(true);
                    form.saveButton.setText("Lưu thay đổi");
                    form.savingProgress.setVisibility(View.GONE);
                    dialog.setCancelable(true);
                    dialog.getBehavior().setDraggable(true);
                    form.formStatusText.setText("Không thể lưu thông tin. Vui lòng thử lại.");
                    form.formStatusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.danger));
                    form.formStatusText.setVisibility(View.VISIBLE);
                }
        });
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
    }

    private void fetchEkycStatus() {
        if (apiClient == null) return;
        apiClient.get("/ekyc").addOnCompleteListener(task -> {
            if (!isAdded() || binding == null) return;
            requireActivity().runOnUiThread(() -> {
                if (binding == null) return;
                if (task.isSuccessful() && task.getResult() != null && task.getResult().statusCode == 200) {
                    try {
                        JSONObject json = new JSONObject(task.getResult().body);
                        JSONArray items = json.optJSONArray("items");
                        if (items != null && items.length() > 0) {
                            JSONObject latest = items.getJSONObject(0);
                            String status = latest.optString("reviewStatus", "DRAFT");
                            applyEkycStatus(status, "DEMO_SELFIE_ONLY".equals(latest.optString("mode")));
                            return;
                        }
                        applyEkycStatus("NONE", "DEMO_SELFIE_ONLY".equals(json.optString("captureMode")));
                        return;
                    } catch (Exception ignored) {}
                }
                applyEkycStatus("NONE", false);
            });
        });
    }

    private void applyEkycStatus(String status, boolean demo) {
        if (binding == null) return;
        switch (status) {
            case "VERIFIED":
                binding.ekycBadge.setText(demo ? "✓ Đã đăng ký khuôn mặt" : "✓ Đã xác thực eKYC");
                binding.ekycBadge.setBackgroundResource(R.drawable.badge_status_success);
                binding.ekycBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.success));
                break;
            case "REVIEWING":
                binding.ekycBadge.setText(demo ? "⏳ Đang kiểm tra ảnh selfie" : "⏳ eKYC đang xử lý");
                binding.ekycBadge.setBackgroundResource(R.drawable.badge_status_warning);
                binding.ekycBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.warning));
                break;
            case "RESUBMIT_REQUIRED":
                binding.ekycBadge.setText(demo ? "⚠️ Cần chọn ảnh selfie khác" : "⚠️ Cần chọn lại ảnh");
                binding.ekycBadge.setBackgroundResource(R.drawable.badge_status_warning);
                binding.ekycBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.warning));
                break;
            case "REJECTED":
                binding.ekycBadge.setText("✕ eKYC Bị từ chối");
                binding.ekycBadge.setBackgroundResource(R.drawable.badge_status_danger);
                binding.ekycBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.danger));
                break;
            case "DRAFT":
                binding.ekycBadge.setText(demo ? "Chưa hoàn tất đăng ký khuôn mặt" : "Chưa hoàn tất eKYC");
                binding.ekycBadge.setBackgroundResource(R.drawable.glass_panel_soft);
                binding.ekycBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink_muted));
                break;
            default:
                binding.ekycBadge.setText(demo ? "Chưa đăng ký khuôn mặt" : "Chưa định danh eKYC");
                binding.ekycBadge.setBackgroundResource(R.drawable.glass_panel_soft);
                binding.ekycBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink_muted));
                break;
        }
    }

    private void onAvatarPicked(@Nullable Uri uri) {
        if (uri == null || !isAdded() || avatarUploading) return;
        avatarUploading = true;
        binding.avatarProgress.setVisibility(View.VISIBLE);
        Context context = requireContext().getApplicationContext();
        AVATAR_PROCESSOR.execute(() -> {
            try {
                byte[] jpeg = prepareAvatar(context.getContentResolver(), uri);
                mainHandler.post(() -> beginAvatarUpload(jpeg));
            } catch (Exception error) {
                Log.w(TAG, "image_prepare_failed type=" + error.getClass().getSimpleName());
                mainHandler.post(() -> finishAvatarUpload(false));
            }
        });
    }

    private byte[] prepareAvatar(ContentResolver resolver, Uri uri) throws IOException {
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input != null) orientation = new ExifInterface(input)
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        }

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) throw new IOException("IMAGE_UNAVAILABLE");
            BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("INVALID_IMAGE");
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 1024)
            options.inSampleSize *= 2;
        Bitmap decoded;
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) throw new IOException("IMAGE_UNAVAILABLE");
            decoded = BitmapFactory.decodeStream(input, null, options);
        }
        if (decoded == null) throw new IOException("INVALID_IMAGE");

        Matrix transform = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90: transform.setRotate(90); break;
            case ExifInterface.ORIENTATION_ROTATE_180: transform.setRotate(180); break;
            case ExifInterface.ORIENTATION_ROTATE_270: transform.setRotate(270); break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: transform.setScale(-1, 1); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: transform.setScale(1, -1); break;
            default: break;
        }
        Bitmap oriented = transform.isIdentity() ? decoded
                : Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), transform, true);
        if (oriented != decoded) decoded.recycle();
        int cropSize = Math.min(oriented.getWidth(), oriented.getHeight());
        int left = (oriented.getWidth() - cropSize) / 2;
        int top = (oriented.getHeight() - cropSize) / 2;
        Bitmap square = Bitmap.createBitmap(oriented, left, top, cropSize, cropSize);
        if (square != oriented) oriented.recycle();
        Bitmap resized = Bitmap.createScaledBitmap(square, 512, 512, true);
        if (resized != square) square.recycle();

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        resized.compress(Bitmap.CompressFormat.JPEG, 88, output);
        resized.recycle();
        byte[] bytes = output.toByteArray();
        if (bytes.length < 3 || bytes.length > 5_000_000) throw new IOException("INVALID_IMAGE_SIZE");
        return bytes;
    }

    private void beginAvatarUpload(byte[] jpeg) {
        if (!isAdded() || binding == null) return;
        if (apiClient == null) {
            finishAvatarUpload(false);
            return;
        }
        try {
            JSONObject request = new JSONObject().put("mime", "image/jpeg").put("bytes", jpeg.length);
            apiClient.post("/me/avatar", request.toString()).addOnCompleteListener(startTask -> {
                if (!isAdded() || binding == null) return;
                try {
                    if (!startTask.isSuccessful() || startTask.getResult() == null
                            || startTask.getResult().statusCode != 200) {
                        int status = startTask.isSuccessful() && startTask.getResult() != null
                                ? startTask.getResult().statusCode : 0;
                        String code = startTask.isSuccessful() && startTask.getResult() != null
                                ? safeApiError(startTask.getResult().body) : "NETWORK_ERROR";
                        Log.w(TAG, "ticket_request_failed status=" + status + " code=" + code);
                        throw new IOException("UPLOAD_SETUP_FAILED");
                    }
                    JSONObject ticket = new JSONObject(startTask.getResult().body);
                    String id = ticket.getString("id");
                    JSONObject fields = ticket.getJSONObject("fields");
                    apiClient.uploadToPresignedPost(ticket.getString("url"), fields, "image/jpeg", jpeg)
                            .addOnCompleteListener(uploadTask -> {
                                if (!isAdded() || binding == null) return;
                                if (!uploadTask.isSuccessful() || uploadTask.getResult() == null
                                        || uploadTask.getResult().statusCode < 200
                                        || uploadTask.getResult().statusCode >= 300) {
                                    int status = uploadTask.isSuccessful() && uploadTask.getResult() != null
                                            ? uploadTask.getResult().statusCode : 0;
                                    String code = uploadTask.isSuccessful() && uploadTask.getResult() != null
                                            ? safeS3Error(uploadTask.getResult().body) : "NETWORK_ERROR";
                                    Log.w(TAG, "storage_upload_failed status=" + status + " code=" + code);
                                    finishAvatarUpload(false);
                                    return;
                                }
                                apiClient.post("/me/avatar/" + id + "/confirm", "{}")
                                        .addOnCompleteListener(confirmTask -> {
                                            if (!isAdded() || binding == null) return;
                                            boolean saved = confirmTask.isSuccessful() && confirmTask.getResult() != null
                                                    && confirmTask.getResult().statusCode >= 200
                                                    && confirmTask.getResult().statusCode < 300;
                                            if (!saved) {
                                                int status = confirmTask.isSuccessful() && confirmTask.getResult() != null
                                                        ? confirmTask.getResult().statusCode : 0;
                                                String code = confirmTask.isSuccessful() && confirmTask.getResult() != null
                                                        ? safeApiError(confirmTask.getResult().body) : "NETWORK_ERROR";
                                                Log.w(TAG, "confirmation_failed status=" + status + " code=" + code);
                                            }
                                            if (saved) {
                                                try {
                                                    AvatarCache.save(requireContext(), currentUserId, jpeg);
                                                    android.graphics.Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                                                    showAvatar(bitmap);
                                                } catch (Exception cacheError) {
                                                    Log.w(TAG, "local_cache_update_failed");
                                                }
                                                loadAvatar();
                                            }
                                            finishAvatarUpload(saved);
                                        });
                            });
                } catch (Exception error) {
                    finishAvatarUpload(false);
                }
            });
        } catch (Exception error) {
            finishAvatarUpload(false);
        }
    }

    private void finishAvatarUpload(boolean success) {
        if (!isAdded() || binding == null) return;
        avatarUploading = false;
        binding.avatarProgress.setVisibility(View.GONE);
        Toast.makeText(requireContext(), success ? "Ảnh đại diện đã được cập nhật."
                : "Chưa thể cập nhật ảnh. Hãy thử lại sau.", Toast.LENGTH_SHORT).show();
    }

    private String safeApiError(String body) {
        try {
            String code = new JSONObject(body).optString("error", "UNKNOWN");
            return code.matches("[A-Z0-9_]{1,48}") ? code : "UNKNOWN";
        } catch (Exception ignored) { return "UNKNOWN"; }
    }

    private String safeS3Error(String body) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("<Code>([A-Za-z0-9_]{1,48})</Code>").matcher(body);
        return matcher.find() ? matcher.group(1) : "UPLOAD_REJECTED";
    }

    private void loadAvatar() {
        if (apiClient == null || binding == null) return;
        var current = binding;
        apiClient.get("/me/avatar").addOnCompleteListener(task -> {
            if (binding != current || !isAdded() || !task.isSuccessful() || task.getResult() == null
                    || task.getResult().statusCode != 200) return;
            try {
                String url = new JSONObject(task.getResult().body).optString("url", "");
                if (url.isEmpty() || "null".equals(url)) {
                    current.avatarImage.setVisibility(View.GONE);
                    current.avatarInitials.setVisibility(View.VISIBLE);
                    return;
                }
                apiClient.downloadBytes(url).addOnCompleteListener(imageTask -> {
                    if (binding != current || !isAdded() || !imageTask.isSuccessful()) return;
                    byte[] imageBytes = imageTask.getResult();
                    android.graphics.Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
                    if (bitmap != null) {
                        try { AvatarCache.save(requireContext(), currentUserId, imageBytes); }
                        catch (Exception cacheError) { Log.w(TAG, "local_cache_update_failed"); }
                        showAvatar(bitmap);
                    }
                });
            } catch (Exception ignored) { }
        });
    }

    private void render(AuthState state) {
        if (state == null || state.getUser() == null || binding == null) return;
        String fullName = state.getUser().getFullName();
        currentUserId = state.getUser().getUid();
        String displayName = (fullName == null || fullName.trim().isEmpty())
                ? state.getUser().getEmail() : fullName;

        binding.nameText.setText(displayName);
        binding.emailText.setText(state.getUser().getEmail());

        // Avatar initials
        String initials = "NB";
        if (displayName != null && !displayName.isEmpty()) {
            String[] parts = displayName.trim().split("\\s+");
            if (parts.length >= 2) {
                initials = ("" + parts[0].charAt(0) + parts[parts.length - 1].charAt(0)).toUpperCase();
            } else if (displayName.length() >= 2) {
                initials = displayName.substring(0, 2).toUpperCase();
            }
        }
        binding.avatarInitials.setText(initials);
        android.graphics.Bitmap cachedAvatar = AvatarCache.load(requireContext(), currentUserId);
        if (cachedAvatar == null) {
            binding.avatarImage.setVisibility(View.GONE);
            binding.avatarInitials.setVisibility(View.VISIBLE);
        } else {
            showAvatar(cachedAvatar);
        }
    }

    private void showAvatar(@Nullable android.graphics.Bitmap bitmap) {
        if (binding == null || bitmap == null) return;
        binding.avatarImage.setImageBitmap(bitmap);
        binding.avatarImage.setVisibility(View.VISIBLE);
        binding.avatarInitials.setVisibility(View.GONE);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}

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
import androidx.navigation.fragment.NavHostFragment;

import com.example.final_term.R;
import com.example.final_term.auth.AuthState;
import com.example.final_term.core.FinalTermApplication;
import com.example.final_term.data.AvatarCache;
import com.example.final_term.data.BackendApiClient;
import com.example.final_term.databinding.FragmentProfileBinding;
import com.example.final_term.ui.common.ThemedDialogBuilder;

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
    private boolean isCustomerRole = true;
    private AuthState latestAuthState;
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
                        latestAuthState = authState;
                        boolean customer = authState.getUser().getRole()
                                == com.example.final_term.auth.UserRole.CUSTOMER;
                        isCustomerRole = customer;
                        binding.ekycBadge.setVisibility(customer ? View.VISIBLE : View.GONE);
                        binding.itemPersonalInfo.setVisibility(View.VISIBLE);
                        binding.cardSecuritySection.setVisibility(customer ? View.VISIBLE : View.GONE);
                        binding.itemNotifications.setVisibility(customer ? View.VISIBLE : View.GONE);
                        binding.itemOfficerAudit.setVisibility(customer ? View.GONE : View.VISIBLE);
                        binding.itemOfficerRates.setVisibility(customer ? View.GONE : View.VISIBLE);
                        if (customer) {
                            binding.profileHeaderTitle.setText("Cá nhân");
                            binding.profileSectionAccountTitle.setText("TÀI KHOẢN & THẺ");
                            binding.profilePersonalInfoTitle.setText("Thông tin cá nhân");
                            binding.logoutButton.setText("Đăng xuất");
                            binding.itemPersonalInfo.setOnClickListener(v -> openProfileEditor());
                            binding.itemNotifications.setOnClickListener(v ->
                                    NavHostFragment.findNavController(this).navigate(R.id.notifications));
                            binding.itemLanguage.setOnClickListener(v -> openProfileEditor());
                            binding.ekycBadge.setOnClickListener(openEkycListener);
                            fetchEkycStatus();
                        } else {
                            binding.profileHeaderTitle.setText("Hồ sơ cán bộ");
                            binding.profileSectionAccountTitle.setText("HỒ SƠ CÁN BỘ");
                            binding.profilePersonalInfoTitle.setText("Thông tin công vụ & quyền hạn");
                            binding.customerNoText.setText("Vai trò: Cán bộ ngân hàng");
                            binding.customerTierText.setText(authState.getUser().isActive()
                                    ? "Đang hoạt động" : "Tạm khóa");
                            binding.personalInfoSummary.setText("Xem mã cán bộ, email công vụ và quyền hạn");
                            binding.logoutButton.setText("Đăng xuất");
                            binding.languageStatusText.setText("Tiếng Việt");
                            binding.itemPersonalInfo.setOnClickListener(v -> showOfficerDetailsDialog());
                            binding.itemOfficerAudit.setOnClickListener(v -> {
                                Bundle officerArgs = new Bundle();
                                officerArgs.putString("title", "Kiểm tra giao dịch");
                                officerArgs.putString("subtitle", "Theo dõi lịch sử thao tác của cán bộ.");
                                NavHostFragment.findNavController(this).navigate(R.id.officerAudit, officerArgs);
                            });
                            binding.itemOfficerRates.setOnClickListener(v -> {
                                Bundle officerArgs = new Bundle();
                                officerArgs.putString("title", "Chính sách lãi suất");
                                officerArgs.putString("subtitle", "Quản lý biểu lãi suất hiện hành và ngày hiệu lực.");
                                NavHostFragment.findNavController(this).navigate(R.id.officerRates, officerArgs);
                            });
                            binding.itemLanguage.setOnClickListener(v ->
                                    Toast.makeText(requireContext(), "Ứng dụng đang sử dụng Tiếng Việt.", Toast.LENGTH_SHORT).show());
                            binding.ekycBadge.setOnClickListener(null);
                        }
                        loadProfile();
                    }
                });

        getParentFragmentManager().setFragmentResultListener("ekyc_status_changed", getViewLifecycleOwner(),
                (requestKey, result) -> { fetchEkycStatus(); loadProfile(); });

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

        new ThemedDialogBuilder(requireContext())
                .setTitle("Bảo mật khuôn mặt")
                .setMessage((cameraPermission
                        ? "Máy ảnh đã được cấp quyền. "
                        : "Máy ảnh chưa được cấp quyền. Bạn có thể bật quyền này trong Cài đặt thiết bị. ") +
                        "Ảnh khuôn mặt dùng để đăng ký và đăng nhập nhanh; phương thức này chưa thay thế bước xác minh giấy tờ tùy thân.")
                .setNeutralButtonDestructive("Xóa dữ liệu khuôn mặt", (dialog, which) -> confirmRevokeFaceTemplate())
                .setPositiveButton("Đóng", null)
                .show();
    }

    private void confirmRevokeFaceTemplate() {
        new ThemedDialogBuilder(requireContext())
                .setTitle("Xóa dữ liệu khuôn mặt")
                .setMessage("Thao tác này sẽ tắt đăng nhập bằng khuôn mặt và yêu cầu xác thực lại khi bạn muốn sử dụng tính năng này. Tài khoản của bạn vẫn hoạt động bình thường. Tiếp tục xóa?")
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Xóa ngay", (d, which) -> {
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

    private void showOfficerDetailsDialog() {
        if (!isAdded() || latestAuthState == null || latestAuthState.getUser() == null) return;
        var user = latestAuthState.getUser();
        String fullName = user.getFullName() == null || user.getFullName().trim().isEmpty()
                ? "Cán bộ Nimbus Bank" : user.getFullName().trim();
        String email = user.getEmail() == null ? "—" : user.getEmail().trim();
        String uid = user.getUid() == null ? "—" : user.getUid().trim();
        String shortCode = uid.length() > 8 ? uid.substring(0, 8).toUpperCase() : uid;
        boolean active = user.isActive();

        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_officer_profile_detail, null);
        android.widget.TextView nameTv = dialogView.findViewById(R.id.officer_detail_name);
        android.widget.TextView statusTv = dialogView.findViewById(R.id.officer_detail_status);
        android.widget.TextView codeTv = dialogView.findViewById(R.id.officer_detail_code);
        android.widget.TextView emailTv = dialogView.findViewById(R.id.officer_detail_email);

        nameTv.setText(fullName);
        statusTv.setText(active ? "Đang hoạt động" : "Tạm khóa");
        statusTv.setTextColor(ContextCompat.getColor(requireContext(), active ? R.color.success : R.color.warning));
        codeTv.setText(shortCode);
        emailTv.setText(email);

        new ThemedDialogBuilder(requireContext())
                .setTitle("Thông tin công vụ")
                .setView(dialogView)
                .setPositiveButton("Đóng", null)
                .show();
    }

    private void loadProfile() {
        if (apiClient == null || binding == null) return;
        var current = binding;
        profileLoaded = false;
        if (isCustomerRole) {
            current.personalInfoSummary.setText("Đang tải thông tin…");
        }
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
        NavHostFragment.findNavController(this).navigate(R.id.personalInfo);
    }

    private void renderPreferenceSummaries() {
        if (binding == null) return;
        if (isCustomerRole && profileLoaded && currentProfile != null) {
            String customerNo = currentProfile.optString("customerNo", "").trim();
            if (!customerNo.isEmpty()) binding.customerNoText.setText("Mã KH: " + customerNo);
            String tier = currentProfile.optString("tier", "").trim();
            if (!tier.isEmpty()) {
                binding.customerTierText.setText("PLATINUM".equalsIgnoreCase(tier) ? "Hạng Platinum" : "Hạng " + tier);
            }
        }
        if (!profileLoaded) {
            if (isCustomerRole) {
                binding.personalInfoSummary.setText("Chạm để cập nhật email hoặc số điện thoại");
                binding.notificationsStatusText.setText("—");
                binding.languageStatusText.setText("—");
            }
            return;
        }
        if (isCustomerRole) {
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
        }
        JSONObject preferences = currentProfile.optJSONObject("preferences");
        boolean notificationsEnabled = preferences == null
                || preferences.optBoolean("notificationsEnabled", true);
        String language = preferences == null ? "vi" : preferences.optString("language", "vi");
        binding.notificationsStatusText.setText(notificationsEnabled ? "Bật" : "Tắt");
        binding.languageStatusText.setText("en".equalsIgnoreCase(language) ? "English" : "Tiếng Việt");
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

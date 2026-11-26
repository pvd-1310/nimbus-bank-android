# Thiết kế hệ thống Pop-up theo Theme Frosted-Glass (`ThemedDialogBuilder`)

## Mục tiêu

Thay thế toàn bộ các hộp thoại mặc định của hệ thống (`AlertDialog.Builder`, `MaterialAlertDialogBuilder`, `DatePickerDialog`) bằng một component pop-up dùng chung đồng bộ với theme frosted-glass của ứng dụng (bo góc `24dp`, nền `@drawable/utilities_dialog_surface`, chữ `@color/ink` / `@color/ink_muted`, nút `@color/navy_deep` và `@color/glass_card`).

## Yêu cầu đạt được

- Tạo một builder dùng chung (`ThemedDialogBuilder`) trong `com.example.final_term.ui.common` với API tương thích gần như 1-1 với `MaterialAlertDialogBuilder` (`setTitle`, `setMessage`, `setView`, `setItems`, `setSingleChoiceItems`, `setPositiveButton`, `setNegativeButton`, `setNeutralButton`, `setCancelable`, `create`, `show`).
- Hỗ trợ lấy nút hành động sau khi `show()` (như `getButton(DialogInterface.BUTTON_POSITIVE)`) để không làm vỡ logic validate giữ dialog mở ở `AccountListFragment`.
- Thay thế toàn bộ 26+ điểm gọi `AlertDialog.Builder` / `MaterialAlertDialogBuilder` ở cả màn hình Khách hàng lẫn Nhân viên (Officer).
- Thay thế `DatePickerDialog` mặc định ở `RatePolicyEditorFragment` bằng `MaterialDatePicker` (hoặc style đồng bộ `@color/navy_deep`).

## Phạm vi không thay đổi

- Không thay đổi các dialog đã có layout custom riêng (`OtpBottomSheetDialog`, `ChatbotDialogFragment`, `TransactionSheet`, `WelcomeGuide`, `EkycDialogFragment`, `FaceScanDialogFragment`, `InAppCameraDialogFragment`, `UtilitiesHubDialogFragment` — chỉ thay các `MaterialAlertDialogBuilder` con được mở bên trong chúng).
- Không thay đổi luồng nghiệp vụ hay chức năng hiện tại.
- **Không hiển thị thông số kỹ thuật trong UI:** Mọi nội dung hiển thị trên pop-up (tiêu đề, thông báo, nút bấm) phải dùng ngôn ngữ tự nhiên, dễ hiểu với người dùng cuối; loại bỏ hoặc diễn đạt lại các thông số kỹ thuật, mã lỗi nội bộ, tên biến/trường hệ thống hoặc ID kỹ thuật không có ý nghĩa cho user.

## Chi tiết thiết kế

### 1. Layout & Thành phần dùng chung

- **`res/layout/dialog_themed.xml`**:
  - Container gốc `LinearLayout` dùng `android:background="@drawable/utilities_dialog_surface"` (bo góc `24dp`, nền `#FFF8FAFE`, viền `1dp` `#FFE0E6F0`), padding `20dp`.
  - **Tiêu đề (`dialog_title`)**: `18sp` đậm, `@color/ink`, tự ẩn khi không có tiêu đề.
  - **Nội dung text (`dialog_message`)**: `14sp`, `@color/ink_muted`, `lineSpacingExtra="3dp"`, đặt trong `ScrollView` khi nội dung dài.
  - **Danh sách chọn (`dialog_items_container`)**: render các mục `setItems` / `setSingleChoiceItems` bằng `item_themed_dialog_choice.xml` (có hiệu ứng `selectableItemBackground`, radio indicator cho single-choice, phân cách `1dp` `@color/glass_border_subtle`).
  - **Vùng custom view (`dialog_custom_container`)**: chứa `View` truyền qua `setView(...)`.
  - **Thanh nút hành động**:
    - `dialog_positive_button` (`MaterialButton`, `@color/navy_deep`, bo góc `16dp`, chữ trắng đậm).
    - `dialog_negative_button` (`OutlinedButton`, nền `@color/glass_card`, viền `@color/glass_border`, bo góc `16dp`, chữ `@color/navy`).
    - `dialog_neutral_button` (text/outlined button gọn phía trên hoặc bên trái, hỗ trợ màu `@color/danger` cho hành động mang tính hủy/thu hồi như `"Xóa dữ liệu khuôn mặt"`).
- **`ThemedDialogBuilder.java`**:
  - Tạo `androidx.appcompat.app.AlertDialog` với `window.setBackgroundDrawable(Color.TRANSPARENT)` và `dimAmount = 0.45f`, gắn `dialog_themed.xml` làm content view.
  - Quản lý trạng thái đóng/mở nút tự động (mặc định bấm nút sẽ `dismiss()`, trừ khi caller ghi đè `OnClickListener` trên button sau `show()`).

### 2. Nhóm màn hình áp dụng

1. **Hồ sơ & Bảo mật (`ProfileFragment`)**: `"Bảo mật khuôn mặt"`, `"Xóa dữ liệu khuôn mặt"`.
2. **Trang chủ, Tài khoản, Chuyển tiền, Giao dịch, Chi nhánh (`CustomerHomeFragment`, `AccountListFragment`, `TransferFragment`, `TransactionExecutionFragment`, `TransactionHistoryFragment`, `BranchLocatorFragment`)**: các dialog thông báo, lọc thời gian (`setSingleChoiceItems`), chọn chi nhánh (`setItems`), và form thao tác (`setView`).
3. **eKYC & Camera (`EkycDialogFragment`, `FaceScanDialogFragment`, `InAppCameraDialogFragment`)**: hướng dẫn `btnHelp`, cảnh báo `"Không đọc được CCCD"`, `"Kiểm tra thông tin CCCD"`.
4. **Tiện ích (`UtilitiesHubDialogFragment`)**: các dialog chọn danh mục/nhà cung cấp/hóa đơn (`setItems`, `setView`).
5. **Nhân viên (`OfficerFeatureFragment`, `OfficerCustomerFormFragment`, `RatePolicyEditorFragment`)**: các dialog thông báo, xem ảnh eKYC, và chọn ngày `MaterialDatePicker`.

## Kiểm chứng

- Build `./gradlew :app:assembleDebug -PfirebaseDemo=true` thành công không lỗi.
- Kiểm tra trực tiếp trên simulator (`emulator-5554`): mở `"Bảo mật khuôn mặt"` ở `ProfileFragment`, bộ lọc ở `TransactionHistoryFragment`, chọn chi nhánh ở `BranchLocatorFragment` để xác nhận giao diện bo góc frosted-glass đồng bộ.

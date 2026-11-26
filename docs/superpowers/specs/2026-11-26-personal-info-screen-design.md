# Thiết kế thiết kế lại màn hình Thông tin cá nhân

## Mục tiêu

Thay thế `BottomSheetDialog` chật hẹp (`dialog_profile_contact.xml`) và đoạn `TextView` ghép chuỗi thô thành một màn hình riêng (`PersonalInfoFragment`) được bố cục trực quan, đồng bộ với theme frosted-glass hiện tại của ứng dụng và hiển thị đầy đủ mọi trường thông tin định danh, liên hệ, tùy chọn.

## Yêu cầu đạt được

- Hiển thị toàn bộ các trường thông tin định danh trả về từ `GET /me` (`fullName`, `nationalIdNumber`, `dateOfBirth`, `gender`, `nationality`, `address`, `identityDocumentExpiry`, `customerNo`, `tier`) dưới dạng card và các dòng key-value rõ ràng thay vì một `TextView` ghép chuỗi.
- Giữ nguyên đầy đủ chức năng chỉnh sửa `contactEmail`, `contactPhone`, `preferences.notificationsEnabled` và `preferences.language` qua `PATCH /me`.
- Tuân thủ ngôn ngữ thiết kế frosted-glass hiện tại (`@drawable/background_gradient`, `MaterialCardView` với `@color/glass_card` và `@color/glass_border`, `@drawable/circle_action_button`, `@color/ink`, `@color/ink_muted`, `@color/navy`, `@color/navy_deep`).
- Đồng bộ lại dữ liệu tóm tắt trên `ProfileFragment` khi người dùng lưu thay đổi và quay lại.

## Phạm vi không thay đổi

- Không thay đổi API contract của backend `GET /me`, `PATCH /me` hay schema Firestore.
- Không cho phép chỉnh sửa các trường định danh KYC (`fullName`, `nationalIdNumber`, `dateOfBirth`, `gender`, `nationality`, `address`, `identityDocumentExpiry`) ở phía client; các trường này vẫn ở trạng thái chỉ đọc đúng theo backend (`PROTECTED_FIELD`).

## Phương án tiếp cận

1. **Tạo màn hình riêng `PersonalInfoFragment` qua Navigation Component (được chọn).** Điều hướng từ `ProfileFragment` (`item_personal_info`) tới `R.id.personalInfo` giúp có đủ không gian hiển thị 7 dòng thông tin định danh, địa chỉ dài, form liên hệ và thanh nút lưu cố định ở đáy, nhất quán với `AccountDetailFragment` và `ResetPasswordFragment`.
2. **Chỉ chỉnh sửa lại giao diện `BottomSheetDialog`.** Giữ dạng pop-up nhưng không gian vẫn chật khi chứa 7 dòng thông tin + 2 ô nhập + switch + dropdown + nút hành động, đặc biệt khi bàn phím ảo bật lên.

## Chi tiết thiết kế

### Điều hướng & Thanh tiêu đề

- Thêm `<fragment android:id="@+id/personalInfo" android:name="com.example.final_term.ui.profile.PersonalInfoFragment" android:label="Thông tin cá nhân" />` vào `app/src/main/res/navigation/nav_graph.xml`.
- Cập nhật `MainActivity.syncBottomNavigation(int destination)` để ẩn thanh điều hướng nổi khi `destination == R.id.personalInfo`, giúp thanh nút `Hủy` / `Lưu thay đổi` ở đáy màn hình thoáng và không bị che.
- Thanh tiêu đề trên cùng (`56dp`, theo mẫu `fragment_account_detail.xml`): nút quay lại hình tròn (`@drawable/circle_action_button` + `@drawable/ic_arrow_back`), tiêu đề căn giữa `"Thông tin cá nhân"`, và khoảng trống `48dp` bên phải.

### Bố cục & Phân cấp trực quan (`fragment_personal_info.xml`)

1. **Card Tóm tắt định danh (`MaterialCardView`, `glass_card`, bo góc `24dp`):**
   - Hiển thị `fullName` (`18sp` đậm, `@color/ink`), badge trạng thái eKYC (`@drawable/badge_status_success` `"Đã định danh"` khi có `nationalIdNumber`, hoặc `@drawable/badge_status_neutral` `"Chưa định danh"` khi trống), và các pill `customerNo` / `tier` (`@drawable/glass_pill_bg`).
2. **Card `THÔNG TIN ĐỊNH DANH`:**
   - Tiêu đề mục (`12sp` đậm viết hoa, `@color/ink_muted`, `letterSpacing="0.06"`) kèm ghi chú nhỏ (`"Cố định theo hồ sơ định danh"`).
   - Mỗi trường thông tin được đặt trong **một khung viền (border) riêng biệt** (bo góc `16dp`, nền `@color/glass_card`, viền `1dp` `@color/glass_border` hoặc style `OutlinedBox` chỉ đọc đồng bộ với phần liên hệ), trong đó **phần phía trên khung viền hiển thị nhãn tên trường** (ví dụ: `"Họ và tên"`, `"Số định danh (CCCD)"`, `"Ngày sinh"`, `"Giới tính"`, `"Quốc tịch"`, `"Ngày hết hạn CCCD"`, `"Địa chỉ"`) và **bên trong khung viền hiển thị giá trị tương ứng**:
     - `Họ và tên` (`fullName`)
     - `Số định danh (CCCD)` (`nationalIdNumber`) kèm nút sao chép nhanh (`@drawable/ic_copy`) ở góc phải trong khung
     - `Ngày sinh` (`dateOfBirth`, định dạng `dd/MM/yyyy` nếu là ISO `YYYY-MM-DD`)
     - `Giới tính` (`gender`)
     - `Quốc tịch` (`nationality`)
     - `Ngày hết hạn CCCD` (`identityDocumentExpiry`, định dạng `dd/MM/yyyy` nếu là ISO `YYYY-MM-DD`)
     - `Địa chỉ` (`address`, khung tự giãn chiều cao hỗ trợ xuống dòng gọn gàng với địa chỉ dài)
   - Các trường chưa có dữ liệu hiển thị `"Chưa cập nhật"` với màu `@color/ink_faint` bên trong khung viền để luôn đảm bảo hiển thị đầy đủ thông tin.
3. **Card `THÔNG TIN LIÊN HỆ`:**
   - `contact_email_layout` / `contact_email_input` (`Widget.Material3.TextInputLayout.OutlinedBox`, bo góc `16dp`, nền `@color/glass_card`, viền `@color/navy`).
   - `contact_phone_layout` / `contact_phone_input` với dòng gợi ý `"Nhập theo mã quốc gia, ví dụ +84901234567"`.
4. **Card `TÙY CHỌN`:**
   - `notifications_switch` (`MaterialSwitch`) cho `"Thông báo biến động"`.
   - `language_input` (`ExposedDropdownMenu`) cho `"Ngôn ngữ"` (`Tiếng Việt`, `English`).
5. **Thanh hành động cố định ở đáy:**
   - `saving_progress` (thanh tiến trình ngang indeterminate), `form_status_text` (`accessibilityLiveRegion="polite"`), và cặp nút ngang: `cancel_button` (`"Hủy"`) và `save_button` (`"Lưu thay đổi"`, `@color/navy_deep`).

### Luồng dữ liệu & Vòng đời

- `PersonalInfoFragment` gọi `GET /me` qua `ApiClient` khi khởi tạo view và điền dữ liệu vào toàn bộ các dòng định danh, ô nhập liên hệ và tùy chọn.
- Khi nhấn lưu (`PATCH /me`), kiểm tra định dạng email (`Patterns.EMAIL_ADDRESS`) và số điện thoại (`\\+[1-9][0-9]{7,14}`), ẩn bàn phím, khóa các nút trong lúc gửi và hiển thị lỗi chi tiết từ backend (`CONTACT_PHONE_EXISTS`, `CONTACT_EMAIL_EXISTS`) ngay trên `TextInputLayout` tương ứng.
- Khi lưu thành công, hiển thị thông báo toast và gọi `NavHostFragment.findNavController(this).popBackStack()`. `ProfileFragment` tải lại tóm tắt thông tin khi quay về màn hình trước.
- Xóa `dialog_profile_contact.xml` và hàm `ProfileFragment.showContactEditor()`.

## Kiểm chứng

- Build ứng dụng Android (`./gradlew :app:assembleDebug`) để kiểm tra ViewBinding, `nav_graph.xml` và tài nguyên XML biên dịch sạch sẽ.
- Kiểm tra giao diện trên máy ảo/thiết bị cho cả trường hợp hồ sơ đầy đủ thông tin eKYC và hồ sơ thiếu các trường tùy chọn (`dateOfBirth`, `gender`, `nationality`, `identityDocumentExpiry`).

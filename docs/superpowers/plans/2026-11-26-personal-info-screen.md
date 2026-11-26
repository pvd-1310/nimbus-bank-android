# Kế hoạch triển khai: Thiết kế lại màn hình Thông tin cá nhân

> **Dành cho agent thực thi:** SỬ DỤNG SUB-SKILL `superpowers:executing-plans` để triển khai kế hoạch này theo từng task. Đánh dấu tiến độ bằng cú pháp checkbox (`- [ ]`).

**Mục tiêu:** Thay thế pop-up `BottomSheetDialog` chỉnh sửa thông tin cá nhân bằng một màn hình riêng (`PersonalInfoFragment`) theo đúng theme frosted-glass hiện tại, hiển thị trực quan và đầy đủ toàn bộ thông tin định danh, liên hệ và tùy chọn tài khoản.

**Kiến trúc:** Thêm destination mới trong Navigation Component (`R.id.personalInfo` -> `PersonalInfoFragment`) sử dụng layout `fragment_personal_info.xml`. Giữ nguyên API `GET /me`, `PATCH /me` và logic validate hiện có, đồng thời dọn bỏ layout pop-up cũ `dialog_profile_contact.xml`.

**Công nghệ:** Android Java/XML, ViewBinding, AndroidX Navigation, Material Components 3.

**Spec tham chiếu:** `docs/superpowers/specs/2026-11-26-personal-info-screen-design.md`

## Ràng buộc chung

- Không thay đổi route backend `/me`, quy tắc validate phía server hay schema Firestore.
- Tất cả các trường định danh (`fullName`, `nationalIdNumber`, `dateOfBirth`, `gender`, `nationality`, `address`, `identityDocumentExpiry`) là chỉ đọc và luôn hiển thị rõ ràng (hiển thị `"Chưa cập nhật"` nếu dữ liệu trống).
- Mọi callback bất đồng bộ của `ApiClient` phải kiểm tra `isAdded()` và `binding != null` để tránh crash khi view đã bị hủy.
- Sử dụng đúng các token màu và drawable của theme hiện tại (`@drawable/background_gradient`, `@color/glass_card`, `@color/glass_border`, `@color/ink`, `@color/ink_muted`, `@color/navy`, `@color/navy_deep`).

---

### Task 1: Tạo layout `fragment_personal_info.xml`

**Tệp liên quan:**
- Tạo mới: `app/src/main/res/layout/fragment_personal_info.xml`
- Tham khảo: `app/src/main/res/layout/fragment_account_detail.xml`
- Tham khảo: `app/src/main/res/layout/fragment_profile.xml`

**Giao diện / ViewBinding đầu ra:**
- Sinh ra `FragmentPersonalInfoBinding` gồm: thanh tiêu đề (`back_button`), card tóm tắt (`header_name_text`, `header_ekyc_badge`, `header_customer_no_text`, `header_tier_text`), các dòng định danh (`row_full_name`, `row_national_id`, `copy_national_id_button`, `row_dob`, `row_gender`, `row_nationality`, `row_id_expiry`, `row_address`), ô nhập liên hệ (`contact_email_layout`, `contact_email_input`, `contact_phone_layout`, `contact_phone_input`), tùy chọn (`notifications_switch`, `language_input`), trạng thái (`saving_progress`, `form_status_text`), và thanh nút đáy (`cancel_button`, `save_button`).

- [ ] **Bước 1: Tạo container gốc và thanh tiêu đề trên cùng** sử dụng nền `@drawable/background_gradient`, header cao `56dp` gồm `back_button` (`@drawable/circle_action_button` + `@drawable/ic_arrow_back`), tiêu đề `"Thông tin cá nhân"` căn giữa và spacer `48dp` bên phải.
- [ ] **Bước 2: Tạo Card Tóm tắt định danh** (`MaterialCardView`, `@color/glass_card`, bo góc `24dp`, viền `1dp` `@color/glass_border`) hiển thị họ tên, badge trạng thái định danh, pill mã khách hàng và pill hạng thành viên.
- [ ] **Bước 3: Tạo Card `THÔNG TIN ĐỊNH DANH`** với tiêu đề mục (`12sp` đậm viết hoa, `@color/ink_muted`, `letterSpacing="0.06"`) kèm ghi chú nhỏ (`"Cố định theo hồ sơ định danh"`). Bên trong hiển thị 7 trường (`Họ và tên`, `Số định danh (CCCD)` kèm nút sao chép, `Ngày sinh`, `Giới tính`, `Quốc tịch`, `Ngày hết hạn CCCD`, `Địa chỉ`), mỗi trường nằm trong một khung viền (border) riêng (`16dp` radius, nền `@color/glass_card`, viền `1dp`) với nhãn tên trường hiển thị phía trên khung viền và giá trị nằm gọn bên trong khung.
- [ ] **Bước 4: Tạo Card `THÔNG TIN LIÊN HỆ` và Card `TÙY CHỌN`** tái sử dụng style `OutlinedBox` (`16dp` radius, viền `@color/navy`), `MaterialSwitch`, và `ExposedDropdownMenu`.
- [ ] **Bước 5: Tạo thanh hành động cố định ở đáy** chứa `saving_progress`, `form_status_text`, nút `cancel_button` (`Hủy`) và `save_button` (`Lưu thay đổi`).

---

### Task 2: Triển khai `PersonalInfoFragment.java`

**Tệp liên quan:**
- Tạo mới: `app/src/main/java/com/example/final_term/ui/profile/PersonalInfoFragment.java`
- Tham khảo: `app/src/main/java/com/example/final_term/ui/profile/ProfileFragment.java`

**Tích hợp:**
- Sử dụng: `ApiClient` (`GET /me` và `PATCH /me`) từ `FinalTermApplication`.
- Đầu ra: Màn hình xem & chỉnh sửa thông tin cá nhân hoàn chỉnh, có định dạng ngày (`YYYY-MM-DD` -> `dd/MM/yyyy`), sao chép số CCCD vào clipboard, validate email/SĐT, ánh xạ lỗi backend (`CONTACT_PHONE_EXISTS`, `CONTACT_EMAIL_EXISTS`), và quay lại màn hình trước khi lưu xong hoặc hủy.

- [ ] **Bước 1: Triển khai `onCreateView`, `onViewCreated`, và `onDestroyView`** với `FragmentPersonalInfoBinding`, khởi tạo danh sách ngôn ngữ (`Tiếng Việt`, `English`), gắn sự kiện nút quay lại/hủy và nút sao chép số CCCD.
- [ ] **Bước 2: Triển khai `loadProfile()` (`GET /me`)** và `bindProfile(JSONObject profile)` để đổ dữ liệu vào card tóm tắt, toàn bộ 7 dòng định danh (hiển thị `"Chưa cập nhật"` màu `@color/ink_faint` nếu trống), thông tin liên hệ và tùy chọn.
- [ ] **Bước 3: Triển khai `saveProfile()` (`PATCH /me`)** giữ nguyên validate email (`Patterns.EMAIL_ADDRESS`) và SĐT (`\\+[1-9][0-9]{7,14}`), ẩn bàn phím, vô hiệu hóa các nút + hiện progress khi đang lưu, và hiển thị lỗi `CONTACT_PHONE_EXISTS` / `CONTACT_EMAIL_EXISTS` trực tiếp trên ô nhập tương ứng.
- [ ] **Bước 4: Điều hướng quay lại khi lưu thành công** bằng `NavHostFragment.findNavController(this).popBackStack()` sau khi hiện toast `"Thông tin cá nhân đã được cập nhật."`.

---

### Task 3: Kết nối điều hướng, cập nhật `MainActivity` & `ProfileFragment`, dọn dẹp pop-up cũ

**Tệp liên quan:**
- Chỉnh sửa: `app/src/main/res/navigation/nav_graph.xml`
- Chỉnh sửa: `app/src/main/java/com/example/final_term/MainActivity.java`
- Chỉnh sửa: `app/src/main/java/com/example/final_term/ui/profile/ProfileFragment.java`
- Xóa: `app/src/main/res/layout/dialog_profile_contact.xml`

**Tích hợp:**
- Sử dụng: destination `R.id.personalInfo` trong `nav_graph.xml`.
- Đầu ra: Nhấn vào mục `item_personal_info` ở `ProfileFragment` sẽ mở `PersonalInfoFragment` toàn màn hình và ẩn thanh bottom navigation.

- [ ] **Bước 1: Khai báo `R.id.personalInfo`** (`com.example.final_term.ui.profile.PersonalInfoFragment`) trong `nav_graph.xml`.
- [ ] **Bước 2: Cập nhật `MainActivity.syncBottomNavigation`** để ẩn thanh bottom navigation khi ở `R.id.personalInfo`.
- [ ] **Bước 3: Cập nhật `ProfileFragment.openProfileEditor()`** chuyển sang điều hướng `NavHostFragment.findNavController(this).navigate(R.id.personalInfo)` và đảm bảo `ProfileFragment` làm mới lại dữ liệu tóm tắt khi quay về (`loadProfile()`).
- [ ] **Bước 4: Xóa `showContactEditor()`** và import `DialogProfileContactBinding` không còn dùng trong `ProfileFragment.java`, đồng thời xóa file `app/src/main/res/layout/dialog_profile_contact.xml`.

---

### Task 4: Build & Kiểm tra

**Tệp liên quan:**
- Kiểm tra: `./gradlew :app:assembleDebug`

- [ ] **Bước 1: Chạy `./gradlew :app:assembleDebug`** để xác nhận XML layout, ViewBinding, `nav_graph.xml` và mã Java biên dịch không lỗi.
- [ ] **Bước 2: Kiểm tra thực tế trên máy ảo/thiết bị**: vào Cá nhân -> Thông tin cá nhân, kiểm tra 7 trường định danh hiển thị trực quan đúng theme frosted-glass, thử chỉnh sửa email/SĐT/tùy chọn, lưu và xác nhận màn hình Cá nhân cập nhật đúng.

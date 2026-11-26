# Kế hoạch triển khai: Thay thế toàn bộ Pop-up mặc định theo Theme của App

> **Dành cho agent thực thi:** SỬ DỤNG SUB-SKILL `superpowers:executing-plans` để triển khai kế hoạch này theo từng task. Đánh dấu tiến độ bằng cú pháp checkbox (`- [ ]`).

**Mục tiêu:** Thay thế toàn bộ các `AlertDialog.Builder`, `MaterialAlertDialogBuilder` và `DatePickerDialog` mặc định hệ thống bằng một component pop-up thống nhất theo theme frosted-glass (`ThemedDialogBuilder`).

**Kiến trúc:** Xây dựng `ThemedDialogBuilder` + `dialog_themed.xml` trong `ui/common` dùng `@drawable/utilities_dialog_surface`, nút `@color/navy_deep` và `@color/glass_card`, sau đó thay thế lần lượt theo từng nhóm tính năng để dễ kiểm soát và không làm sai lệch hành vi cũ.

**Công nghệ:** Android Java/XML, Material Components 3 (`MaterialButton`, `MaterialDatePicker`), ViewBinding.

**Spec tham chiếu:** `docs/superpowers/specs/2026-11-26-themed-dialogs-design.md`

## Ràng buộc chung

- Giữ nguyên toàn bộ luồng nghiệp vụ, callback và logic validate hiện có ở từng màn hình.
- **Không hiển thị thông số kỹ thuật cho user:** Trong quá trình thay thế từng pop-up, rà soát nội dung text để đảm bảo không hiển thị mã lỗi kỹ thuật, tên biến/hệ thống hoặc thông số kỹ thuật nội bộ; chuyển sang câu chữ thân thiện, dễ hiểu với người dùng cuối.
- Không làm vỡ hành vi giữ dialog mở khi validate lỗi ở `AccountListFragment` (dòng 1057, 1205).
- Đảm bảo mọi dialog đều có `window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT))` để hiển thị đúng bo góc `24dp` của `@drawable/utilities_dialog_surface`.

---

### Task 1: Xây dựng component dùng chung `ThemedDialogBuilder`

**Tệp liên quan:**
- Tạo mới: `app/src/main/res/layout/dialog_themed.xml`
- Tạo mới: `app/src/main/res/layout/item_themed_dialog_choice.xml`
- Tạo mới: `app/src/main/java/com/example/final_term/ui/common/ThemedDialogBuilder.java`
- Tham khảo: `app/src/main/res/drawable/utilities_dialog_surface.xml`

- [ ] **Bước 1: Tạo `dialog_themed.xml`** với khung `@drawable/utilities_dialog_surface`, `dialog_title`, `ScrollView` chứa `dialog_message`, `dialog_items_container`, `dialog_custom_container`, và cụm nút `dialog_neutral_button`, `dialog_negative_button`, `dialog_positive_button`.
- [ ] **Bước 2: Tạo `item_themed_dialog_choice.xml`** cho từng dòng trong `setItems` / `setSingleChoiceItems` (padding `14dp`, chữ `@color/ink` `15sp`, icon check/radio khi là single-choice).
- [ ] **Bước 3: Triển khai `ThemedDialogBuilder.java`** hỗ trợ đầy đủ fluent API (`setTitle`, `setMessage`, `setView`, `setItems`, `setSingleChoiceItems`, `setPositiveButton`, `setNegativeButton`, `setNeutralButton`, `setNeutralButtonDestructive`, `setCancelable`, `create`, `show`).

---

### Task 2: Thay thế pop-up ở Hồ sơ, eKYC & Camera

**Tệp liên quan:**
- Chỉnh sửa: `app/src/main/java/com/example/final_term/ui/profile/ProfileFragment.java` (dòng 153, 165)
- Chỉnh sửa: `app/src/main/java/com/example/final_term/ui/ekyc/EkycDialogFragment.java` (dòng 119, 248, 311)
- Chỉnh sửa: `app/src/main/java/com/example/final_term/ui/ekyc/FaceScanDialogFragment.java` (dòng 176)
- Chỉnh sửa: `app/src/main/java/com/example/final_term/ui/ekyc/InAppCameraDialogFragment.java` (dòng 86)

- [ ] **Bước 1: Cập nhật `ProfileFragment.java`** thay 2 `AlertDialog.Builder` (`"Bảo mật khuôn mặt"` và `"Xóa dữ liệu khuôn mặt"` — dùng `setNeutralButtonDestructive` hoặc nút cảnh báo `@color/danger`).
- [ ] **Bước 2: Cập nhật `EkycDialogFragment.java`**, `FaceScanDialogFragment.java`, `InAppCameraDialogFragment.java` sang `ThemedDialogBuilder` (bao gồm dialog `setView(scroll)` ở dòng 311).

---

### Task 3: Thay thế pop-up ở các màn hình Khách hàng & Tiện ích

**Tệp liên quan:**
- Chỉnh sửa: `app/src/main/java/com/example/final_term/ui/customer/CustomerHomeFragment.java` (dòng 135)
- Chỉnh sửa: `app/src/main/java/com/example/final_term/ui/customer/TransferFragment.java` (dòng 208)
- Chỉnh sửa: `app/src/main/java/com/example/final_term/ui/transaction/TransactionExecutionFragment.java` (dòng 1205)
- Chỉnh sửa: `app/src/main/java/com/example/final_term/ui/customer/TransactionHistoryFragment.java` (dòng 75 — `setSingleChoiceItems`)
- Chỉnh sửa: `app/src/main/java/com/example/final_term/ui/customer/BranchLocatorFragment.java` (dòng 92 — `setItems`)
- Chỉnh sửa: `app/src/main/java/com/example/final_term/ui/utilities/UtilitiesHubDialogFragment.java` (dòng 579, 624, 750, 859)
- Chỉnh sửa: `app/src/main/java/com/example/final_term/ui/customer/AccountListFragment.java` (dòng 556, 1029, 1104, 1177, 1244, 1285, 1331, 1342)

- [ ] **Bước 1: Thay thế các dialog thông báo/chọn danh sách** ở `CustomerHomeFragment`, `TransferFragment`, `TransactionExecutionFragment`, `TransactionHistoryFragment`, `BranchLocatorFragment`.
- [ ] **Bước 2: Thay thế các dialog chọn/danh sách** ở `UtilitiesHubDialogFragment`.
- [ ] **Bước 3: Thay thế toàn bộ 8 dialog** ở `AccountListFragment`, giữ nguyên cơ chế custom click listener trên `BUTTON_POSITIVE` ở dòng 1057 và 1205.

---

### Task 4: Thay thế pop-up ở màn hình Nhân viên (Officer) & Bộ chọn ngày

**Tệp liên quan:**
- Chỉnh sửa: `app/src/main/java/com/example/final_term/ui/officer/OfficerFeatureFragment.java` (dòng 341, 383, 421)
- Chỉnh sửa: `app/src/main/java/com/example/final_term/ui/officer/OfficerCustomerFormFragment.java` (dòng 240)
- Chỉnh sửa: `app/src/main/java/com/example/final_term/ui/officer/RatePolicyEditorFragment.java` (dòng 135 — `DatePickerDialog`)

- [ ] **Bước 1: Thay thế các `AlertDialog.Builder` / `MaterialAlertDialogBuilder`** ở `OfficerFeatureFragment` (kể cả dialog xem ảnh `setView(iv)`) và `OfficerCustomerFormFragment`.
- [ ] **Bước 2: Thay thế `DatePickerDialog`** trong `RatePolicyEditorFragment` bằng `MaterialDatePicker.Builder.datePicker()` đồng bộ với Material 3 theme của app.

---

### Task 5: Build & Kiểm tra trên Simulator

**Kiểm tra:**
- Chạy `./gradlew :app:assembleDebug -PfirebaseDemo=true`
- Cài lên `emulator-5554` (`com.example.final_term.localreview`) và kiểm tra trực tiếp pop-up `"Bảo mật khuôn mặt"` ở `ProfileFragment` cùng các dialog danh sách/xác nhận khác.

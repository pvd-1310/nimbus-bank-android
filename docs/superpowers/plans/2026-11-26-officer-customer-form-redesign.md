# Plan: Redesign UI Cấp mới tài khoản & Điều chỉnh thông tin + Mở rộng Backend Schema

## Mục tiêu

1. **Redesign giao diện** hai chế độ của `OfficerCustomerFormFragment` (**Cấp mới tài khoản** và **Điều chỉnh thông tin**) theo đúng ngôn ngữ thiết kế frosted-glass của màn hình **Thông tin cá nhân** (`PersonalInfoFragment`):
   - Phân nhóm rõ ràng theo `MaterialCardView` (`glass_card`, bo góc `24dp`, viền `glass_border`).
   - Sử dụng `TextInputLayout.OutlinedBox` / `ExposedDropdownMenu` (không bị cắt chữ hint như `EditText` cũ) và `MaterialDatePicker` cho các trường ngày tháng (`dd/MM/yyyy`).
   - Thanh hành động ở đáy nằm trên **layer nổi riêng biệt** (`bg_floating_action_bar`, `elevation="18dp"`) phía trên `ScrollView`.
2. **Đồng bộ đầy đủ trường thông tin** với màn hình **Chi tiết thông tin tài khoản** (`PersonalInfoFragment`):
   - **Thông tin định danh**: `fullName` (Họ và tên), `nationalIdNumber` (Số định danh CCCD), `dateOfBirth` (Ngày sinh), `gender` (Giới tính), `nationality` (Quốc tịch), `identityDocumentExpiry` (Ngày hết hạn CCCD), `address` (Địa chỉ), `customerNo` (Mã khách hàng).
   - **Thông tin liên hệ**: `email` / `contactEmail` (Email đăng nhập & liên hệ), `phoneNumber` / `contactPhone` (Số điện thoại).
   - **Tùy chọn**: `preferences.notificationsEnabled` (Thông báo biến động), `preferences.language` (Ngôn ngữ).
3. **Cập nhật Backend Schema & API** (`POST /officer/customers`, `GET /officer/customers/:id`, `PATCH /officer/customers/:id`) để lưu trữ, xác thực, trả về và ghi audit đầy đủ các trường trên.

---

## Phần 1: Backend Schema & API (`backend/src/`)

### 1.1. Schema & Validation (`backend/src/modules/officer/officer.routes.ts`, `backend/src/database/template-schema.ts`)
- **`POST /officer/customers`** (cấp mới hồ sơ + tài khoản thanh toán):
  - Mở rộng Zod schema (vẫn giữ `.strict()`):
    - Giữ nguyên: `requestId`, `email`, `fullName`, `phoneNumber`, `nationalIdNumber`, `customerNo?`, `address?`.
    - Bổ sung:
      - `dateOfBirth`: `z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional()` (hoặc bắt buộc nếu UI yêu cầu — khuyến nghị `.optional()` ở API để tương thích test cũ, UI bắt buộc/validate khi nhập).
      - `gender`: `z.enum(["Nam", "Nữ", "Khác"]).optional()` (hoặc `z.string().trim().min(1).max(30).optional()`).
      - `nationality`: `z.string().trim().min(1).max(80).optional()`.
      - `identityDocumentExpiry`: `z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional()`.
      - `contactEmail`: `z.email().max(254).optional()` (mặc định fallback về `email` nếu không truyền riêng).
      - `preferences`: `z.object({ notificationsEnabled: z.boolean().optional(), language: z.enum(["vi", "en"]).optional() }).optional()`.
- **`customer-provisioning.service.ts`**:
  - Khi ghi `users/{uid}`: lưu thêm `contactEmail: input.contactEmail ?? input.email` và `preferences: { notificationsEnabled: input.preferences?.notificationsEnabled ?? true, language: input.preferences?.language ?? "vi" }`.
  - Khi ghi `customers/{uid}`: lưu đầy đủ `dateOfBirth: input.dateOfBirth ?? null`, `gender: input.gender ?? null`, `nationality: input.nationality ?? null`, `identityDocumentExpiry: input.identityDocumentExpiry ?? null`, `address: input.address ?? null`.
- **`GET /officer/customers/:id`** (`backend/src/modules/officer/officer-accounts.service.ts`):
  - Trả về đầy đủ các trường để màn hình **Điều chỉnh thông tin** hiển thị:
    - `fullName`, `customerNo`, `nationalIdNumber`, `dateOfBirth`, `gender`, `nationality`, `identityDocumentExpiry`, `address`, `contactEmail`, `contactPhone`, `preferences`.
- **`PATCH /officer/customers/:id`** (`backend/src/modules/officer/officer.routes.ts`):
  - Mở rộng Zod schema cho phép Officer điều chỉnh thêm: `dateOfBirth`, `gender`, `nationality`, `identityDocumentExpiry`, `preferences` (cùng `fullName`, `contactEmail`, `contactPhone`, `address`, `reason`).
  - Phân luồng ghi chính xác:
    - `users/{uid}`: `fullName`, `contactEmail`, `contactPhone`, `preferences` (kèm `phoneMasked`, `contactPhoneSearchTokens` nếu đổi SĐT).
    - `customers/{uid}`: `address`, `dateOfBirth`, `gender`, `nationality`, `identityDocumentExpiry`.
  - Ghi nhận đầy đủ `before` / `after` vào `customerChanges` và masked audit log vào `auditLogs`.
- **`backend/src/database/template-schema.ts` & `backend/src/docs/openapi.ts`**:
  - Cập nhật `openapi.ts` (nếu có mô tả endpoint `/officer/customers`) và kiểm tra `template-schema.ts` (`optionalCustomerIdentityFields` đã có `dateOfBirth`, `gender`, `nationality`, `identityDocumentExpiry`; bổ sung `contactEmail`, `preferences` nếu cần trong mô tả schema).

---

## Phần 2: Redesign UI & Form (`app/src/main/`)

### 2.1. Layout `fragment_officer_customer_form.xml`
Chuyển từ layout `EditText` đơn lẻ sang cấu trúc 2 layer với **Material 3 Frosted-Glass Cards**:
1. **Top Bar (`56dp`)**:
   - Nút quay lại tròn `back_button` (`@drawable/circle_action_button` + `@drawable/ic_arrow_back`), tiêu đề căn giữa + subtitle nhỏ bên dưới (`Cấp mới tài khoản` / `Điều chỉnh thông tin`).
2. **Layer 1 — `ScrollView`** (`clipToPadding="false"`, `paddingBottom="116dp"`):
   - **Card Ngữ cảnh tài khoản (`account_context_card`)** *(chỉ hiện khi `editing = true`)*: Card màu `@color/navy` hiển thị `TÀI KHOẢN THANH TOÁN` + `account_number_text` + pill trạng thái/mã KH.
   - **Card 1: `THÔNG TIN ĐỊNH DANH`**:
     - `Họ và tên` (`name_layout` / `name_input`)
     - `Số định danh (CCCD)` (`national_id_layout` / `national_id_input`, 12 chữ số; ở mode edit có thể hiển thị chỉ đọc hoặc cho xem để đối chiếu)
     - Hàng 2 cột: `Ngày sinh` (`dob_layout` / `dob_input`, click mở DatePicker `dd/MM/yyyy`) & `Giới tính` (`gender_layout` / `gender_input`, `ExposedDropdownMenu`: `Nam`, `Nữ`, `Khác`)
     - Hàng 2 cột: `Quốc tịch` (`nationality_layout` / `nationality_input`, mặc định `Việt Nam`) & `Ngày hết hạn CCCD` (`id_expiry_layout` / `id_expiry_input`, click mở DatePicker `dd/MM/yyyy`)
     - `Địa chỉ` (`address_layout` / `address_input`, multi-line)
     - `Mã khách hàng (tùy chọn)` (`customer_no_layout` / `customer_no_input`, hiện ở mode tạo mới)
   - **Card 2: `THÔNG TIN LIÊN HỆ`**:
     - `Email đăng nhập / liên hệ` (`email_layout` / `email_input`)
     - `Số điện thoại liên hệ` (`phone_layout` / `phone_input`, kèm `helperText`: `0xxxxxxxxx` khi tạo mới, `+84xxxxxxxxx` khi điều chỉnh)
   - **Card 3: `TÙY CHỌN`**:
     - `Thông báo biến động` (`notifications_switch`, `MaterialSwitch`)
     - `Ngôn ngữ` (`language_layout` / `language_input`, `ExposedDropdownMenu`: `Tiếng Việt`, `English`)
   - **Card 4: `LÝ DO ĐIỀU CHỈNH`** *(chỉ hiện khi `editing = true`)*:
     - `Lý do điều chỉnh (bắt buộc)` (`reason_layout` / `reason_input`)
     - Ghi chú audit `"Mọi thay đổi sẽ được lưu vào lịch sử thao tác của ngân hàng."`
3. **Layer 2 — Floating Bottom Action Bar** (`bg_floating_action_bar`, `elevation="18dp"`, `layout_gravity="bottom"`):
   - Thanh tiến trình `saving_progress` + `form_status_text` (nếu có lỗi).
   - Cặp nút ngang: `cancel_button` (**Hủy**) + `submit_button` (**Cấp mới hồ sơ** / **Lưu điều chỉnh**).

### 2.2. Logic `OfficerCustomerFormFragment.java`
- Hỗ trợ chuyển đổi ngày giữa hiển thị `dd/MM/yyyy` và API ISO `YYYY-MM-DD`.
- Tích hợp `MaterialDatePicker` cho `dob_input` và `id_expiry_input` (người dùng chạm là chọn ngày, không gõ sai định dạng).
- Thiết lập dropdown `gender_input` (`Nam`, `Nữ`, `Khác`) và `language_input` (`Tiếng Việt`, `English`).
- **Mode `create`**: Validate đầy đủ `fullName`, `nationalIdNumber` (12 số), `dateOfBirth` (nếu nhập phải đúng định dạng), `identityDocumentExpiry`, `email`, `phoneNumber` (`0[0-9]{9}`), đóng gói JSON gửi `POST /officer/customers`.
- **Mode `edit`**: Nạp toàn bộ dữ liệu từ `GET /officer/customers/{id}`, so sánh diff từng trường (bao gồm cả thông tin định danh mới, liên hệ và `preferences`), chỉ gửi các trường thay đổi kèm `reason` tới `PATCH /officer/customers/{id}`.

---

## Phần 3: Kiểm thử & Xác minh

1. **Backend tests**:
   - Cập nhật/bổ sung test trong `backend/test/officer-accounts.test.ts` (và kiểm tra `schema.test.ts`) cho payload tạo mới & cập nhật đầy đủ các trường định danh + `contactEmail` + `preferences`.
   - Chạy test suite backend (`npm test` trong `backend/`).
2. **Android build**:
   - Chạy `./gradlew :app:assembleDebug` để xác nhận ViewBinding, resources XML và Java compile sạch sẽ.

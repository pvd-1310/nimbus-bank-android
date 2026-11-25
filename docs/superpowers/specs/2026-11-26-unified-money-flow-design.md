# Chuẩn hóa luồng giao dịch tiền 3 bước theo thiết kế HTML

## 1. Mục tiêu & Phạm vi

Chuẩn hóa toàn bộ các luồng giao dịch liên quan đến tiền trong ứng dụng Android Nimbus Bank theo cùng một trải nghiệm tuyến tính **3 bước** thống nhất, có giao diện bám sát 3 mẫu HTML:

1. **Bước 1 — Xác nhận giao dịch**: `D:\Khoa 15\TON DUC THANG\Mobile\Final\html\Transfer_Confirmation.html`
2. **Bước 2 — Xác thực OTP (Bottom Sheet)**: `D:\Khoa 15\TON DUC THANG\Mobile\Final\html\OTP_BottomSheet.html`
3. **Bước 3 — Giao dịch thành công (Biên lai)**: `D:\Khoa 15\TON DUC THANG\Mobile\Final\html\Success_Screen.html`

**Các luồng nghiệp vụ nằm trong phạm vi:**
- Chuyển tiền cùng ngân hàng (`INTERNAL_TRANSFER`)
- Chuyển tiền liên ngân hàng (`EXTERNAL_TRANSFER`)
- Nạp tiền vào tài khoản (`DEPOSIT` / Stripe Top-up)
- Rút tiền từ tài khoản (`WITHDRAWAL`)
- Thanh toán hóa đơn điện, nước, dịch vụ khác (`BILL_PAYMENT`)
- Thanh toán đơn hàng tiện ích: Nạp điện thoại (`TOPUP`), Vé máy bay (`FLIGHT`), Vé xem phim (`MOVIE`), Khách sạn (`HOTEL`), Mua sắm (`ECOMMERCE`) (`ORDER_PAYMENT`)

**Ngoài phạm vi:**
- Không thay đổi API contract hay schema Firestore phía backend (trừ việc đọc/hiển thị lại đúng dữ liệu backend hiện có).
- Không tích hợp thư viện `androidx.biometric` mới; không xác thực SMS OTP thật (giữ nguyên cơ chế `DEMO_DISPLAY_CODE` với auto-fill).

---

## 2. Kiến trúc tổng thể (Phương án A — Destination 3 bước dùng chung)

### 2.1. Tách biệt Bước Nhập liệu (Step 0) và Luồng Thực thi 3 bước (`transactionFlow`)

- Các màn hình chuyên biệt chỉ chịu trách nhiệm **Bước 0 (Nhập / Chọn thông tin giao dịch)**:
  - `TransferFragment`: Chọn loại chuyển khoản (Cùng NH / Ngân hàng khác), tài khoản nguồn, ngân hàng nhận (nếu liên NH), SĐT hoặc số TK nhận, số tiền + quick chips, nội dung. Nếu là chuyển nội bộ, gọi `POST /transfers/recipient` để lấy `recipientAccountId` và `recipientName` trước khi chuyển tiếp.
  - `MoneyRequestFragment`: Cho phép chọn **Tài khoản thanh toán** và nhập **Số tiền** cho **cả 2 tab Nạp tiền (`DEPOSIT`) và Rút tiền (`WITHDRAWAL`)**, bỏ cơ chế cũ ẩn form nạp để nhảy vào Step 1 của `TransferFragment`.
  - `BillPaymentFragment`: Tra cứu/chọn hóa đơn chưa thanh toán.
  - `MobileTopupFragment`: Nhập SĐT, chọn nhà mạng, chọn mệnh giá.
  - `UtilityCatalogFragment`: Chọn chuyến bay, vé phim, phòng khách sạn, sản phẩm mua sắm.
  - `UtilitiesHubDialogFragment`: Khi người dùng chọn thanh toán hóa đơn hoặc tạo order xong từ dialog, đóng dialog và điều hướng sang `transactionFlow` trên `NavController` chính.

- Thêm destination mới **`R.id.transactionFlow`** (`TransactionExecutionFragment`) trong `app/src/main/res/navigation/nav_graph.xml`.
- **Linear Transaction Rule**: Cập nhật `MainActivity.syncBottomNavigation(...)` để **ẩn thanh điều hướng dưới (`main_bottom_navigation`)** khi `destination == R.id.transactionFlow`, đảm bảo nút CTA dưới cùng của màn Xác nhận và Thành công hiển thị trọn vẹn như thiết kế HTML.

### 2.2. Hợp đồng dữ liệu truyền vào (`TransactionFlowRequest`)

Mọi màn hình Bước 0 đóng gói thông tin thành `TransactionFlowRequest` (implements `Parcelable` / `Bundle` helper) với các trường:

- `kind`: `INTERNAL_TRANSFER | EXTERNAL_TRANSFER | DEPOSIT | WITHDRAWAL | BILL_PAYMENT | ORDER_PAYMENT`
- `amountMinor` (`long`), `currency` (`String`, mặc định `"VND"`)
- `sourceAccountId` (`String`, nullable với card topup/payment khi dùng thẻ ngoài)
- `sourceLabel` (`String`, VD `"Thanh toán (••• 4125)"` hoặc `"Thẻ quốc tế (Stripe)"`)
- `recipientName` (`String`, nullable), `recipientVerified` (`boolean`)
- `bankName` (`String`, nullable), `bankSubtitle` (`String`, nullable), `bankCode` (`String`, nullable)
- `targetNumber` (`String`, nullable — SĐT nhận, số TK nhận, mã hóa đơn, hoặc SĐT nạp)
- `methodLabel` (`String`, hiển thị trong pill phương thức)
- `note` (`String`, nullable — nội dung chuyển khoản hoặc chi tiết kỳ/dịch vụ)
- **Trường dành riêng cho từng API**:
  - `recipientAccountId` (`INTERNAL_TRANSFER`)
  - `billId` (`BILL_PAYMENT`)
  - `orderCategory`, `productId`, `orderCustomerDetails` (`ORDER_PAYMENT` — sẽ gọi `POST /orders` để lấy `billId`/`orderId` ở bước chuẩn bị hoặc trước khi vào `transactionFlow`)

---

## 3. Ánh xạ trường dữ liệu theo từng loại giao dịch

Quy tắc chung cho Card chi tiết ở **Bước 1 (Xác nhận)** và **Bước 3 (Biên lai)**: **Ẩn hoàn toàn dòng nào không có dữ liệu** (kèm hairline divider tương ứng) để card gọn gàng, không hiển thị text giả.

| Trường UI | Chuyển nội bộ (`INTERNAL_TRANSFER`) | Chuyển liên NH (`EXTERNAL_TRANSFER`) | Nạp tiền (`DEPOSIT`) | Rút tiền (`WITHDRAWAL`) | Hóa đơn / Tiện ích (`BILL_PAYMENT` / `ORDER_PAYMENT`) |
|---|---|---|---|---|---|
| **Tiêu đề Confirm** | Xác nhận chuyển tiền | Xác nhận chuyển tiền | Xác nhận nạp tiền | Xác nhận rút tiền | Xác nhận thanh toán |
| **Hero Label** | Tổng số tiền chuyển | Tổng số tiền chuyển | Tổng tiền nạp | Tổng tiền rút | Tổng tiền thanh toán |
| **Dòng 1** | **Tài khoản nguồn**: `Thanh toán (••• xxxx)` | **Tài khoản nguồn**: `Thanh toán (••• xxxx)` | **Nguồn nạp**: `Thẻ quốc tế (Stripe)` | **Tài khoản nguồn**: `Thanh toán (••• xxxx)` | **Nguồn thanh toán**: `Thẻ (Stripe)` hoặc `Thanh toán (••• xxxx)` |
| **Dòng 2** | **Người thụ hưởng**: `recipientName` + badge xanh `verified` | *(Ẩn — backend không trả tên người nhận liên NH)* | **Tài khoản nhận**: `Thanh toán (••• xxxx)` | **Đích nhận**: `Rút tiền mặt · Nimbus Bank` | **Dịch vụ**: Tên dịch vụ / gói sản phẩm |
| **Dòng 3** | **Ngân hàng nhận**: `Nimbus Bank` | **Ngân hàng nhận**: Tên từ `ExternalBankCatalog` | *(Ẩn)* | *(Ẩn)* | **Nhà cung cấp**: VD `EVNHCMC`, `SAWACO`, `Viettel` *(nếu có)* |
| **Dòng 4** | **Số điện thoại / TK nhận**: SĐT / mã TK | **Số tài khoản nhận**: `beneficiaryAccount` (monospace) | *(Ẩn)* | *(Ẩn)* | **Mã hóa đơn / SĐT**: `invoiceRef` hoặc SĐT nạp |
| **Dòng 5 (Phương thức)** | `Chuyển nhanh nội bộ 24/7` | `Chuyển nhanh Napas 24/7` | `Thanh toán thẻ Stripe` | `Rút tiền tài khoản` | `Thanh toán qua Stripe` / `Tài khoản thanh toán` |
| **Dòng 6 (Nội dung)** | `note` *(ẩn nếu rỗng)* | `note` *(ẩn nếu rỗng)* | *(Ẩn)* | *(Ẩn)* | Chi tiết hóa đơn / đơn hàng *(ẩn nếu rỗng)* |
| **Tiêu đề Mini Header (Success)** | `BIÊN LAI CHUYỂN KHOẢN` | `BIÊN LAI CHUYỂN KHOẢN` | `BIÊN LAI NẠP TIỀN` | `BIÊN LAI RÚT TIỀN` | `BIÊN LAI THANH TOÁN` |
| **Headline Success** | Chuyển tiền thành công | Chuyển tiền thành công | Nạp tiền thành công | Rút tiền thành công | Thanh toán thành công |

*Ghi chú định dạng tài khoản:* Lấy 4 ký tự cuối của `accountId` làm `(••• xxxx)` khi chưa có trường số tài khoản hiển thị riêng.

---

## 4. Đặc tả giao diện 3 bước khớp với HTML

### 4.1. Bước 1 — Màn hình Xác nhận (`Transfer_Confirmation.html`)
- **Nền**: `@drawable/background_gradient` (`#F4F6FA` → `#E4E9F2` → `#B9C4DC` → `#8D9CC0`) kết hợp 2 glow orb mờ trang trí ở góc trên phải và giữa trái.
- **Header (`56dp`)**:
  - Nút trái `40dp` hình tròn kính mờ: icon `ic_arrow_back` (Quay lại bước nhập liệu / hủy intent cũ nếu có).
  - Tiêu đề giữa: `18sp`, `ink` (`#1B2340`), medium/semibold.
  - Nút phải `40dp` hình tròn kính mờ: icon `ic_shield` (`#222E55`), bấm vào hiển thị hộp thoại tóm tắt bảo mật E2E & Smart OTP.
- **Hero Amount**:
  - Label chữ hoa nhỏ (`12sp`, `ink_muted`, letterSpacing `0.06`).
  - Giá trị số tiền (`38sp`, `sans-serif-light`, `ink`) + ký hiệu `₫` (`20sp`, `ink_muted`).
  - Pill trạng thái phí kính mờ (`glass_pill`): icon check xanh `#2E9E7B` + `"Phí dịch vụ: 0 ₫ (Miễn phí)"` (hoặc số tiền phí thực tế nếu `feeMinor > 0`).
- **Glass Details Card** (`rounded 24dp`, nền `#73FFFFFF`, viền `#BFFFFFFF` `1dp`, padding `16dp`):
  - Các hàng ngang `label` bên trái (`13sp`, `ink_muted`) — `value` bên phải (`13sp`, `ink`), ngăn cách bằng `1dp` divider (`#66FFFFFF`).
  - Hỗ trợ: icon ví ở dòng Tài khoản nguồn, badge xanh `verified` ở dòng Người thụ hưởng, monospace cho Số tài khoản nhận, pill `primary_fixed` (`#99DCE1FF`) + icon tia sét ở dòng Phương thức.
- **Security Guarantee Banner**: Card bo `16dp` kính mờ, icon khóa trong vòng tròn trắng + text `"Giao dịch được bảo vệ bằng mã hóa E2E và xác thực an toàn qua Nimbus Smart OTP."`
- **Bottom CTA cố định đáy**: Nút `#2F3E6E`, cao `54dp`, bo `16dp`, chữ trắng `15sp` + icon mũi tên phải: `"Xác nhận & nhập OTP"`.

### 4.2. Bước 2 — Xác thực OTP BottomSheet (`OTP_BottomSheet.html`)
- Triển khai bằng `OtpBottomSheetDialog` (DialogFragment / BottomSheetDialog trong suốt toàn màn hình) với:
  - **Backdrop**: nền tối mờ `#730F1530`.
  - **Nút đóng tròn nổi (`40dp`)**: nằm phía trên bên phải, tách khỏi mép trên của sheet `8dp`, nền trắng mờ `#D9FFFFFF`, icon `ic_close`.
  - **Thân Sheet**: nền `#F0FFFFFF`, bo top-left & top-right `32dp`, drag handle `40dp x 4dp`.
  - **Header Sheet**:
    - Vòng tròn `48dp` nền `#F4F6FA` viền trắng chứa icon `ic_shield` (`24dp`, `#2F3E6E`).
    - Tiêu đề `"Xác thực giao dịch"` (`20sp` bold, `#1B2340`).
    - Subtitle `"Nhập mã OTP 6 số để xác thực giao dịch"` (`13sp`, `#6B7694`) — **không hiển thị số điện thoại**.
  - **6 ô OTP riêng lẻ**:
    - 6 `TextView`/`FrameLayout` kích thước bằng nhau (`weight = 1`, cao `52dp`, bo `14dp`, gap `8dp`):
      - Ô đã có số: nền `#CCFFFFFF`, viền trắng, chữ số `22sp` bold `#1B2340`.
      - Ô đang active (vị trí kế tiếp cần nhập): nền trắng, viền `2dp` `#2F3E6E`, hiển thị vạch con trỏ `|` nhấp nháy alpha (`ObjectAnimator`).
      - Ô chưa nhập: nền `#CCF5F3F7`, viền trắng, chấm tròn `8dp` `#669AA4BC` ở giữa.
    - Chặn bàn phím hệ thống; tự động điền toàn bộ 6 chữ số khi backend `/intents/:id/otp` trả về mã demo (`DEMO_DISPLAY_CODE`).
  - **Đếm ngược gửi lại**: Icon đồng hồ `16dp` + `"Gửi lại mã sau 00:30"`; khi về `00:00` đổi thành `"Gửi lại mã"` cho phép bấm gọi lại `/intents/:id/otp`.
  - **Dòng trạng thái lỗi**: 1 `TextView` gọn (ẩn khi rỗng) để hiển thị thông báo sai OTP / khóa / quá giới hạn.
  - **Nút `"Xác nhận"`**: nền `#2F3E6E`, cao `48dp`, bo `20dp`, chữ trắng `15sp`. Disable khi chưa đủ 6 số hoặc đang xử lý.
  - **Bàn phím số `3x4` tùy chỉnh**:
    - 10 phím số `0–9` (`44dp` height, bo `16dp`, hiệu ứng ripple), nhập vào vị trí active.
    - Góc dưới trái: icon vân tay (`ic_fingerprint`); khi chạm hiện thông báo ngắn `"Xác thực sinh trắc học chưa khả dụng; vui lòng dùng mã OTP"`.
    - Góc dưới phải: icon xóa (`ic_backspace`), xóa chữ số cuối cùng.

### 4.3. Bước 3 — Màn hình Thành công (`Success_Screen.html`)
- **Mini Header (`52dp`)**:
  - Nút tròn `36dp` kính mờ bên trái: icon `ic_close` (Về trang chủ).
  - Text giữa chữ hoa `13sp` `ink_muted`: `BIÊN LAI CHUYỂN KHOẢN` / `BIÊN LAI NẠP TIỀN` / `BIÊN LAI RÚT TIỀN` / `BIÊN LAI THANH TOÁN`.
  - Nút tròn `36dp` kính mờ bên phải: icon `ic_share` (Chia sẻ biên lai).
- **Hero Success**:
  - Vòng tròn 2 lớp (`64dp` vòng kính mờ viền `#662F3E6E` + `44dp` vòng đặc `#2F3E6E` + icon check trắng `24dp`, kèm pulse ring nhẹ).
  - Tiêu đề (`22sp` medium `#1B2340`), số tiền lớn (`38sp` light `#1B2340`) + `₫`, ngày giờ hoàn tất (`HH:mm · dd 'Tháng' MM, yyyy`, `#6B7694`).
- **Glass Receipt Card** (`rounded 24dp`, padding `20dp`):
  1. `Mã giao dịch`: hiển thị mã gọn (ưu tiên `providerReference`, hoặc 12 ký tự đầu uppercase của `transactionId`/`intentId`) + icon `ic_copy`. Chạm vào copy **full ID thật** vào Clipboard + hiện feedback `"Đã sao chép mã giao dịch"`.
  2. Các dòng chi tiết tương ứng loại giao dịch (ẩn dòng rỗng).
  3. `Phí dịch vụ`: `0 ₫` + pill xanh `Miễn phí`.
  4. `Nội dung`: canh phải (ẩn nếu rỗng).
  5. `Trạng thái`: pill xanh lá (`#1A2E9E7B`) viền `#332E9E7B`, chấm tròn xanh `6dp` + `"Hoàn tất"`.
- **Trust Note**: Icon `ic_shield` + `"Giao dịch được bảo mật bởi Nimbus SafeGuard 256-bit"`.
- **Bottom Actions**:
  - Nút phụ (pill bo tròn `999dp`, cao `50dp`, nền kính mờ `#A6FFFFFF`): icon `ic_share` + `"Chia sẻ biên lai"`.
  - Nút chính (pill bo tròn `999dp`, cao `54dp`, nền `#2F3E6E`, chữ trắng): `"Về trang chủ"` (popBackStack về `R.id.customerHome`).

---

## 5. Xử lý các nhánh kỹ thuật & trường hợp đặc biệt

1. **Tạo Intent & Idempotency**:
   - Chỉ gọi `POST` tạo Intent khi người dùng bấm `"Xác nhận & nhập OTP"` ở Bước 1 (tránh tạo intent thừa nếu người dùng quay lại chỉnh sửa).
   - Giữ `requestId` ổn định cho mỗi phiên xác nhận để hỗ trợ khôi phục qua `/by-request/:id` nếu rớt mạng.
   - Với `ORDER_PAYMENT`, nếu `billId` chưa có sẵn, gọi `POST /orders` trước khi gọi `POST /payments/intents` và cập nhật `billId` để recovery không tạo đơn trùng.
2. **Face Step-Up (`faceRequired == true`)**:
   - Sau khi tạo Intent, nếu `faceRequired && !faceVerified`, mở `FaceCamera` (`verifyIntentFace`) trước khi mở `OtpBottomSheetDialog`.
3. **Stripe `PaymentSheet` (luồng `DEPOSIT` và `PAYMENT` bằng thẻ)**:
   - Sau khi Verify OTP thành công, gọi `POST /topups` hoặc `POST /payments`.
   - Nếu backend trả `clientSecret`, tạm ẩn `OtpBottomSheetDialog` và mở Stripe `PaymentSheet`.
   - Khi `PaymentSheetResult.Completed`: hiển thị trạng thái chờ đồng bộ nhẹ trên màn hình Xác nhận và poll `GET /topups/:id` hoặc `GET /intents/:id` cho tới `SUCCEEDED`/`COMMITTED`, rồi chuyển sang Bước 3.
   - Nếu `Canceled` hoặc `Failed`: giữ trạng thái `commitPending`, hiển thị thông báo rõ ràng kèm nút `"Tiếp tục thanh toán / kiểm tra kết quả"`.
4. **Demo Movement Reconciliation (`WITHDRAWAL`, `EXTERNAL_TRANSFER`)**:
   - Backend `demo-movements/:id/commit` trả `COMMIT_PENDING` trong ~5 giây đầu (`reconcileAfter`). Client tự động gọi `POST /demo-movements/:id/reconcile` theo chu kỳ ngắn (tối đa vài lần) với trạng thái loading trên nút Xác nhận cho tới `COMMITTED` hoặc `FAILED`.
5. **Chế độ Pending Recovery (`SharedPreferences`)**:
   - Giữ lại cơ chế lưu `pending_bank_transfer` cho các intent đang `commitPending` khi thoát/xoay màn hình để có thể khôi phục kết quả an toàn.

---

## 6. Kiểm thử & Xác minh

- Cập nhật `Phase2Presentation` & `Phase2PresentationTest`: `usesRequestForm("DEPOSIT")` trả `true` (vì Nạp tiền nay cũng nhập tài khoản & số tiền tại `MoneyRequestFragment`).
- Bổ sung unit test cho:
  - Builder/Mapper ánh xạ `TransactionFlowRequest` → danh sách dòng hiển thị của Bước 1 và Bước 3 cho cả 5 nhóm giao dịch (kiểm tra ẩn dòng trống, format mã giao dịch rút gọn vs full copy, format thời gian).
  - Logic trạng thái của bộ nhập OTP 6 số (nhập số, xóa lùi, auto-fill, đủ 6 số).
- Chạy `./gradlew.bat testDebugUnitTest assembleDebug -PfirebaseDemo=true` (và kiểm tra `BaseFlowInstrumentedTest` không bị ảnh hưởng).

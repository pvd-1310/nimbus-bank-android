# Kế hoạch: Seed dữ liệu thực tế hơn

> **Dành cho agent triển khai:** Thực hiện lần lượt các mục và cập nhật checkbox sau khi hoàn tất.

**Mục tiêu:** Làm dữ liệu seed không đăng nhập và nội dung tiện ích trông tự nhiên, nhất quán; giữ nguyên đăng nhập, bảo vệ demo nội bộ và trạng thái giao dịch đang hoạt động.

**Phạm vi kỹ thuật:** TypeScript/Firebase Admin ở `backend/src/scripts/seed-demo.ts`; Android Java/XML ở màn tiện ích.

**Đặc tả:** `docs/superpowers/specs/2026-11-25-realistic-seed-data-design.md`

## Ràng buộc

- Giữ UID, email, role, password flow và Firebase claims của tài khoản đăng nhập.
- Giữ `demo: true`, `demoNamespace`, kiểm tra namespace và các marker nội bộ.
- Không đổi schema/API; không chạy seed và không ghi Firebase.
- Bảo toàn số dư, trạng thái tài khoản, hóa đơn đã thanh toán/khóa, đơn hàng, thanh toán và lịch sử giao dịch hiện có.
- Chỉ cập nhật fixture thuộc namespace đang seed; từ chối dữ liệu thuộc namespace khác.

## Việc cần làm

### 1. Hồ sơ và dữ liệu ngân hàng

- [x] Thay tên hiển thị, mã khách hàng/nhân viên, địa chỉ và số điện thoại che một phần bằng dữ liệu Việt Nam tổng hợp; giữ nguyên ID, email, role.
- [x] Thêm upsert có kiểm tra `demoNamespace` cho hồ sơ và dữ liệu tĩnh; giữ marker nội bộ.
- [x] Đặt số dư ban đầu khác nhau, hợp lý cho tài khoản mới. Với tài khoản hiện có, giữ số dư/trạng thái nếu đã phát sinh thay đổi; luôn giữ `version` và trạng thái không active.
- [x] Thay alias thẻ và mã sản phẩm tiết kiệm mang chữ demo bằng giá trị tự nhiên, giữ ID tài liệu và liên kết policy nhất quán.
- [x] Giữ timestamp đã có; chỉ tạo timestamp mới cho fixture mới.

### 2. Danh mục, hóa đơn và chi nhánh

- [x] Thay tên chung chung và giá catalog cho điện, nước, nạp tiền, phim, khách sạn, thương mại điện tử bằng giá trị cụ thể, hợp lý; giữ ID và service type.
- [x] Cập nhật ba chuyến SGN–HAN có hãng, số hiệu, loại máy bay, giờ bay, thời lượng, hạng vé và giá nhất quán; vẫn đọc được dữ liệu cũ thiếu trường tùy chọn.
- [x] Giữ ba mạng Viettel, MobiFone, VinaPhone với key A/B/C và sáu mệnh giá mỗi mạng.
- [x] Cập nhật nhà cung cấp, mã tham chiếu, kỳ hóa đơn và chỉ số điện/nước; dùng kỳ hiện tại. Với hóa đơn đã thanh toán/khóa, giữ nguyên trạng thái và số tiền.
- [x] Cập nhật tên, địa chỉ, tọa độ và giờ làm việc chi nhánh; chỉ upsert tài liệu cùng namespace.
- [x] Sửa nội dung dry-run cho đúng: mô tả fixture có thể được cập nhật, còn đăng nhập và trạng thái giao dịch được giữ.

### 3. Nội dung hiển thị tiện ích

- [x] Bỏ nhãn demo trong phụ đề/ghi chú hóa đơn; dùng EVN và SAWACO khớp dữ liệu seed.
- [x] Bỏ hậu tố demo ở tổng tiền nạp điện thoại, giữ nguyên bộ lọc marker nội bộ trong Java.
- [x] Bỏ badge và câu chữ demo ở luồng vé máy bay (thẻ tuyến, giá, thông báo rỗng/fallback, nút tìm kiếm); giữ logic lọc, fallback dữ liệu, ID sản phẩm và checkout.
- [x] Rà lại để marker demo nội bộ và kiểm soát đơn hàng không bị thay đổi.

### 4. Rà soát và build

- [x] Rà tất cả collection, kiểm tra namespace và bảo toàn trạng thái mutable.
- [x] Chạy `npm run build` trong `backend/`.
- [x] Chạy `gradlew.bat assembleDebug -PfirebaseDemo=true --offline`.
- [x] Chạy `git diff --check`; xác nhận các thay đổi ngoài phạm vi không bị đụng tới.
- [x] Không chạy test và không áp dụng seed.

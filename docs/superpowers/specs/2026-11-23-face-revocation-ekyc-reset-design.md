# Thiết kế thu hồi khuôn mặt và đặt lại eKYC

## Mục tiêu

Khi khách hàng chọn **Thu hồi template khuôn mặt**, hệ thống thu hồi mẫu dùng để đăng nhập, đặt hồ sơ eKYC hiện tại về trạng thái có thể đăng ký lại và xóa ảnh eKYC đã lưu. Hồ sơ không còn hiển thị như đã đăng ký khuôn mặt.

## Mục tiêu cụ thể

- Chỉ áp dụng với khách hàng đã xác thực trong phiên hiện tại.
- Thu hồi template khuôn mặt để không thể dùng cho đăng nhập hoặc xác minh giao dịch.
- Đổi yêu cầu eKYC mới nhất sang `RESUBMIT_REQUIRED`, lý do `FACE_TEMPLATE_REVOKED`, để luồng `/ekyc` hiện tại tạo được hồ sơ nháp mới.
- Cập nhật `kycStatus` của hồ sơ khách hàng thành `RESUBMIT_REQUIRED`, đồng thời giữ tài khoản demo đang hoạt động.
- Xóa mọi phiên bản ảnh eKYC đã tải lên của khách hàng, kể cả ở các yêu cầu cũ, và xóa tham chiếu ảnh khỏi hồ sơ.
- Giữ lại dữ liệu kiểm toán tối thiểu; không lưu ảnh hoặc embedding khuôn mặt đã mã hóa sau khi hoàn tất.
- Cho phép gọi thu hồi lặp lại an toàn và báo rõ nếu việc dọn ảnh trên S3 còn chờ xử lý.

## Ngoài phạm vi

- Không khóa đăng nhập Firebase, vô hiệu hóa tài khoản khách hàng hoặc thay đổi số dư/giao dịch.
- Không đổi mô hình nhận diện khuôn mặt hoặc luồng chụp ảnh eKYC.
- Không xóa thông tin định danh khách hàng hoặc toàn bộ metadata lịch sử eKYC.

## Phương án

1. **Đặt lại mềm và dọn ảnh minh chứng (được chọn).** Thu hồi template, chuyển hồ sơ eKYC mới nhất sang cần đăng ký lại, xóa ảnh lưu trữ, xóa tham chiếu ảnh và giữ metadata kiểm toán tối thiểu. Tài khoản vẫn hoạt động và có thể tạo hồ sơ eKYC mới.
2. **Xóa hẳn lịch sử eKYC.** Xóa mọi hồ sơ và ticket tải lên cùng tất cả phiên bản ảnh. Cách này mất khả năng truy vết, cần hỗ trợ xóa rộng hơn trong tầng lưu trữ và xử lý phục hồi phức tạp nếu xóa ảnh lỗi giữa chừng.

## Thiết kế

### Xử lý ở backend

Mở rộng endpoint đã xác thực `POST /me/face/revoke`. Trong một giao dịch Firestore, xác thực khách hàng, đánh dấu template là `REVOKED` (không giữ ciphertext embedding), tìm hồ sơ mới nhất qua `ekycCustomerHeads/{uid}`, đổi trạng thái thành `RESUBMIT_REQUIRED`, ghi lý do `FACE_TEMPLATE_REVOKED`, xóa `slots` và `storageRefs`, cập nhật `kycStatus` của hồ sơ khách hàng, và đánh dấu mọi upload ticket thuộc khách hàng để dọn. Giữ trạng thái `users/{uid}` là `ACTIVE` để khách hàng tiếp tục dùng tài khoản demo.

Giao dịch ghi audit với trạng thái trước/sau và số object ảnh được đưa vào hàng chờ xóa. Endpoint idempotent: gọi lại vẫn giữ trạng thái đặt lại và không lỗi chỉ vì template đã bị thu hồi.

### Dọn ảnh minh chứng

Dùng cờ `purging` hiện có trên ticket và script dọn dữ liệu xác minh hiện tại. Script xóa mọi phiên bản object S3 rồi xóa upload ticket. Nếu dọn lỗi, ticket vẫn mang cờ `purging` để lần chạy sau thử lại an toàn. Phản hồi endpoint phân biệt trạng thái đặt lại đã hoàn thành với việc dọn ảnh còn chờ. Nếu storage chưa cấu hình, việc đặt lại tài khoản/template vẫn hoàn thành và ticket còn chờ đến khi chạy được job dọn.

### Hành vi ứng dụng

Hộp thoại xác nhận nói rõ thao tác sẽ tắt đăng nhập khuôn mặt, xóa ảnh eKYC đã lưu và yêu cầu đăng ký eKYC lại. Khi thành công, tải lại `/ekyc` và thông tin hồ sơ. Hồ sơ mới nhất `RESUBMIT_REQUIRED` được hiển thị là **Cần chụp lại eKYC** (hoặc nhãn demo hiện có), không còn là **Đã đăng ký khuôn mặt demo**. Giữ nguyên đăng nhập email/mật khẩu.

### Đăng ký lại

Luồng tạo `/ekyc` hiện tại chấp nhận hồ sơ mới nhất ở trạng thái `RESUBMIT_REQUIRED`, tạo hồ sơ nháp mới; khi nộp thành công, backend ghi hồ sơ VERIFIED và template khuôn mặt mới. Không cần quy trình reset quản trị riêng cho luồng demo thông thường.

## Ràng buộc và rủi ro

- Giao dịch Firestore không thể commit nguyên tử cùng thao tác xóa S3. Đánh dấu ticket `purging` trong Firestore trước và dùng job dọn có thể thử lại để không mất yêu cầu xóa khi S3 tạm thời lỗi.
- Đăng nhập khuôn mặt hiện yêu cầu cả template hợp lệ lẫn hồ sơ eKYC VERIFIED. Thu hồi template và đổi trạng thái hồ sơ mới nhất đều ngăn sử dụng trong lúc chờ dọn ảnh.
- Metadata lịch sử eKYC được giữ lại để kiểm toán; object key, version, slot ảnh và ciphertext embedding phải được xóa sau khi dọn hoàn tất.
- Không đổi trạng thái kích hoạt tài khoản vì điều đó sẽ khóa khách hàng khỏi tài khoản demo và ngăn họ đăng ký lại.

## Xác minh

- Kiểm tra backend cho việc thu hồi của khách hàng đã đăng ký, đặt lại trạng thái hồ sơ và profile, cờ dọn ticket, audit, gọi lặp lại và từ chối actor không phải CUSTOMER.
- Xác minh đăng nhập khuôn mặt và xác thực giao dịch từ chối template đã thu hồi.
- Xác minh `/ekyc` tạo được hồ sơ nháp mới sau thu hồi và việc đăng ký lại thành công khôi phục trạng thái VERIFIED.
- Kiểm tra ứng dụng hiển thị hộp thoại xác nhận và trạng thái mới. Không thực hiện thao tác ghi lên Firebase/S3 đang kết nối trong quá trình xác minh cục bộ.

## Quyết định triển khai

- Giữ lịch sử eKYC và audit tối thiểu, đồng thời xóa ảnh minh chứng.
- Giữ tài khoản khách hàng hoạt động; chỉ đặt lại trạng thái đăng ký eKYC và xác thực khuôn mặt.
- Dùng lại cờ `purging` và script dọn hiện có thay vì tạo hàng đợi xóa riêng.

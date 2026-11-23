# Kế hoạch triển khai thu hồi khuôn mặt và đặt lại eKYC

> **Dành cho agent triển khai:** dùng kỹ năng `superpowers:executing-plans` để thực hiện kế hoạch theo từng tác vụ.

**Mục tiêu:** Khi khách hàng thu hồi khuôn mặt, template bị vô hiệu, hồ sơ eKYC đặt lại để đăng ký mới, ảnh minh chứng được đưa vào quy trình xóa có thể thử lại, giao diện hiện trạng thái mới.

**Kiến trúc:** Dùng giao dịch Firestore để thay đổi trạng thái, đánh dấu ticket S3 bằng `purging`, giữ audit tối thiểu và dùng job dọn ảnh sẵn có. Ứng dụng tiếp tục gọi cùng endpoint nhưng giải thích rõ tác động và tải lại trạng thái eKYC sau kết quả.

**Công nghệ:** Android Java, Node.js/TypeScript, Express, Firestore transaction, S3 versioned evidence.

**Thiết kế:** `docs/superpowers/specs/2026-11-23-face-revocation-ekyc-reset-design.md`

## Ràng buộc chung

- Chỉ actor CUSTOMER đang xác thực được phép thu hồi dữ liệu của chính mình.
- Giữ `users/{uid}` ở trạng thái ACTIVE; không sửa số dư hay nghiệp vụ thanh toán/chuyển khoản.
- Trạng thái eKYC mới nhất sau thu hồi là `RESUBMIT_REQUIRED`, lý do `FACE_TEMPLATE_REVOKED`.
- Template không được giữ ciphertext; mọi ticket ảnh của khách hàng cần mang `purging: true`.
- Giữ metadata tối thiểu phục vụ kiểm toán, không giữ ảnh/embedding sau khi dọn xong.
- Không thêm hoặc chạy test trong kế hoạch này; chỉ chạy bước biên dịch/build được cho phép.

## Tập trung rà soát

- Khách hàng chưa có template nhưng có hồ sơ eKYC: endpoint vẫn đặt lại trạng thái và có thể gọi lặp lại.
- Nhiều hồ sơ lịch sử và nhiều phiên bản upload: tất cả ticket thuộc khách hàng được đánh dấu dọn, không chỉ ticket mới nhất.
- S3 tạm lỗi hoặc chưa cấu hình: trạng thái đặt lại đã lưu, ticket vẫn ở trạng thái chờ để job dọn thử lại.
- Hồ sơ đang REVIEWING đồng thời với yêu cầu thu hồi: giao dịch Firestore không để lần nộp cũ tái tạo template sau thu hồi.
- Actor không phải khách hàng: bị từ chối và không thay đổi template, hồ sơ hoặc ticket.

## Tác vụ 1: Đặt lại dữ liệu eKYC và template trong backend

**Tệp:**
- Sửa `backend/src/modules/ekyc/ekyc.service.ts`
- Sửa `backend/src/modules/security/face.routes.ts`
- Sửa `backend/src/scripts/purge-verification-data.ts`
- Sửa `backend/src/docs/openapi.ts`

**Giao diện:**
- Endpoint `POST /me/face/revoke` gọi service eKYC sở hữu nghiệp vụ đặt lại.
- Phản hồi gồm `status: "REVOKED"`, `ekycStatus: "RESUBMIT_REQUIRED"`, `evidenceCleanup.status` (`PENDING` hoặc `NOT_REQUIRED`) và `objectsQueued`.

- [x] Trong giao dịch Firestore, xác thực CUSTOMER, đọc head và các hồ sơ/ticket của chủ thể, đánh dấu template REVOKED không giữ ciphertext, đổi hồ sơ mới nhất sang `RESUBMIT_REQUIRED`, xóa các tham chiếu ảnh, cập nhật customer `kycStatus`, đánh dấu ticket `purging`, ghi audit before/after và trả số ticket chờ dọn.
- [x] Làm thao tác idempotent và xử lý cuộc đua với submit eKYC bằng kiểm tra trạng thái/đọc-ghi trong cùng transaction.
- [x] Cập nhật route để gọi service mới và cập nhật hợp đồng OpenAPI phản ánh trạng thái đặt lại cùng kết quả dọn ảnh.
- [x] Biên dịch backend bằng `npm run build` trong `backend/`; đầu ra kỳ vọng là build hoàn tất thành công.

## Tác vụ 2: Làm rõ trạng thái thu hồi trong ứng dụng

**Tệp:**
- Sửa `app/src/main/java/com/example/final_term/ui/profile/ProfileFragment.java`
- Sửa `app/src/main/java/com/example/final_term/ui/ekyc/EkycDialogFragment.java`
- Sửa `app/src/main/res/layout/fragment_profile.xml` nếu cần cập nhật nội dung tĩnh

**Giao diện:**
- Hộp thoại nêu rõ template đăng nhập bị thu hồi, ảnh eKYC được đưa vào hàng chờ xóa và khách hàng cần làm lại eKYC.
- Sau phản hồi thành công, app tải lại eKYC và hiển thị thông báo khác nhau khi ảnh đã được xếp hàng chờ dọn hoặc không có ảnh.

- [x] Cập nhật nội dung xác nhận để mô tả tác động của thao tác.
- [x] Đọc `evidenceCleanup` trong phản hồi, hiển thị kết quả phù hợp, sau đó gọi `fetchEkycStatus()` và `fetchCameraAndCapabilitiesStatus()` như hiện tại.
- [x] Biên dịch Android bằng `./gradlew :app:compileDebugJavaWithJavac`; đầu ra kỳ vọng là BUILD SUCCESSFUL.

## Tác vụ 3: Đồng bộ tài liệu và rà soát

**Tệp:**
- Sửa `backend/README.md`
- Sửa thiết kế `docs/superpowers/specs/2026-11-23-face-revocation-ekyc-reset-design.md`

- [x] Dịch và giữ bản thiết kế tiếng Việt.
- [x] Ghi rõ endpoint thu hồi đặt lại eKYC, giữ tài khoản ACTIVE, xóa ảnh bằng `purge:verification`, và retry ticket `purging`.
- [x] Rà soát diff có giới hạn trong các tệp của kế hoạch; chạy `git diff --check` cho các tệp đã sửa.

## Quyết định thực thi

- Làm trực tiếp trong workspace hiện tại vì working tree có nhiều thay đổi chưa commit và tạo worktree sẽ bỏ mất các thay đổi đó; không tạo commit để tránh gộp chung thay đổi có sẵn của người dùng.
- Chỉ đánh dấu hoàn tất sau khi đọc kết quả các lệnh build nêu trong kế hoạch.

# Kế hoạch chuyển eKYC sang thư viện ảnh và xử lý lỗi Face API

**Mục tiêu:** Chọn ảnh CCCD/selfie từ thư viện, chuẩn hóa ảnh trước khi tải lên, và không để lỗi Face API giữ hồ sơ ở trạng thái `REVIEWING`.

**Cách làm:** Sửa mount mặc định để Face API thấy model đúng cấu trúc; các lỗi setup/timeout/busy trong bước nhận diện chuyển hồ sơ sang `RESUBMIT_REQUIRED`. Thay camera eKYC bằng Android system picker, giữ nguyên xem trước, upload S3, và API.

## Tác vụ

1. **Face API runtime:** [x] đổi mặc định `FACE_MODEL_PATH` của Docker Compose sang `./backend/private-face-model`; log đầy đủ lỗi khởi tạo model.
2. **Backend eKYC:** [x] phân loại lỗi Face API có thể phục hồi thành trạng thái `RESUBMIT_REQUIRED`, cập nhật customer status và audit; cho phép gửi lại hồ sơ `REVIEWING` quá 60 giây.
3. **Android eKYC:** [x] dùng `GetContent` cho từng slot; copy URI vào cache, chuẩn hóa JPEG giới hạn 5 MB, hỗ trợ khôi phục sau xoay màn hình, cập nhật nhãn camera thành thư viện.
4. **Kiểm chứng:** [x] chạy backend build, Android compile và `git diff --check`; không chạy test suite.

## Ràng buộc

- Không xóa hoặc di chuyển file model hiện có.
- Giữ nguyên luồng upload ticket, xác nhận version S3, và so khớp CCCD/selfie.
- Bộ chọn hệ thống không cần quyền đọc toàn bộ thư viện.
- Không gọi submit eKYC trên dữ liệu thật trong lúc kiểm chứng.

# Nimbus Bank

Nimbus Bank là đồ án môn Phát triển ứng dụng di động. Dự án gồm một ứng dụng Android cho hai vai trò **Khách hàng** và **Nhân viên ngân hàng**, một API TypeScript chạy riêng và Firebase để xác thực, lưu trữ dữ liệu. Đây là ứng dụng đồ án, không dùng để giao dịch tiền thật.

## Kiến trúc

| Thành phần | Vai trò |
| --- | --- |
| [Android](app/) | Giao diện Java/XML với AndroidX Navigation. Đăng nhập bằng Firebase Auth, đọc dữ liệu được cấp quyền từ Firestore và gọi REST API cho các thao tác nghiệp vụ. |
| [Backend](backend/) | Node.js và Express/TypeScript. Xác minh Firebase ID token, kiểm tra vai trò và quyền sở hữu, xử lý OTP, giao dịch, thanh toán và ghi dữ liệu qua Firebase Admin. |
| [Firestore](firestore.rules) | Lưu hồ sơ, tài khoản, lịch sử giao dịch, thông báo và dữ liệu nghiệp vụ. Rules giới hạn quyền đọc của Android và không cho ứng dụng ghi trực tiếp số dư hoặc giao dịch. |

Firestore có SDK cho Android; không cần đi qua backend để đọc mọi dữ liệu. Backend chạy bên ngoài APK để người dùng không thể tự sửa số dư, bỏ qua OTP hoặc giữ khóa bí mật của nhà cung cấp trên thiết bị. Khi chuyển nội bộ, Android gửi yêu cầu theo thứ tự **tạo intent, xác thực OTP, xác nhận chuyển**. Backend kiểm tra lại điều kiện và ghi số dư hai bên, bút toán và lịch sử trong cùng một giao dịch Firestore.

## Tính năng trong mã hiện tại

| Nhóm | Hiện trạng |
| --- | --- |
| Đăng nhập và dữ liệu tài khoản | Android có đăng nhập email/mật khẩu, điều hướng theo vai trò, hồ sơ chỉ đọc, danh sách tài khoản, thông báo và lịch sử giao dịch. Màn tiết kiệm và khoản vay mới hiển thị tổng quan; API tính lãi và lịch trả nợ chưa được nối vào màn hình. |
| Chuyển tiền và nạp/rút | Android đã nối API chuyển nội bộ và yêu cầu nạp/rút. Yêu cầu nạp/rút ở trạng thái `REQUESTED` không làm thay đổi số dư. OTP hiện là cơ chế demo, chưa phải OTP production. Chuyển liên ngân hàng chưa tích hợp. |
| Nhân viên ngân hàng | Có màn xem tài khoản và lịch sử. Các màn duyệt eKYC, audit và chính sách lãi suất chưa nối API. Backend có mã cho quản trị hồ sơ khách hàng, audit và cập nhật chính sách lãi suất. |
| Thanh toán và tiện ích | Backend có Stripe test mode, webhook và xử lý đơn demo; Android chưa có checkout. |
| eKYC, bản đồ và chatbot | Module eKYC backend đang thiếu. Backend có danh mục chi nhánh nhưng chưa tính tuyến đường; chatbot chưa triển khai. |

## Cài đặt và chạy

### Android

1. Mở thư mục gốc bằng Android Studio. Cài Android SDK API 37 qua SDK Manager; dự án hiện đặt `minSdk`, `targetSdk` và `compileSdk` là 37. Dùng thiết bị hoặc emulator chạy API 37 trở lên.
2. Đăng ký ứng dụng `com.example.final_term` trong Firebase và đặt `google-services.json` vào `app/`. Gradle yêu cầu tệp này ngay khi cấu hình dự án. Xem [hướng dẫn Firebase](docs/firebase-setup.md) để bật Email/Password, thiết lập dữ liệu và triển khai rules/indexes trên project thử nghiệm.
3. Nếu chạy cùng API local với cổng mẫu `3000`, thêm `backendBaseUrl=http://10.0.2.2:3000` vào `local.properties` với Android Emulator. Nếu không cấu hình, app Debug mặc định gọi `http://10.0.2.2:3001`. Khi build để phát hành, cấu hình URL backend HTTPS.
4. Sync Gradle và chạy cấu hình `app`. Android hiện không tự chuyển Firebase Auth/Firestore sang Firebase Emulator; để kiểm thử luồng REST từ app, cấu hình app và backend dùng cùng Firebase project thử nghiệm và cấp custom claims phù hợp cho tài khoản demo.

### Backend

**Checkout hiện thiếu** `backend/src/modules/ekyc/ekyc.routes.ts`, `ekyc.service.ts` và `s3.provider.ts`. Các entrypoint vẫn import chúng, nên cần khôi phục module này trước khi build và chạy API.

Sau khi khôi phục module, cài Node.js 22 trở lên, rồi chạy trong `backend/`:

```sh
npm ci
npm run build
npm test
npm run dev
```

Trước `npm run dev`, sao chép [`backend/.env.example`](backend/.env.example) thành `backend/.env`, điền cấu hình và khởi động Firebase Auth/Firestore Emulator nếu dùng `APP_MODE=emulator`. Xem [README backend](backend/README.md) để chọn chế độ emulator hoặc connected, cấu hình providers và seed dữ liệu. Khi API chạy trên cổng mẫu, `GET http://127.0.0.1:3000/health` kiểm tra tiến trình và `GET http://127.0.0.1:3000/openapi.json` xem hợp đồng API.

Không commit `.env`, `google-services.json`, Firebase Admin credentials, khóa ký APK, khóa provider hoặc ảnh eKYC. Chỉ dùng dữ liệu tổng hợp cho demo.

## Kiểm thử và tài liệu

| Nội dung | Lệnh hoặc tài liệu |
| --- | --- |
| Android unit test và debug APK | `gradlew.bat testDebugUnitTest assembleDebug` trên Windows; dùng `./gradlew` trên macOS/Linux. |
| Android instrumentation | `gradlew.bat connectedDebugAndroidTest` khi có thiết bị hoặc emulator tương thích. |
| Backend unit/HTTP test | `npm test` trong `backend/`; đọc [README backend](backend/README.md) để chạy bộ test Firebase Emulator riêng. |
| Thiết kế dữ liệu | [Đối chiếu schema Firestore](docs/database-template-alignment.md). |
| Bằng chứng các giai đoạn trước | [Tài liệu kiểm thử](docs/verification/). Đây là kết quả lịch sử, không xác nhận checkout hiện tại đã build hoặc các luồng đã chạy hoàn chỉnh. |

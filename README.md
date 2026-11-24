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
| Đăng nhập và tài khoản | Đăng nhập Firebase theo vai trò, cập nhật hồ sơ theo quyền, đối chiếu ảnh eKYC demo, xem checking, lãi dự kiến savings, lịch mortgage và lịch sử. |
| Chuyển tiền và nạp/rút | Chuyển nội bộ ghi sổ nguyên tử. Nạp/rút có yêu cầu riêng và luồng settlement mô phỏng. Chuyển liên ngân hàng dùng DEMO_BANK và tài khoản mẫu. OTP hiển thị trong app là demo. |
| Nhân viên | Tạo hồ sơ khách hàng, cập nhật có lý do, đọc audit phân trang, xem bằng chứng eKYC theo quyền và ban hành chính sách lãi suất. |
| Thanh toán và tiện ích | Hóa đơn, nạp điện thoại, vé, phòng và hàng mẫu có quote/đơn demo. Mọi khoản thanh toán dịch vụ dùng Stripe Sandbox qua PaymentSheet. Backend xác nhận kết quả qua webhook. |
| Chi nhánh và chatbot | Danh sách chi nhánh hoạt động khi thiếu Maps hoặc vị trí. Google Routes so tuyến đi bộ trong tập đã truy vấn. Chatbot tùy chọn mở các màn cho phép. |

Xem [đối chiếu PDF và checklist duyệt](docs/final-project-assessment.md).

## Cài đặt và chạy

### Android demo cục bộ

Yêu cầu Android SDK compile API 37 và thiết bị chạy API 26 trở lên. Bản demo dùng API backend cục bộ trên cổng 3198, Auth Emulator cổng 19099 và Firestore Emulator cổng 18080 qua `10.0.2.2` trên Android Emulator. Build bằng:

```sh
./gradlew --offline testDebugUnitTest assembleDebug -PfirebaseDemo=true
```

Bản này có nhãn Nimbus Bank (Local), package `com.example.final_term.localreview` và dùng Firebase app tên riêng với project `demo-mobile-banking`. Có thể cài đồng thời với package Android thông thường. Bản demo dùng Firebase Emulator và hỗ trợ Stripe Sandbox với khóa công khai `pk_test_...`. Xem [chạy backend và seed demo cục bộ](backend/README.md#demo-cục-bộ-từ-android-emulator).

### Android với Firebase project thử nghiệm

1. Đặt `app/google-services.json` cho ứng dụng `com.example.final_term`. App và backend phải dùng cùng project. Cấp custom claims và quyền hồ sơ tương ứng. Xem [cấu hình Firebase](docs/firebase-setup.md).
2. Cấu hình URL backend phù hợp bằng `ANDROID_BACKEND_URL` trong `.env` ở thư mục gốc hoặc truyền `-PbackendBaseUrl=...`. Debug chấp nhận HTTP loopback. Release dùng HTTPS.
3. Build bình thường với `./gradlew --offline testDebugUnitTest assembleDebug`. Thiết lập Stripe test, Google Routes và OpenAI theo tài liệu backend nếu chạy các đường dẫn provider đã được duyệt.

### Backend

Cài Node.js 22 trở lên, rồi chạy các lệnh sau trong `backend/`:

```sh
npm ci
npm run build
npm test
npm run dev
```

Trước `npm run dev`, sao chép [`.env.example`](.env.example) thành `.env` tại thư mục gốc, điền cấu hình và khởi động Firebase Auth/Firestore Emulator nếu dùng `APP_MODE=emulator`. Xem [README backend](backend/README.md) để chọn chế độ emulator hoặc connected, cấu hình providers và seed dữ liệu. Khi API chạy trên cổng mẫu, `GET http://127.0.0.1:3000/health` kiểm tra tiến trình và `GET http://127.0.0.1:3000/openapi.json` xem hợp đồng API.

`/health` kiểm tra tiến trình HTTP, không xác nhận Firebase hay S3 đã được cấu hình. Các route nghiệp vụ như `/accounts`, `/ekyc` và `/officer/customers/:id` không có tiền tố `/v1`.

Không commit `.env`, `google-services.json`, Firebase Admin credentials, khóa ký APK, khóa provider hoặc ảnh eKYC. Chỉ dùng dữ liệu tổng hợp cho demo.

## Kiểm thử và tài liệu

| Nội dung | Lệnh hoặc tài liệu |
| --- | --- |
| Android unit test và debug APK | `gradlew.bat testDebugUnitTest assembleDebug` trên Windows; dùng `./gradlew` trên macOS/Linux. |
| Android instrumentation | `gradlew.bat connectedDebugAndroidTest` khi có thiết bị hoặc emulator tương thích. |
| Backend unit/HTTP test | `npm test` trong `backend/`; đọc [README backend](backend/README.md) để chạy bộ test Firebase Emulator riêng. |
| Thiết kế dữ liệu | [Đối chiếu schema Firestore](docs/database-template-alignment.md). |
| Bằng chứng các giai đoạn trước | [Tài liệu kiểm thử](docs/verification/). Đây là kết quả lịch sử, không xác nhận checkout hiện tại đã build hoặc các luồng đã chạy hoàn chỉnh. |

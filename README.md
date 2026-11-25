# Nimbus Bank Android

> Ứng dụng ngân hàng phục vụ đồ án/học tập. Hãy chỉ dùng Firebase project, AWS bucket và Stripe ở môi trường thử nghiệm; không nhập dữ liệu khách hàng thật hoặc thông tin thẻ thật.

README này hướng dẫn chuẩn bị dịch vụ, chạy backend, ứng dụng Android và các tích hợp bên ngoài. Có hai cấu hình:

- **Local**: Firebase Emulator + backend chạy trên máy + Android Emulator. Dùng để phát triển luồng ngân hàng cơ bản.
- **Đầy đủ**: Firebase project thử nghiệm + Docker backend/Face API + S3 + Stripe test + Android. Maps/Routes và chatbot là tùy chọn.

## Thành phần hệ thống

| Thành phần | Vai trò | Cần dịch vụ ngoài? |
|---|---|---|
| Android app | Giao diện khách hàng và nhân viên | Firebase Android config khi dùng Firebase thật |
| Backend Node.js/Express | API, xác thực, giao dịch, OTP, tích hợp | Firebase Admin; các dịch vụ còn lại tùy tính năng |
| Firebase Auth + Firestore | Tài khoản, phiên đăng nhập, dữ liệu | Emulator cho local hoặc Firebase project thử nghiệm |
| Face API | Xử lý ảnh khuôn mặt và embedding cho eKYC | Chạy bằng Docker; cần model InsightFace |
| S3 | Lưu ảnh/chứng cứ eKYC riêng tư | Cần cho luồng eKYC đầy đủ |
| Stripe | Tạo PaymentIntent và nhận webhook | Stripe test mode để thử thanh toán |
| Google Maps/Routes | Bản đồ và định tuyến tới chi nhánh | API key; có thể bỏ qua khi chạy phần cốt lõi |
| OpenAI | Chatbot tùy chọn | API key chỉ khi bật chatbot |

## Yêu cầu cài đặt

- Git và Android Studio; cài Android SDK **API 37** và tạo Android Emulator.
- JDK **25** để chạy Gradle wrapper của dự án.
- Node.js **22 trở lên** và npm.
- Docker Desktop có Docker Compose.
- Firebase CLI và Java nếu chạy Firebase Emulator. Đăng nhập CLI bằng `firebase login` khi cần deploy rules hoặc dùng project.
- Stripe CLI nếu muốn nhận webhook thanh toán trên máy.

## 1. Chạy local với Firebase Emulator

Cấu hình này không cần tài khoản AWS, Stripe, Maps hay OpenAI. Firebase Emulator chứa dữ liệu cục bộ và phù hợp để làm việc với dữ liệu seed.

### 1.1 Chuẩn bị biến môi trường

Tại thư mục gốc, sao chép `.env.example` thành `.env`:

```powershell
Copy-Item .env.example .env
```

Mở `.env` và đặt các giá trị local tương ứng:

```dotenv
APP_MODE=emulator
FIREBASE_PROJECT_ID=demo-mobile-banking
FIREBASE_AUTH_EMULATOR_HOST=127.0.0.1:19099
FIRESTORE_EMULATOR_HOST=127.0.0.1:18080
HOST=127.0.0.1
PORT=3000
ANDROID_BACKEND_URL=http://10.0.2.2:3000
OTP_SECRET=<chuỗi-ngẫu-nhiên-tối-thiểu-32-ký-tự>
DEMO_PASSWORD=<mật-khẩu-thử-nghiệm-tối-thiểu-12-ký-tự>
```

Tạo secret ngẫu nhiên bằng PowerShell:

```powershell
[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
```

Thay các giá trị trong dấu `<...>`; không giữ dấu ngoặc. `10.0.2.2` là địa chỉ để Android Emulator truy cập máy host. Android app hiện dùng cổng Auth `19099` và Firestore `18080`, vì vậy hãy dùng đúng file cấu hình emulator bên dưới. `firebase.json` mặc định có cổng khác.

Backend đọc `backend/.env` trước rồi mới đọc `.env` ở root. Nếu `backend/.env` đã tồn tại, hãy bảo đảm các biến trùng tên có giá trị tương thích; file backend được ưu tiên.

### 1.2 Khởi động Firebase Emulator

Mở terminal tại root và chạy:

```powershell
firebase emulators:start --project demo-mobile-banking --config firebase.backend-test.json --only auth,firestore
```

Giữ terminal này mở. Emulator Auth dùng cổng `19099`, Firestore dùng `18080`.

### 1.3 Khởi động backend và tạo dữ liệu thử nghiệm

Mở terminal thứ hai:

```powershell
Set-Location backend
npm ci
npm run build
npm run seed
npm run seed -- --apply
npm run dev
```

Lệnh seed đầu chỉ xem trước thay đổi; lệnh có `--apply` mới ghi dữ liệu vào Firebase đang cấu hình. Backend chạy tại `http://127.0.0.1:3000`. Tài khoản seed dùng các địa chỉ `local_demo.customer@example.com`, `local_demo.recipient@example.com` và `local_demo.officer@example.com`; mật khẩu là giá trị `DEMO_PASSWORD`.

### 1.4 Chạy Android

Mở project ở Android Studio, chọn Android Emulator rồi Run cấu hình `app`. Hoặc tại root chạy:

```powershell
.\gradlew.bat assembleDebug -PfirebaseDemo=true -PbackendBaseUrl=http://10.0.2.2:3000
.\gradlew.bat installDebug -PfirebaseDemo=true -PbackendBaseUrl=http://10.0.2.2:3000
```

Đây là cấu hình Firebase demo/local; không cần `app/google-services.json`. Với Android Emulator, backend phải được truy cập qua `10.0.2.2`, không phải `127.0.0.1`.

## 2. Chạy cấu hình đầy đủ bằng Docker và dịch vụ thử nghiệm

Docker Compose khởi động **backend và Face API**; nó không khởi động Firebase Emulator, không tạo bucket S3 và không chạy Stripe CLI. Hãy chuẩn bị các dịch vụ dưới đây trước.

### 2.1 Firebase project thử nghiệm

1. Tạo Firebase project dành riêng cho development/test.
2. Bật **Email/Password** trong Firebase Authentication và tạo Firestore database.
3. Thêm Android app với application ID `com.example.final_term`, tải `google-services.json` và đặt tại `app/google-services.json`.
4. Tạo service account có quyền cần thiết cho Firebase Admin SDK; tải JSON key về `backend/firebase-admin.json`. Giữ file này ngoài Git.
5. Triển khai Firestore rules và indexes từ root:

```powershell
firebase login
firebase use <FIREBASE_PROJECT_ID>
firebase deploy --only firestore:rules,firestore:indexes
```

Không dùng project production cho đồ án hoặc dữ liệu seed. Tham khảo [Firebase Android setup](https://firebase.google.com/docs/android/setup), [Email/Password Auth](https://firebase.google.com/docs/auth/android/password-auth), [Admin SDK](https://firebase.google.com/docs/admin/setup) và [deploy Firestore rules](https://firebase.google.com/docs/rules/manage-deploy).

### 2.2 S3 riêng tư cho eKYC

Tạo bucket S3 ở region phù hợp và:

- Bật toàn bộ **Block Public Access**, không tạo public ACL/policy.
- Bật versioning; backend cần version ID để quản lý chứng cứ eKYC.
- Tạo IAM user/role riêng với quyền tối thiểu trên bucket và prefix `ekyc/` cần dùng.
- Chuẩn bị AWS access key/secret cho môi trường local. Không đưa key vào Git hoặc Android app.

Đọc thêm hướng dẫn AWS về [Block Public Access](https://docs.aws.amazon.com/AmazonS3/latest/userguide/access-control-block-public-access.html) và [S3 versioning](https://docs.aws.amazon.com/AmazonS3/latest/userguide/Versioning.html). Cấu hình eKYC đầy đủ cần `S3_BUCKET` và `AWS_REGION`.

### 2.3 Face API và model eKYC

Face API chạy trong Docker Compose. Model InsightFace không nằm trong Git; tải gói `buffalo_l` từ [InsightFace model zoo](https://github.com/deepinsight/insightface/releases/download/model-zoo/buffalo_l.zip), giải nén để có cấu trúc:

```text
backend/private-face-model/models/buffalo_l/det_10g.onnx
backend/private-face-model/models/buffalo_l/w600k_r50.onnx
```

Kiểm tra quyền sử dụng model theo [điều khoản cấp phép InsightFace](https://www.insightface.ai/solutions/face-recognition-licensing). Model weights và thư mục chứa model được loại khỏi Git. Backend bật face verification khi có `FACE_API_BASE_URL` và `FACE_TEMPLATE_KEY_HEX`; Compose tự đặt URL nội bộ `http://face-api:8000`.

### 2.4 Stripe test mode

Tạo tài khoản Stripe, bật test mode và lấy publishable key (`pk_test_...`) cùng secret key (`sk_test_...`). Cài và đăng nhập [Stripe CLI](https://docs.stripe.com/stripe-cli), sau đó chạy listener ở terminal riêng:

```powershell
stripe login
stripe listen --events payment_intent.succeeded,payment_intent.payment_failed,payment_intent.canceled --forward-to http://127.0.0.1:3001/webhooks/stripe
```

CLI in ra signing secret `whsec_...`; đưa secret đó vào `backend/.env`, rồi tạo lại backend container như hướng dẫn bên dưới. Giữ listener hoạt động để Stripe có thể gửi webhook tới backend. Dùng thẻ thử nghiệm Stripe, ví dụ `4242 4242 4242 4242`, ngày hết hạn tương lai và CVC bất kỳ.

### 2.5 Google Maps và Routes (tùy chọn)

Trong Google Cloud Console, bật billing và **Maps SDK for Android**; tạo Android key giới hạn theo package `com.example.final_term` và SHA-1 của signing certificate. Đặt key vào `MAPS_API_KEY` ở `.env` root. Nếu cần chỉ đường, bật **Routes API**, tạo key riêng phía server và đặt `GOOGLE_ROUTES_API_KEY` ở `backend/.env`. Không dùng chung một key không giới hạn cho Android và backend. Xem [lấy Maps Android key](https://developers.google.com/maps/documentation/android-sdk/get-api-key), [bảo vệ API keys](https://developers.google.com/maps/api-security-best-practices) và [Routes API](https://developers.google.com/maps/documentation/routes/get-api-key).

### 2.6 Chatbot (tùy chọn)

Chỉ khi muốn bật chatbot, tạo OpenAI API key và cấu hình `CHATBOT_ENABLED=true`, `OPENAI_API_KEY` cùng `OPENAI_MODEL` trong `backend/.env`. Nếu không dùng chatbot, để `CHATBOT_ENABLED=false`.

### 2.7 Tạo file môi trường

Root `.env` được Gradle đọc cho cấu hình Android và Compose đọc cho đường dẫn mount. Backend `.env` là cấu hình runtime của Node API. Hai file có mục đích khác nhau; Compose nạp `backend/.env` vào container.

Tạo `.env` root từ mẫu và đặt tối thiểu:

```dotenv
APP_MODE=connected
FIREBASE_PROJECT_ID=<FIREBASE_PROJECT_ID>
ANDROID_BACKEND_URL=http://10.0.2.2:3001
STRIPE_PUBLISHABLE_KEY=pk_test_<giá-trị>
MAPS_API_KEY=<Android-Maps-key>
FIREBASE_ADMIN_KEY_PATH=./backend/firebase-admin.json
FACE_MODEL_PATH=./backend/private-face-model
```

Tạo `backend/.env` với các biến sau (giữ lại biến khác từ cấu hình hiện có nếu cần):

```dotenv
APP_MODE=connected
FIREBASE_PROJECT_ID=<FIREBASE_PROJECT_ID>
HOST=0.0.0.0
PORT=3000
GOOGLE_APPLICATION_CREDENTIALS=/run/secrets/firebase-admin.json
OTP_SECRET=<chuỗi-ngẫu-nhiên-tối-thiểu-32-ký-tự>
DEMO_PASSWORD=<mật-khẩu-thử-nghiệm-tối-thiểu-12-ký-tự>
DEMO_NAMESPACE=local_demo
CONFIRM_SEED_PROJECT=<FIREBASE_PROJECT_ID>
AWS_REGION=<AWS_REGION>
S3_BUCKET=<bucket-riêng-tư>
AWS_ACCESS_KEY_ID=<access-key>
AWS_SECRET_ACCESS_KEY=<secret-key>
FACE_TEMPLATE_KEY_HEX=<chuỗi-hex-ngẫu-nhiên-64-ký-tự>
STRIPE_SECRET_KEY=sk_test_<giá-trị>
STRIPE_WEBHOOK_SECRET=whsec_<giá-trị-từ-stripe-listen>
GOOGLE_ROUTES_API_KEY=<server-side-Routes-key>
CHATBOT_ENABLED=false
```

Sinh `OTP_SECRET` và `FACE_TEMPLATE_KEY_HEX` bằng lệnh PowerShell ở trên; cả hai cần secret riêng. Không đặt `FACE_API_BASE_URL` trỏ về `localhost`: Compose đã cấu hình URL nội bộ tới service Face API. `FACE_TEMPLATE_KEY_HEX` cần đúng định dạng hex 32 bytes. Không commit `.env`, service account JSON, AWS keys, Stripe keys hoặc model weights.

### 2.8 Seed dữ liệu và khởi động Docker

Seed sẽ ghi tài khoản/dữ liệu thử nghiệm vào Firebase project đã chọn. Chỉ chạy với project test. `CONFIRM_SEED_PROJECT` phải trùng `FIREBASE_PROJECT_ID` để cho phép áp dụng.

```powershell
Set-Location backend
$env:GOOGLE_APPLICATION_CREDENTIALS = (Resolve-Path .\firebase-admin.json).Path
npm ci
npm run build
npm run seed
npm run seed -- --apply
Set-Location ..
docker compose up -d --build face-api backend
docker compose ps
```

Khi seed từ máy host, dòng `$env:GOOGLE_APPLICATION_CREDENTIALS` trỏ tới file JSON trên máy; Compose dùng cùng file nhưng mount nó tại `/run/secrets/firebase-admin.json` bên trong container. Nếu chạy lệnh từ bash/macOS/Linux, dùng `export GOOGLE_APPLICATION_CREDENTIALS="$(pwd)/firebase-admin.json"` trong thư mục `backend`.

Docker backend publish cổng host `3001` tới cổng container `3000`; Face API publish `8000`. Kiểm tra API:

```powershell
Invoke-RestMethod http://127.0.0.1:3001/health
```

`/health` xác nhận tiến trình HTTP phản hồi; nó không đảm bảo Firebase, S3, Stripe, model hoặc camera đã sẵn sàng. Theo dõi log bằng `docker compose logs -f backend face-api`.

### 2.9 Chạy Android với backend Docker

Đảm bảo `app/google-services.json` đã có, `.env` root đặt `APP_MODE=connected` và `ANDROID_BACKEND_URL=http://10.0.2.2:3001`, sau đó:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat installDebug
```

Hoặc mở project bằng Android Studio và chạy app trên emulator. Với thiết bị Android thật qua USB, backend trên máy có thể được chuyển tiếp bằng:

```powershell
.\scripts\connect-android-usb.ps1
```

Chạy lại lệnh sau khi ngắt/kết nối lại thiết bị hoặc khởi động lại ADB. Thiết bị thật không truy cập được địa chỉ `10.0.2.2`; với USB reverse, build app dùng backend URL `http://127.0.0.1:3001` (đặt `ANDROID_BACKEND_URL` trong `.env` root trước khi build).

Tài khoản seed đăng nhập: `local_demo.customer@example.com`, `local_demo.recipient@example.com`, `local_demo.officer@example.com`; mật khẩu là `DEMO_PASSWORD` trong `backend/.env`. Đăng xuất rồi đăng nhập lại sau khi seed nếu claims/role vừa được cập nhật.

**Giới hạn xác thực:** OTP giao dịch hiện dùng chế độ `DEMO_DISPLAY_CODE` để hiển thị mã trong luồng app, không gửi qua một kênh độc lập như SMS/email và không phải 2FA production. Face step-up cũng là luồng demo, không thay thế quy trình xác minh danh tính/liveness được chứng nhận.

## Xác nhận các endpoint

- API health: `http://127.0.0.1:3000/health` khi chạy backend trực tiếp hoặc `http://127.0.0.1:3001/health` qua Docker.
- OpenAPI: `/openapi.json` trên cùng host/port.
- Runtime capabilities: `/capabilities` trên cùng host/port.
- Face API trong Docker: `http://127.0.0.1:8000/health`.

## Dừng hệ thống

- Backend chạy trực tiếp, Firebase Emulator hoặc Stripe CLI: nhấn `Ctrl+C` ở terminal tương ứng.
- Docker services: `docker compose down` tại root. Lệnh này giữ dữ liệu Firebase cloud và S3; nó không xóa các tài nguyên bên ngoài.

## Khắc phục sự cố thường gặp

| Triệu chứng | Kiểm tra |
|---|---|
| Android không gọi được backend | Emulator dùng `10.0.2.2`; backend npm dùng cổng `3000`, Docker dùng cổng host `3001`. Kiểm tra `ANDROID_BACKEND_URL`. |
| Firebase Emulator không kết nối | Khởi động bằng `firebase.backend-test.json`; Auth/Firestore phải ở `19099`/`18080`. Kiểm tra cả root `.env` và `backend/.env`. |
| Android connected không khởi động Firebase | Kiểm tra `app/google-services.json`, application ID và Firebase project có trùng nhau không. |
| Login được nhưng thiếu quyền/role | Chạy seed đúng project, đăng xuất rồi đăng nhập lại để lấy token claims mới. |
| eKYC báo thiếu cấu hình | Kiểm tra S3 bucket/versioning/credentials, hai file ONNX, `FACE_TEMPLATE_KEY_HEX` và log của `face-api`. |
| Thanh toán chờ hoặc chưa cập nhật | Giữ Stripe CLI listener chạy, kiểm tra webhook secret và endpoint `/webhooks/stripe`; trạng thái cập nhật dựa trên webhook. |
| Bản đồ không tải hoặc định tuyến thiếu | Kiểm tra billing, API đã bật, package/SHA-1 restriction và dùng đúng key Android hoặc Routes server. |

## Tài liệu liên quan

- [Backend setup và API](backend/README.md)
- [Firebase setup](docs/firebase-setup.md)
- [Hướng dẫn seed dữ liệu](scripts/seed-demo-data.md)
- [Đối chiếu database template](docs/database-template-alignment.md)
- [Các khoảng trống chức năng](docs/functional-gaps.md)
- [Firestore rules](firestore.rules) và [Firestore indexes](firestore.indexes.json)

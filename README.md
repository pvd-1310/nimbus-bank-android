# Nimbus Bank Android

Ứng dụng ngân hàng cho đồ án, gồm Android app, backend Node.js, Firebase Authentication/Firestore và các tích hợp tùy chọn cho thanh toán, eKYC và bản đồ. Tài liệu này hướng dẫn từ máy mới đến khi đăng nhập được bằng tài khoản mẫu.

> Chỉ sử dụng dữ liệu giả và dịch vụ thử nghiệm. OTP giao dịch hiển thị ngay trong app là cơ chế demo, không phải xác thực hai yếu tố độc lập. Chức năng eKYC hiện chưa kiểm tra tính thật của CCCD, chưa đối chiếu ảnh trên thẻ với selfie và chưa có liveness.

## 1. Chọn cách chạy

| | A. Local với Firebase Emulator | B. Firebase test và Docker |
|---|---|---|
| Dùng khi | Mới làm quen, phát triển chức năng cơ bản | Cần dữ liệu Firebase test và tích hợp dịch vụ ngoài |
| Firebase | Auth và Firestore chạy trên máy | Một Firebase project test riêng |
| Backend | Node.js chạy trên máy, cổng `3000` | Docker, cổng host `3001` |
| Android | Android Emulator | Android Emulator hoặc điện thoại USB |
| Cần tài khoản dịch vụ | Không cần Firebase, AWS, Stripe hoặc FPT.AI | Cần Firebase; các dịch vụ khác tùy chức năng |

**Nên bắt đầu với cách A.** Làm xong mục 2, tiếp tục mục 3 cho cách A hoặc mục 4 cho cách B. Các lệnh bên dưới dùng **PowerShell trên Windows**; đường dẫn tương đối tính từ thư mục gốc repository trừ khi có hướng dẫn khác.

## 2. Chuẩn bị máy

1. Cài [Git](https://git-scm.com/downloads), [Node.js 22 trở lên](https://nodejs.org/) và [Android Studio](https://developer.android.com/studio).
2. Trong Android Studio, mở **SDK Manager** và cài Android SDK Platform **API 37**, Android SDK Build-Tools, Platform-Tools và Android Emulator. Tạo một thiết bị ảo trong **Device Manager**; app yêu cầu Android API 26 trở lên.
3. Cài **JDK 25** cho Gradle và cấu hình Gradle JDK trong Android Studio. Dự án đã có Gradle wrapper, không cần cài Gradle riêng. Firebase Emulator cũng cần Java.
4. Cài [Firebase CLI](https://firebase.google.com/docs/cli) để chạy Firebase Emulator hoặc triển khai rules. Với npm, có thể chạy `npm install -g firebase-tools`. Chỉ cần [Docker Desktop](https://www.docker.com/products/docker-desktop/) cho cách B.
5. Clone repository bằng URL do chủ dự án cung cấp, rồi mở thư mục vừa clone trong Android Studio:

   ```powershell
   git clone <URL-repository>
   Set-Location <thu-muc-repository>
   ```

   Thay nội dung trong `<...>` bằng giá trị thực, không nhập dấu ngoặc. Nếu đã có source, chỉ cần mở terminal tại thư mục chứa `README.md` này.

6. Kiểm tra các công cụ trong terminal mới:

   ```powershell
   node --version
   npm --version
   java -version
   firebase --version
   ```

   Với cách B, mở Docker Desktop và kiểm tra thêm `docker compose version`. Android Studio tạo `local.properties` trỏ tới Android SDK trên máy; không sao chép đường dẫn SDK của người khác.

### File cấu hình và thứ tự ưu tiên

Copy `.env.example` thành `.env` ở root cho cấu hình máy mới. `.env` root được Gradle đọc để đưa URL backend, Maps key và Stripe publishable key vào app; Docker Compose cũng đọc file này cho đường dẫn mount. Backend Node đọc **`backend/.env` trước, sau đó `.env` root**; biến môi trường đã có trong terminal/container được ưu tiên hơn cả hai file. Docker Compose nạp `backend/.env` cho service backend và đặt một số biến riêng trong `docker-compose.yaml`.

Khi đổi chế độ, kiểm tra cả hai file và các biến trong terminal; không để cấu hình emulator cũ ghi đè cấu hình connected. Các file `.env`, credentials và `google-services.json` là cấu hình cục bộ: không commit hoặc gửi key vào chat.

## 3. Cách A — chạy local với Firebase Emulator

### Bước 1: Tạo `.env`

Tại root, sao chép mẫu nếu chưa có file:

```powershell
Copy-Item .env.example .env
```

Mở `.env`, kiểm tra hoặc sửa các giá trị sau:

```dotenv
APP_MODE=emulator
FIREBASE_PROJECT_ID=demo-mobile-banking
FIREBASE_AUTH_EMULATOR_HOST=127.0.0.1:19099
FIRESTORE_EMULATOR_HOST=127.0.0.1:18080
HOST=127.0.0.1
PORT=3000
ANDROID_BACKEND_URL=http://10.0.2.2:3000
OTP_SECRET=<secret-ngau-nhien-it-nhat-32-ky-tu>
DEMO_NAMESPACE=demo
DEMO_PASSWORD=<mat-khau-demo-it-nhat-12-ky-tu>
```

Tạo một secret ngẫu nhiên bằng lệnh dưới và dán kết quả vào `OTP_SECRET`; tự đặt `DEMO_PASSWORD` và lưu lại trên máy:

```powershell
[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
```

Không giữ giá trị ví dụ trong `.env.example`. Nếu đã có `backend/.env`, sửa hoặc tạm đổi tên file đó để tránh nó ưu tiên cấu hình khác. Cách A không cần `app/google-services.json`.

### Bước 2: Chạy Firebase Emulator

Ở terminal thứ nhất tại root:

```powershell
firebase emulators:start --project demo-mobile-banking --config firebase.backend-test.json --only auth,firestore
```

Giữ terminal mở. Đợi Auth ở `127.0.0.1:19099` và Firestore ở `127.0.0.1:18080` sẵn sàng. Dùng đúng `firebase.backend-test.json`: cấu hình mặc định `firebase.json` có thể dùng cổng khác. Đây là dữ liệu emulator cục bộ; lần chạy hướng dẫn này không cấu hình lưu dữ liệu qua các lần khởi động lại.

### Bước 3: Cài, seed và chạy backend

Mở terminal thứ hai tại root:

```powershell
Set-Location backend
npm ci
npm run build
npm run seed
npm run seed -- --apply
npm run dev
```

Lệnh `seed` đầu tiên chỉ in project, namespace và tài khoản dự kiến. Kiểm tra project là `demo-mobile-banking`, mode là `emulator`, namespace là `demo` trước khi chạy lệnh có `--apply`. Giữ terminal mở khi backend đã khởi động.

Ở terminal khác, kiểm tra:

```powershell
Invoke-RestMethod http://127.0.0.1:3000/health
```

Kết quả mong đợi có `status: ok`. Endpoint này chỉ xác nhận tiến trình HTTP phản hồi, không kiểm tra toàn bộ dịch vụ bên ngoài.

### Bước 4: Chạy Android Emulator

Khởi động thiết bị ảo trong Android Studio. Chờ thiết bị khởi động xong, sau đó mở project root và Run cấu hình `app`. Hoặc chạy tại root:

```powershell
.\gradlew.bat assembleDebug -PfirebaseDemo=true -PbackendBaseUrl=http://10.0.2.2:3000
.\gradlew.bat installDebug -PfirebaseDemo=true -PbackendBaseUrl=http://10.0.2.2:3000
```

`10.0.2.2` là địa chỉ Android Emulator dùng để truy cập máy host. Bản local có application ID `com.example.final_term.localreview`, có thể cài cạnh bản connected. Firebase Emulator của app đang cố định tại `10.0.2.2:19099` và `10.0.2.2:18080`, nên các bước này dành cho Android Emulator; dùng cách B nếu muốn chạy trên điện thoại thật.

### Bước 5: Đăng nhập và thử

| Email | Vai trò | Gợi ý thử |
|---|---|---|
| `demo.customer@example.com` | Khách hàng chính | Xem tài khoản, lịch sử và tiện ích |
| `demo.recipient@example.com` | Khách hàng nhận | Người nhận chuyển tiền nội bộ |
| `demo.peer@example.com` | Khách hàng | Khách hàng mẫu thứ ba |
| `demo.officer@example.com` | Nhân viên | Xem chức năng nhân viên |

Mật khẩu của tài khoản mới là `DEMO_PASSWORD` trong `.env`. Đăng xuất để đổi vai trò. Nếu đổi `DEMO_NAMESPACE`, tiền tố email/UID đổi theo; namespace phải gồm 3–30 ký tự chữ thường, số hoặc `_`.

Để thử chuyển khoản nội bộ, đăng nhập khách hàng chính, chọn tài khoản thanh toán và tra cứu người nhận bằng số điện thoại mẫu `0907364000` của khách hàng recipient. Kiểm tra tên/số tiền trước khi xác nhận. Với giao dịch demo cần OTP, app hiển thị mã để tiếp tục. Số dư và các giao dịch trong emulator là dữ liệu thử nghiệm.

Cách A không tự cấu hình S3, Face API, FPT.AI, Stripe hay Maps. Các tính năng phụ thuộc chúng sẽ báo chưa khả dụng; bản Firebase demo cũng không gắn Maps key vào Android. Xem mục 5 khi cần thử các tích hợp đó trong cấu hình connected.

## 4. Cách B — Firebase test và Docker

### Bước 1: Tạo Firebase project test

1. Trong [Firebase Console](https://console.firebase.google.com/), tạo project riêng cho phát triển/test; ghi lại **Project ID**.
2. Bật **Authentication → Sign-in method → Email/Password** và tạo **Cloud Firestore**.
3. Thêm Android app với package `com.example.final_term`. Tải `google-services.json` và đặt tại `app/google-services.json`.
4. Trong cài đặt project, tạo service-account key cho Firebase Admin và lưu cục bộ tại `backend/firebase-admin.json`. Tài khoản này cần quyền phù hợp để quản lý Auth và Firestore của project test.
5. Đăng nhập Firebase CLI, chọn đúng project và deploy rules/indexes từ root:

   ```powershell
   firebase login
   firebase use <FIREBASE_PROJECT_ID>
   firebase deploy --only firestore:rules,firestore:indexes
   ```

   Đợi indexes hoàn tất trước khi thử các danh sách. Đảm bảo `google-services.json`, service account và Project ID đều thuộc **cùng project test**.

### Bước 2: Cấu hình Android và đường dẫn Docker

Nếu chưa có `.env` root, copy từ `.env.example`. Sửa các giá trị sau trong `.env` root:

```dotenv
APP_MODE=connected
FIREBASE_PROJECT_ID=<FIREBASE_PROJECT_ID>
ANDROID_BACKEND_URL=http://10.0.2.2:3001
FIREBASE_ADMIN_KEY_PATH=./backend/firebase-admin.json
FACE_MODEL_PATH=./backend/private-face-model
```

**Xóa hoặc comment cả hai dòng** `FIREBASE_AUTH_EMULATOR_HOST` và `FIRESTORE_EMULATOR_HOST` từ file mẫu khi chuyển sang connected. `ANDROID_BACKEND_URL` phía trên dành cho Android Emulator; nếu dùng điện thoại USB, đổi theo bước 5 trước khi build. Key Android tùy chọn (`MAPS_API_KEY`, `STRIPE_PUBLISHABLE_KEY`) cũng đặt trong file root này.

Tạo `backend/.env` cho backend và Docker:

```dotenv
APP_MODE=connected
FIREBASE_PROJECT_ID=<FIREBASE_PROJECT_ID>
HOST=127.0.0.1
PORT=3001
OTP_SECRET=<secret-ngau-nhien-it-nhat-32-ky-tu>
DEMO_NAMESPACE=demo
DEMO_PASSWORD=<mat-khau-demo-it-nhat-12-ky-tu>
CONFIRM_SEED_PROJECT=<FIREBASE_PROJECT_ID>
```

Không đặt hai biến emulator host trong `backend/.env`. Tạo `OTP_SECRET` bằng lệnh ở mục 3 và tự đặt mật khẩu demo. `CONFIRM_SEED_PROJECT` phải trùng chính xác Project ID khi áp dụng seed lên Firebase connected. Docker Compose tự đặt `HOST=0.0.0.0`, `PORT=3000` và đường dẫn credential bên trong container; cổng truy cập từ máy host vẫn là `3001`.

### Bước 3: Cài dependency và seed Firebase test

Ở terminal tại root:

```powershell
Set-Location backend
$env:GOOGLE_APPLICATION_CREDENTIALS = (Resolve-Path .\firebase-admin.json).Path
npm ci
npm run build
npm run seed
```

Đọc kết quả xem trước và xác nhận đúng **project test**, mode `connected` và namespace `demo`. Sau đó mới ghi dữ liệu mẫu:

```powershell
npm run seed -- --apply
Set-Location ..
```

Đường dẫn service-account ở trên dành cho tiến trình Node chạy **trên máy host**. Compose mount cùng file vào `/run/secrets/firebase-admin.json` bên trong container. Biến `$env:GOOGLE_APPLICATION_CREDENTIALS` chỉ tồn tại trong terminal hiện tại; mở terminal mới thì đặt lại trước khi chạy seed trên host.

Seed tạo tài khoản ở bảng mục 3.5 và dữ liệu checking/savings/mortgage, hóa đơn, sản phẩm, chi nhánh. Chạy lại giữ các trường và mật khẩu hiện có; lịch sử giao dịch mẫu chỉ thêm khi tài khoản chính chưa tồn tại. Script không có lệnh xóa/reset dữ liệu. Nếu tài khoản đã có, `DEMO_PASSWORD` không đổi mật khẩu của tài khoản đó. Namespace cũ không được tự động di chuyển hoặc xóa.

### Bước 4: Khởi động backend

Mở Docker Desktop, rồi tại root chạy:

```powershell
docker compose up -d --build backend
docker compose ps
Invoke-RestMethod http://127.0.0.1:3001/health
```

Backend dùng Firebase test và phản hồi `status: ok`. Service `face-api` chưa bắt buộc cho bước đăng nhập; cấu hình model ở mục 5.2 khi cần eKYC. Xem log bằng `docker compose logs -f backend`.

### Bước 5: Cài app

**Android Emulator:** giữ `ANDROID_BACKEND_URL=http://10.0.2.2:3001` trong `.env` root. Mở project trong Android Studio, chọn emulator và Run `app`, hoặc dùng:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat installDebug
```

**Điện thoại Android thật qua USB:** bật Developer options và USB debugging, kết nối cáp, xác nhận hộp thoại cho phép debug trên điện thoại. Đổi `ANDROID_BACKEND_URL` trong `.env` root thành `http://127.0.0.1:3001`, rồi chạy tại root:

```powershell
.\scripts\connect-android-usb.ps1
.\gradlew.bat assembleDebug
.\gradlew.bat installDebug
```

Script thiết lập `adb reverse tcp:3001 tcp:3001`; chạy lại sau khi rút cáp, kết nối lại hoặc khởi động lại ADB. Nếu có nhiều điện thoại, chạy `adb devices` và truyền `-Serial <serial>` cho script. URL được đóng vào app khi build: sau khi sửa URL, **build và cài lại app**.

Mở app, đăng nhập bằng một trong các email ở mục 3.5 và mật khẩu đã dùng khi tạo tài khoản. Nếu seed vừa bổ sung role/claims, đăng xuất rồi đăng nhập lại để lấy token mới.

## 5. Bật các chức năng tích hợp

Các bước này dùng project và tài khoản **test**, thực hiện sau mục 4. Đặt secret phía máy chủ vào `backend/.env`; sau khi sửa file, chạy `docker compose up -d --build backend` để tạo lại container với cấu hình mới. Không đưa secret backend vào Android hoặc Git.

### 5.1 Bản đồ và đường đi bộ

1. Trong Google Cloud project phù hợp, bật billing và **Maps SDK for Android**. Tạo Android API key giới hạn theo package `com.example.final_term` và SHA-1 của signing certificate; xem [hướng dẫn lấy key](https://developers.google.com/maps/documentation/android-sdk/get-api-key). Có thể xem SHA-1 của debug build bằng `./gradlew.bat signingReport`.
2. Đặt `MAPS_API_KEY=<android-maps-key>` trong `.env` root. Build/cài lại app sau khi sửa.
3. Để tìm tuyến đi bộ, tạo key trên [HeiGIT / openrouteservice](https://account.heigit.org/) và đặt `OPENROUTESERVICE_API_KEY=<key>` trong `backend/.env`. Backend ưu tiên key này nếu có; nếu không, có thể cấu hình `GOOGLE_ROUTES_API_KEY` cho Google Routes API.
4. Tạo lại backend, mở bản đồ chi nhánh và cấp quyền vị trí. App cần dữ liệu chi nhánh có tọa độ và kết nối tới nhà cung cấp chỉ đường. Backend so sánh tối đa 20 chi nhánh; nếu nhiều hơn, người dùng phải chọn chi nhánh.

Đường đi openrouteservice và nền Google Maps đến từ hai nhà cung cấp; kiểm tra điều khoản và yêu cầu ghi nguồn áp dụng trước khi phát hành. Hạn mức và khả năng hoạt động phụ thuộc tài khoản/provider.

### 5.2 eKYC: CCCD, FPT.AI, S3 và khuôn mặt

Luồng `FULL_ID` cần đủ các thành phần sau:

1. **S3:** tạo bucket test private, bật Block Public Access và **versioning**. Cấu hình `AWS_REGION` và `S3_BUCKET` trong `backend/.env`; cấp AWS credentials cho container với quyền giới hạn prefix `ekyc/` để tải lên, đọc object/version và dọn object version. `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` là một cách cấu hình local; không dùng root credentials. Chi tiết quyền và retention ở [backend README](backend/README.md).
2. **FPT.AI:** tạo key cho ID Recognition theo [tài liệu FPT.AI](https://docs.fpt.ai/docs/vi/vision/api/id-recognition/) và đặt `FPT_AI_KEY=<key>` trong `backend/.env`. Ảnh mặt trước CCCD được backend gửi tới FPT.AI để trích xuất; người dùng kiểm tra và xác nhận thông tin trước bước selfie. Cần key hợp lệ, hạn mức và kết nối mạng.
3. **Face API:** lấy model `buffalo_l` từ [InsightFace Model Zoo](https://github.com/deepinsight/insightface/tree/master/model_zoo) và kiểm tra quyền sử dụng weights. Đặt **chính xác hai file ONNX** vào:

   ```text
   backend/private-face-model/models/buffalo_l/
   ├── det_10g.onnx
   └── w600k_r50.onnx
   ```

   Không để thêm file `.onnx` khác trong thư mục này. Model không nằm trong Git. Nếu đặt ở nơi khác, sửa `FACE_MODEL_PATH` trong `.env` root.
4. Tạo một secret riêng bằng lệnh ở mục 3, đặt 64 ký tự hex vào `FACE_TEMPLATE_KEY_HEX` trong `backend/.env`. Giữ ổn định key này giữa các lần restart vì template khuôn mặt được mã hóa bằng key đó.
5. Khởi động lại:

   ```powershell
   docker compose up -d --build face-api backend
   docker compose ps
   Invoke-RestMethod http://127.0.0.1:8000/health
   ```

   Face API trả `status: ready` khi nạp model. Nếu trả `503`/`setup_required`, xem `docker compose logs face-api` và kiểm tra model path/tên file. Trên Android, vào hồ sơ → eKYC, đồng ý, chụp hai mặt CCCD, kiểm tra thông tin, quét selfie và nộp.

`EKYC_DEMO_SELFIE_ONLY=true` trong `backend/.env` là chế độ chỉ đăng ký selfie, không OCR hay xác minh CCCD; chế độ này vẫn cần S3 và Face API. Để trống hoặc đặt `false` cho `FULL_ID`. Chỉ thử với ảnh có quyền sử dụng; kết quả OCR có thể sai và cần người dùng xác nhận.

### 5.3 Stripe thanh toán thử nghiệm

1. Tạo tài khoản [Stripe](https://dashboard.stripe.com/) và chọn **test mode**. Lấy publishable key `pk_test_...` và secret key `sk_test_...`.
2. Đặt `STRIPE_PUBLISHABLE_KEY=pk_test_...` vào `.env` root và `STRIPE_SECRET_KEY=sk_test_...` vào `backend/.env`. Dự án chỉ chấp nhận test keys cho checkout.
3. Cài [Stripe CLI](https://docs.stripe.com/stripe-cli), mở terminal riêng và chạy:

   ```powershell
   stripe login
   stripe listen --events payment_intent.succeeded,payment_intent.payment_failed,payment_intent.canceled --forward-to http://127.0.0.1:3001/webhooks/stripe
   ```

4. Copy webhook signing secret `whsec_...` do CLI in ra vào `STRIPE_WEBHOOK_SECRET` trong `backend/.env`. Tạo lại backend, build/cài lại app để nạp publishable key và giữ Stripe CLI chạy khi thử. Dùng [thẻ test Stripe](https://docs.stripe.com/testing), không dùng thẻ thật.

Webhook phải tới backend và xác thực thành công để đồng bộ kết quả. Các chức năng Stripe Sandbox liên kết tài khoản có hướng dẫn riêng trong [backend README](backend/README.md).

### 5.4 Chatbot

Nếu muốn dùng chatbot với OpenAI, đặt `CHATBOT_ENABLED=true`, `OPENAI_API_KEY` và `OPENAI_MODEL` trong `backend/.env`, rồi tạo lại backend. Nếu không dùng, để `CHATBOT_ENABLED=false`. Chế độ emulator còn có thể bật chatbot hướng dẫn cục bộ bằng `CHATBOT_LOCAL_DEMO=true`; chế độ này không cần OpenAI key.

## 6. Kiểm tra, dừng và xử lý lỗi

| Cần kiểm tra | Cách thực hiện |
|---|---|
| Backend Node local | `Invoke-RestMethod http://127.0.0.1:3000/health` |
| Backend Docker | `Invoke-RestMethod http://127.0.0.1:3001/health` |
| API contract | Mở `/openapi.json` trên cùng host và cổng backend |
| Tình trạng các tích hợp | `GET /capabilities` với Firebase bearer token; `ENABLED` không bảo đảm quota hoặc credential còn hợp lệ |
| Face API | `Invoke-RestMethod http://127.0.0.1:8000/health` khi đã chạy service |
| Backend tests | `Set-Location backend`, sau đó `npm test` |
| Android unit tests | Tại root chạy `.\gradlew.bat testDebugUnitTest` |

Để dừng Node, Firebase Emulator hoặc Stripe CLI, nhấn `Ctrl+C` tại terminal tương ứng. Dừng Docker tại root bằng `docker compose down`; lệnh này không xóa Firebase cloud hay S3.

| Triệu chứng | Kiểm tra |
|---|---|
| App không kết nối backend | `/health` trên máy host; emulator dùng `10.0.2.2`, điện thoại USB dùng `127.0.0.1` qua `adb reverse`; build/cài lại sau khi đổi URL. |
| Điện thoại không hiện trong ADB | Cài Platform-Tools, bật USB debugging, chấp nhận thông báo trên điện thoại; xem `adb devices` và chạy lại script USB. |
| Đăng nhập local thất bại | Emulator còn chạy đúng cổng `19099`/`18080`; seed đã chạy với `--apply`; app là bản local và dùng đúng `DEMO_PASSWORD`. |
| Seed báo `CONFIRM_PROJECT_REQUIRED` | Cách B: đặt `CONFIRM_SEED_PROJECT` bằng đúng `FIREBASE_PROJECT_ID` trong cấu hình backend. |
| Seed báo `SEED_TARGET_NOT_DEMO`, `SEED_AUTH_EMAIL_MISMATCH` hoặc email đã tồn tại | Không xóa dữ liệu để thử lại. Kiểm tra namespace, project và tài khoản/document đang tồn tại; chọn project test hoặc namespace phù hợp. |
| Backend báo `EMULATORS_REQUIRED` / `EXPLICIT_PROJECT_REQUIRED` | Kiểm tra `APP_MODE`, project ID, hai emulator host và thứ tự ưu tiên `backend/.env`, root `.env`, biến terminal. |
| Connected app thiếu Firebase | Kiểm tra `app/google-services.json`, package `com.example.final_term` và đúng project; sync/build lại. |
| eKYC không đọc được CCCD | Kiểm tra `FPT_AI_KEY`, hạn mức/kết nối FPT.AI; dùng ảnh rõ toàn bộ mặt trước và xem log backend. Ảnh SVG minh họa không thay thế kiểm thử ảnh hợp lệ. |
| eKYC không nộp được | Kiểm tra S3 credentials/versioning, `FACE_TEMPLATE_KEY_HEX`, model và `/health` Face API. |
| Bản đồ hoặc tuyến không hiện | Kiểm tra Maps SDK key/package/SHA-1, billing, vị trí; kiểm tra routes key và backend logs. |
| Thanh toán không cập nhật | Kiểm tra test keys, Stripe CLI đang chạy, webhook secret và endpoint forward. |

## 7. Cấu trúc và tài liệu

```text
app/                 Android Java, XML, resources và tests
backend/             API TypeScript/Express, seed và tests
face-api/            Dịch vụ Python nhận diện khuôn mặt
firebase*.json       Cấu hình Firebase và emulator
firestore.rules      Quy tắc truy cập Firestore
docker-compose.yaml  Backend và Face API cho cấu hình Docker
scripts/             Tiện ích kết nối thiết bị
```

- [Backend: cấu hình, API và kiểm thử](backend/README.md)
- [Thiết lập Firebase](docs/firebase-setup.md)
- [Đối chiếu database template](docs/database-template-alignment.md)
- [Firestore rules](firestore.rules) và [indexes](firestore.indexes.json)

# Nimbus Bank backend

Backend TypeScript production-shaped cho Nimbus Bank. Firebase Admin xác thực bearer token, Firestore là nguồn dữ liệu authoritative, còn OTP/S3/Stripe được bảo vệ bằng capability checkpoints. Backend đã có contract REST để Android kết nối; deploy thật cần chọn hạ tầng HTTPS và cung cấp provider credentials ngoài repository.

## Tổ chức mã nguồn

```text
backend/
├── src/
│   ├── app.ts                 # Ghép middleware và đăng ký route
│   ├── server.ts              # Khởi động HTTP server
│   ├── core/                  # Cấu hình, phân quyền, context và Store contract
│   ├── infrastructure/        # Firestore và runtime composition
│   ├── shared/                # Lỗi, validation, phân trang và hashing
│   ├── database/              # Schema theo Word và logic migration
│   ├── docs/                  # OpenAPI
│   ├── scripts/               # Seed, migration, xử lý outbox
│   └── modules/
│       ├── accounts/          # Chi tiết checking/savings/mortgage, tính toán
│       ├── auth/              # Actor và xác minh Firebase token
│       ├── profile/           # Hồ sơ và preferences
│       ├── transactions/      # Lịch sử và bộ lọc
│       ├── requests/          # Yêu cầu nạp/rút
│       ├── transfers/         # Intent, OTP, commit và ledger
│       ├── notifications/     # Thông báo và đánh dấu đã đọc
│       ├── ekyc/              # Upload S3, automatic demo screening
│       ├── payments/          # Stripe, payment state và webhook
│       ├── orders/            # Catalog, đơn hàng và fulfillment
│       ├── officer/           # Khách hàng, provisioning và audit views
│       ├── rates/             # Chính sách lãi suất
│       ├── branches/          # Danh sách chi nhánh
│       └── capabilities/      # Trạng thái provider
├── test/support/             # MemoryStore chỉ dành cho test
└── scripts/build.mjs          # Dọn dist cũ và build TypeScript
```

Phase 2 tập trung vào `accounts`, `profile`, `transactions`, `requests` và `notifications`. Mỗi module giữ route/validation và service riêng khi có nghiệp vụ; provider S3/Stripe nằm cạnh module sử dụng. `Banking` trong `core/` là context dùng chung và composition của các service, không còn chứa toàn bộ nghiệp vụ tài khoản/chuyển khoản.

Các lệnh `npm run dev`, `start`, `seed`, `migrate`, `worker` giữ nguyên. Script đã được chuyển về `src/scripts/`; chỉ dùng lệnh npm thay vì gọi đường dẫn file cũ. `npm run build` tạo lại `dist/` để loại bỏ file biên dịch từ cây thư mục cũ. `noUnusedLocals` và `noUnusedParameters` được bật để chặn code/import thừa quay lại.

Refactor giữ nguyên endpoint, schema, các field tương thích và thứ tự xử lý raw Stripe webhook. Không chạy seed/migration hoặc thay đổi tài nguyên Firebase/S3 khi refactor.

## Connected production deployment

Không chạy production bằng emulator hoặc file seed demo. Trên deployment host:

```sh
cp .env.example .env
# Configure connected mode in the root .env before starting.
cd backend
npm ci
npm run build
npm start
```

`APP_MODE=connected`, `FIREBASE_PROJECT_ID` phải là project thật, `GOOGLE_APPLICATION_CREDENTIALS` phải trỏ tới Firebase Admin credential hoặc workload identity của runtime, và `OTP_SECRET` phải là secret manager value. Reverse proxy phải cung cấp HTTPS; không đưa service-account, OTP, AWS hoặc Stripe secret vào Android.

Container production:

```sh
docker build -t nimbus-bank-api ./backend
docker run --read-only --cap-drop=ALL --security-opt=no-new-privileges \
  -p 3000:3000 --env-file .env \
  -v /secure/firebase-admin.json:/run/secrets/firebase-admin.json:ro \
  nimbus-bank-api
```

Chỉ mở cổng qua reverse proxy/API gateway có TLS, rate limit, access log và secret injection. Chưa chạy lệnh deploy vì repository chưa chỉ định AWS/GCP/Cloud Run/Render/Fly hay domain production.

## Chạy local bằng emulator (chỉ dành cho automated tests)

Yêu cầu Node 22 trở lên, Java và Firebase CLI. Tại thư mục gốc project:

```sh
firebase emulators:start --project demo-mobile-banking --only auth,firestore
```

Sao chép `.env.example` thành `.env` tại thư mục gốc, tạo `OTP_SECRET` bằng `openssl rand -hex 32`, đặt `DEMO_PASSWORD` riêng dài ít nhất 12 ký tự. Các giá trị này chỉ lưu trên máy, không gửi vào chat. Mở terminal tại `backend/` để chạy các lệnh sau.

```sh
npm ci
npm run build
npm run seed
npm run seed -- --apply
npm run dev
```

Lệnh seed đầu tiên chỉ xem trước; `--apply` tạo dữ liệu nếu chưa có và giữ nguyên số dư đã phát sinh. Tài khoản theo namespace mặc định: `local_demo.customer@example.com`, `local_demo.recipient@example.com`, `local_demo.officer@example.com`. Mật khẩu là `DEMO_PASSWORD` bạn tự đặt. Dữ liệu gồm checking/savings/mortgage, tài khoản người nhận, hóa đơn, danh mục dịch vụ và chi nhánh. Seed không có chức năng xóa dữ liệu.

API: `http://127.0.0.1:3000`; health: `GET /health`; hợp đồng API: `GET /openapi.json`. Đăng nhập bằng Firebase Auth emulator rồi gửi ID token qua `Authorization: Bearer ...`. Auth emulator REST: `POST http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=demo` với JSON `email`, `password`, `returnSecureToken: true`. Tiền tố `/v1` ở đây thuộc Firebase, không thuộc API Nimbus. Không in hoặc chia sẻ ID token.

OTP giao dịch dùng DEMO_DISPLAY_CODE. Backend chỉ trả mã khi tạo challenge và giữ HMAC để xác minh. Đây là mã hiển thị cùng app cho đồ án, không phải yếu tố xác thực độc lập.

Android Emulator truy cập API máy qua `10.0.2.2:3000`. Khi tích hợp Android, cần cấu hình Auth/Firestore emulator riêng và cho phép HTTP local trong debug build; Gradle chọn Firebase Emulator khi `APP_MODE=emulator` trong `.env` và đọc địa chỉ từ `ANDROID_BACKEND_URL`. Bản release cần HTTPS.

## Luồng API chính

Tất cả endpoint nghiệp vụ yêu cầu bearer token, ngoại trừ webhook Stripe. API dùng path không có tiền tố phiên bản, ví dụ `GET /accounts` và `POST /transfers/intents`. Lỗi có `error` và `requestId`; không trả stack trace. Danh sách dùng `limit` (1–100, mặc định 25), `cursor` và trả `items`, `nextCursor`.

| Nghiệp vụ | Endpoint |
| --- | --- |
| Hồ sơ | GET/PATCH `/me` |
| Khả năng đã cấu hình | GET `/capabilities` |
| Tài khoản | GET `/accounts`, `/accounts/:id`, `/accounts/:id/details` |
| Tính toán | GET `/accounts/:id/savings-estimate?days=30`, `/accounts/:id/mortgage-schedule` |
| Lịch sử | GET `/transactions?accountId=...&type=...&status=...&from=...&to=...` (epoch ms) |
| Yêu cầu nạp/rút | POST/GET `/money-requests` |
| Chuyển nội bộ | POST `/transfers/intents`, POST `/transfers/:id/commit` |
| OTP và trạng thái | GET `/intents/:id`, POST `/intents/:id/otp`, POST `/intents/:id/verify-otp` |
| eKYC | POST/GET `/ekyc`, POST `/ekyc/:id/uploads`, `/uploads/:id/confirm`, `/ekyc/:id/submit` |
| Hóa đơn/thanh toán | GET `/bills`, POST `/payments/intents`, POST `/payments`, GET `/payments/:id` |
| Webhook | POST `/webhooks/stripe` (raw JSON + Stripe signature) |
| Dịch vụ demo | GET `/catalog`, POST/GET `/orders` |
| Chi nhánh | GET `/branches`; POST `/branches/routes` trả tuyến đi bộ cùng khoảng cách và thời gian |
| Thông báo | GET `/notifications`, POST `/notifications/:id/read` |
| Officer | GET `/officer/customers`, `/officer/ekyc`, `/officer/audit` |
| Officer quản trị | POST `/officer/customers`, PATCH `/officer/customers/:id`, POST `/officer/rate-policies` |
| Officer eKYC | GET `/officer/ekyc/:id/evidence/:index` for authorized inspection |

Gửi `latitude` và `longitude` tới `POST /branches/routes` để tìm chi nhánh gần nhất theo đường đi bộ. Gửi thêm `branchId` (trường `id` từ `GET /branches`) để chỉ đường tới một chi nhánh cụ thể.

`POST /officer/customers` nhận `requestId`, `email`, `fullName`, `phoneNumber` là số Việt Nam 10 chữ số bắt đầu bằng 0 (ví dụ 0901234567), hoặc dạng +84 tương đương cho API cũ. Backend chuẩn hóa thành +84 để lưu và kiểm tra trùng, cùng `customerNo`/`address` tùy chọn. Backend tạo Firebase Authentication user bằng email và mật khẩu ngẫu nhiên không lưu hoặc trả về cho Officer; Android yêu cầu Firebase Auth gửi email đặt mật khẩu. Số điện thoại liên hệ không được coi là số đã xác minh trong Firebase Auth; backend reserve số để từ chối trùng. Hồ sơ và tài khoản checking được tạo ở trạng thái onboarding; khách hàng chỉ được đọc/cập nhật `/me` và hoàn tất eKYC cho đến khi kiểm tra ảnh tự động trả VERIFIED. Khi mặt trước CCCD khớp selfie, backend kích hoạt hồ sơ khách hàng. Email trùng bị Firebase Auth từ chối; số điện thoại và mã khách hàng trùng bị từ chối trước khi tạo identity. Chỉ gọi API này qua HTTPS ngoài môi trường emulator/local.

`PATCH /me` chỉ cho phép contactEmail, contactPhone theo định dạng E.164 và preferences.language/notificationsEnabled. Officer có thể chỉnh họ tên, email/điện thoại liên hệ và địa chỉ qua `PATCH /officer/customers/:id`; bắt buộc có lý do. Backend lưu before/after vào `customerChanges` và audit log; GET `/officer/customers/:id/changes` đọc lịch sử theo quyền CUSTOMER_ADMIN. Email đăng nhập, role, UID, customerNo và trạng thái eKYC không được sửa qua endpoint này.

Cấu hình `GOOGLE_ROUTES_API_KEY` trong `.env` ở thư mục gốc bằng khóa máy chủ được phép gọi Routes API trên dự án Google Cloud đã bật Routes API và billing. Android đọc `MAPS_API_KEY` từ cùng file `.env` giới hạn cho Maps SDK for Android và package `com.example.final_term` cùng SHA-1. Không commit API keys.

Ví dụ tạo intent chuyển nội bộ:

```json
{"requestId":"transfer-demo-001","sourceAccountId":"local_demo_checking","destinationAccountId":"local_demo_recipient_checking","amountMinor":10000,"currency":"VND"}
```

Gọi POST `/intents/:id/otp` để nhận `challengeId`, `code`, `expiresAt` và trạng thái AWAITING_CODE. Gọi POST `/intents/:id/verify-otp` với `{challengeId,code}`, sau đó commit. Giữ nguyên requestId và intent ID khi retry. GET `/intents/:id` trả trạng thái giao dịch, OTP và face. Khi chỉnh sửa nội dung, POST `/intents/:id/cancel` trước khi tạo intent mới. Intent sống 5 phút, mã sống tối đa 2 phút. Mỗi intent có tối đa 5 lần nhập và mỗi lần tạo mã cách nhau ít nhất 30 giây.

Yêu cầu nạp/rút dùng `requestId`, `accountId`, `amountMinor`, `currency`, `type` (`DEPOSIT`/`WITHDRAWAL`); trạng thái `REQUESTED` không thay số dư. Chuyển liên ngân hàng dùng simulator `DEMO_ONLY` cho tài khoản checking có cờ demo. Xem phần demo cục bộ.

## Mốc cấu hình Firebase thật

Chỉ cần thực hiện khi muốn dùng Spark project hiện tại thay emulator. Mình sẽ hướng dẫn cùng bạn ở mốc này; không cần nâng Blaze.

1. Firebase Console → Project settings → xác nhận project ID và gói Spark. Authentication → bật Email/Password. Firestore → tạo database nếu chưa có.
2. Project settings → Service accounts → tạo thông tin Firebase Admin cho môi trường tin cậy. Lưu file ngoài repository, không gửi nội dung vào chat. Đặt `GOOGLE_APPLICATION_CREDENTIALS` thành đường dẫn tuyệt đối; tài khoản này phải có quyền Auth/Firestore cần thiết.
3. Trong `.env`: `APP_MODE=connected`, project ID thật, xóa cả hai biến emulator host; giữ API trên loopback.
4. Trước seed, xem dry run. Chỉ đặt `CONFIRM_SEED_PROJECT` bằng project ID sau khi kiểm tra namespace và tài khoản đích. Không chạy seed đè lên hai tài khoản Android cũ; seed mới tạo các UID có namespace.
5. Migration hai UID demo cũ sang custom claims sẽ thực hiện riêng sau khi đối chiếu dữ liệu. Sign out/in lại sau đổi claims. Không tự deploy rules/indexes lúc này; rules gốc vẫn chặn client ghi dữ liệu tài chính. API sử dụng Admin nên luôn tự kiểm tra quyền.

OTP giao dịch dùng chung chế độ hiển thị demo trong emulator và connected mode. Có thể bỏ cấu hình FIREBASE_PHONE_DEMO_SCOPE cũ. Email đặt mật khẩu tiếp tục dùng Firebase Auth.

## Face API và bước xác minh giao dịch giá trị cao

1. Lấy đúng hai file `det_10g.onnx` và `w600k_r50.onnx` từ gói `buffalo_l` của [bản phát hành InsightFace v0.7](https://github.com/deepinsight/insightface/releases/tag/v0.7). Đặt chúng tại `<FACE_MODEL_PATH>/models/buffalo_l/`. Giữ license đi kèm gói ở ngoài Git. Chủ dự án xác nhận quyền sử dụng đúng bản weights cung cấp. File detection và recognition phát hành công khai thuộc điều khoản nghiên cứu phi thương mại của pretrained models. Quyền thương mại cần cấp phép riêng từ InsightFace. [Model Zoo](https://github.com/deepinsight/insightface/tree/master/model_zoo), [Cấp phép weights](https://www.insightface.ai/solutions/face-recognition-licensing)
2. Trong `.env` ở thư mục gốc, đặt `FACE_TEMPLATE_KEY_HEX` bằng giá trị sinh độc lập với `OTP_SECRET`, dùng `openssl rand -hex 32`. Giữ khóa ổn định qua restart. Thay khóa cần quy trình chuyển đổi hoặc đăng ký lại template. Cấu hình Firebase Admin phải có quyền tạo custom token.
3. Từ thư mục gốc, đặt `FACE_MODEL_PATH` là đường dẫn tuyệt đối trên máy host, rồi chạy `docker compose up -d --build face-api backend`. Dùng `docker compose ps` để xem health. Compose đặt `FACE_API_BASE_URL=http://face-api:8000` trên mạng nội bộ. Face API không publish port và khởi động offline. Model version kết hợp hash hai file và phiên bản pipeline `v1`. Mẫu Python đi kèm yêu cầu thiếu file requirements nên bản pin trong `face-api/requirements.txt` là baseline được chọn cho Python 3.11.
4. Android dùng POST `/auth/face-login` với JPEG/PNG raw, sau đó dùng Firebase `signInWithCustomToken`. Backend giới hạn 5 lần mỗi 5 phút theo IP kết nối và 60 lần mỗi phút toàn dịch vụ. Khi có reverse proxy, IP kết nối có thể là IP chung của proxy. Cấu hình rate-limit theo IP xác thực tại proxy nếu cần tách người dùng. Endpoint dùng tối đa 1.000 template cùng phiên bản và từ chối khi vượt ngưỡng.
5. Backend áp dụng `FACE_THRESHOLDS_MINOR` (mặc định VND 100 triệu, mặc định dự phòng 100 triệu đơn vị nhỏ nhất mỗi currency chưa cấu hình). Khi `faceRequired` bật, POST JPEG/PNG mới tới `/intents/:id/face` với bearer Firebase. OTP và bằng chứng khuôn mặt có TTL riêng. Demo khớp ảnh tĩnh, thiếu liveness và kiểm tra chống phát lại.

`face: ENABLED` yêu cầu backend có provider và khóa, đồng thời `/health` xác nhận Face API đã nạp đúng model. Hồ sơ REVIEWING cũ có thể được chủ hồ sơ gọi lại POST `/ekyc/:id/submit` để chạy kiểm tra tự động. Hồ sơ VERIFIED cũ chưa có template cần quy trình di chuyển riêng; endpoint Officer duyệt thủ công đã bị loại bỏ. POST `/me/face/revoke` thu hồi template, đặt hồ sơ eKYC mới nhất thành `RESUBMIT_REQUIRED`, cập nhật `customers.kycStatus`, xóa tham chiếu ảnh và đánh dấu tất cả upload ticket của khách hàng `purging`. Tài khoản vẫn ACTIVE. Phản hồi có `evidenceCleanup.status` là `PENDING` khi còn ticket ảnh cần dọn; job `purge:verification` xóa mọi object version S3 và ticket, có thể chạy lại sau lỗi. Hồ sơ cũ giữ metadata kiểm toán tối thiểu. Sau thu hồi, khách hàng có thể tạo hồ sơ eKYC mới qua `/ekyc`.

`FACE_ID_SIMILARITY` mặc định 0.40 cho phép so chân dung in trên CCCD với selfie trong chế độ FULL_ID. Cần hiệu chuẩn với dữ liệu được đồng ý trước khi dùng ngoài demo. Việc khớp ảnh không xác thực CCCD thật hoặc chống ảnh giả/phát lại.

Chế độ tạm `EKYC_DEMO_SELFIE_ONLY=true` trong `.env` ở thư mục gốc bỏ qua hai ảnh CCCD và chỉ yêu cầu selfie ở index 2. Backend vẫn xác nhận S3 và kiểm tra đúng một khuôn mặt đủ chất lượng, rồi lưu template. Hồ sơ có `mode: DEMO_SELFIE_ONLY`; `/ekyc` trả `captureMode`, `/capabilities` trả `ekycMode`. Trạng thái kỹ thuật VERIFIED/ACTIVE trong chế độ này chỉ phục vụ demo đăng nhập khuôn mặt, không phải xác minh danh tính hoặc CCCD. Đặt `false` và restart backend để trở lại luồng FULL_ID; hồ sơ đã nộp giữ nguyên mode ban đầu.

## Mốc cấu hình S3 eKYC

1. AWS Console → S3 → tạo bucket private trong region đã chọn; bật toàn bộ Block Public Access, encryption mặc định và **bucket versioning**.
2. Tạo IAM principal chỉ cho prefix `ekyc/*` của bucket. Quyền cần: `s3:PutObject`, `s3:GetObject`, `s3:GetObjectVersion`. Không dùng root credentials. Cấu hình AWS profile trên máy hoặc credential chain phù hợp; không lưu AWS key vào Android.
3. Đặt `AWS_REGION`, `AWS_PROFILE`, `S3_BUCKET` trong `.env`, restart API. Dùng ảnh giả cho demo.
4. Tạo hoặc mở lại bản nháp với `{"consented":true}`. Xin vé tải lên với `mime` (`image/jpeg` hoặc `image/png`), `bytes` chính xác và `index` (0: mặt trước, 1: mặt sau, 2: selfie). Chế độ demo chỉ cấp vé cho index 2. Gửi multipart form theo `upload.url` và `upload.fields`, tệp ở trường `file`.
5. Gọi confirm ticket; backend kiểm tra kích thước, MIME, header ảnh và cố định phiên bản đối tượng. Chế độ FULL_ID cần đủ ba ảnh đã xác nhận và so mặt trước CCCD với selfie. Chế độ DEMO_SELFIE_ONLY chỉ cần selfie đã xác nhận và kiểm tra chất lượng khuôn mặt. Kết quả VERIFIED kích hoạt khách hàng; RESUBMIT_REQUIRED yêu cầu hồ sơ mới; lỗi provider giữ REVIEWING và cho phép gọi lại submit. Officer có quyền `EKYC_REVIEW` chỉ xem hồ sơ và ảnh.
6. Đặt lifecycle cho upload bỏ dở và phiên bản cũ sau khi thống nhất thời hạn lưu bằng chứng. Job `purge:verification` dọn ảnh/template theo retention; không bật lifecycle ngắn có thể xóa ảnh đang xử lý.

URL upload sống 180 giây, URL đọc theo object version sống 120 giây. Chủ hồ sơ dùng GET `/ekyc/:id/evidence/:index`. Officer có quyền EKYC_REVIEW dùng GET `/officer/ekyc/:id/evidence/:index`. Dùng S3_ENDPOINT và S3_FORCE_PATH_STYLE cho S3-compatible. URL ký phải định tuyến được từ Android. Compose cần nhận AWS credentials qua môi trường hoặc workload identity của container.

### Dọn dữ liệu xác minh

Lên lịch chạy hằng ngày `docker compose exec -T backend npm run purge:verification`. Với backend chạy trực tiếp, build trước rồi chạy `npm run purge:verification` từ thư mục backend. Lệnh xóa tất cả object version và delete marker thuộc ticket hết hạn. Cấp thêm `s3:ListBucketVersions` giới hạn prefix `ekyc/` và `s3:DeleteObjectVersion` chỉ cho job/role dọn dữ liệu. Job có thể chạy lại sau lỗi. Mặc định giữ ảnh của hồ sơ đã có quyết định 90 ngày và giữ ảnh hồ sơ đang review. Ảnh draft đã confirm được giữ 30 ngày, ticket draft bị thay thế/chưa confirm được giữ một ngày rồi có thể chụp/upload lại. Draft cũ thiếu timestamp cần rà soát riêng. Template có TTL mặc định 365 ngày, bị loại khỏi matching ngay khi hết hạn và được xóa ciphertext khi job chạy. Cấu hình vòng đời backup của storage và Firestore riêng theo môi trường.

## Dòng tiền VND và thanh toán Stripe test

Firestore là nguồn số dư VND chính của app. Số dư checking, lịch sử giao dịch và các bút toán hiển thị trên Android lấy từ sổ này. Chuyển khoản nội bộ ghi nợ tài khoản gửi và ghi có tài khoản nhận trong cùng một Firestore transaction, sau khi backend kiểm tra quyền, trạng thái tài khoản, số dư, hạn mức, OTP và yêu cầu face step-up.

Stripe chỉ thu tiền thẻ ở chế độ test. `POST /topups/intents` tạo intent nạp VND gắn với tài khoản checking; người dùng hoàn thành OTP/face rồi gọi `POST /topups` để nhận PaymentIntent cho Stripe PaymentSheet. Tiền chỉ được ghi có vào Firebase sau webhook có chữ ký `payment_intent.succeeded`, khớp payment ID, mục đích, số tiền, currency, tài khoản và provider ID. PaymentSheet đóng hoặc báo hoàn tất chưa phải bằng chứng ghi có. Stripe VND dùng số nguyên đồng; việc thu VND phụ thuộc currency/capability của Stripe account.

Hóa đơn điện/nước và đơn hàng tiện ích dùng PaymentIntent thẻ riêng. Webhook thành công đánh dấu hóa đơn đã trả và tạo outbox cho fulfillment; không trừ Firebase wallet lần nữa. Fulfillment hiện vẫn là demo và ghi trạng thái `DEMO_FULFILLED`.

Cấu hình `STRIPE_SECRET_KEY=sk_test_...`, `STRIPE_WEBHOOK_SECRET=whsec_...` và Android `STRIPE_PUBLISHABLE_KEY=pk_test_...` trong file env cục bộ. Không commit các khóa này. Chạy Stripe CLI để chuyển webhook tới đúng port backend đang nghe, ví dụ `stripe listen --events payment_intent.succeeded,payment_intent.payment_failed,payment_intent.canceled --forward-to http://127.0.0.1:3000/webhooks/stripe`; lấy signing secret CLI in ra làm `STRIPE_WEBHOOK_SECRET` rồi khởi động lại backend. Khi port backend khác 3000, thay URL tương ứng. Dùng thẻ test `4242 4242 4242 4242`, ngày hết hạn tương lai và CVC bất kỳ.

Chuyển liên ngân hàng VND chỉ dùng `DEMO_BANK` và beneficiary demo; backend mô phỏng kết quả và ghi clearing demo, không chuyển tới ngân hàng thật. Rút tiền cũng là luồng demo. Muốn chuyển liên ngân hàng hoặc payout thật phải tích hợp nhà cung cấp có rail chuyển tiền phù hợp.

Các số dư/financial accounts Stripe Treasury cũ tách biệt khỏi Firebase và không còn được hiển thị như số dư Nimbus. App không tự provision Stripe Treasury account khi tạo checking mới. Những số dư cũ không được tự động di chuyển hoặc cộng vào Firebase.

## Kiểm thử và giới hạn

Schema đã được đối chiếu với bảng 16 trong Word. Xem `../docs/database-template-alignment.md` để tra field chuẩn và chạy migration có bước xem trước. API nhận `sourceId`/`target` cho transfer, `billOrOrderRef` cho payment, `accountProduct` cho rate policy; body cũ vẫn được hỗ trợ riêng. Không trộn hai dạng trong cùng request.

```sh
npm test
npm run build
```

Từ thư mục gốc:

```sh
firebase emulators:exec --project demo-mobile-banking --only auth,firestore "npm --prefix backend run test:emulator"
```

Kiểm thử emulator được chạy riêng, không âm thầm kết nối project thật. Unit suite thường đánh dấu nó skipped. Chưa chạy kiểm thử connected với AWS/Stripe/Firebase thật do chưa có cấu hình. Test HTTP cần quyền mở cổng loopback.

Truy vấn phân trang ngay trên Firestore; cursor là opaque token, cần gửi lại nguyên giá trị. Lịch sử có bộ lọc ngày có thể cần composite index trên Spark; chốt/deploy index theo truy vấn khi kết nối thật. Worker xử lý tối đa 25 job mỗi lần, chạy lại để drain. Hạn mức ngày checking được kiểm tra và tăng usage nguyên tử theo ngày UTC; policy thay đổi yêu cầu tạo intent mới.

Giới hạn demo còn lại: kích hoạt khách hàng cần hardening, chưa có real SMS/face/Routes/interbank/inventory provider, webhook reconciliation sau timeout cần thao tác retry cùng request ID, chưa có credential rotation, metrics, backup hoặc production deployment. Chưa triển khai refund, dispute hoặc các luồng Stripe live. Việc triển khai app hoàn chỉnh là giai đoạn tiếp theo.


## Demo cục bộ từ Android Emulator

Dùng một Android Emulator API 26 trở lên và project emulator `demo-mobile-banking`. Hai lệnh sau minh họa thiết lập local. Firebase CLI và Java cần sẵn bản tương thích và Firestore Emulator trong cache.

```sh
CI=true FIREBASE_CLI_DISABLE_TELEMETRY=1 FIREBASE_SKIP_UPDATE_CHECK=true   firebase --config firebase.backend-test.json emulators:start   --project demo-mobile-banking --only auth,firestore
```

Từ một shell riêng, cung cấp các biến này cho tiến trình backend và seed. Nhập mật khẩu demo thông qua môi trường hoặc trình quản lý bí mật, không đặt mật khẩu trên dòng lệnh. Các giá trị trống tắt S3, Face API và Google Routes cho phiên shell này. Để thử thanh toán, thiết lập Stripe Sandbox theo mục trên trước khi khởi động backend.

```sh
export APP_MODE=emulator FIREBASE_PROJECT_ID=demo-mobile-banking
export FIREBASE_AUTH_EMULATOR_HOST=127.0.0.1:19099 FIRESTORE_EMULATOR_HOST=127.0.0.1:18080
export HOST=127.0.0.1 PORT=3198 DEMO_NAMESPACE=local_demo
export CHATBOT_ENABLED=false CHATBOT_LOCAL_DEMO=true GOOGLE_ROUTES_API_KEY=
export S3_BUCKET= FACE_API_BASE_URL=
export OTP_SECRET="$(openssl rand -hex 32)"
# Cung cấp DEMO_PASSWORD trước khi chạy --apply.
npm --prefix backend run seed -- --apply
npm --prefix backend run build
npm --prefix backend start
```

Đăng nhập `local_demo.customer@example.com` hoặc `local_demo.officer@example.com` bằng mật khẩu demo đã cấp. Chạy seed trên namespace emulator cục bộ để thêm hóa đơn nước và danh mục mới. Seed thêm bản ghi còn thiếu. Khi có thanh toán đồ án đã quyết toán, từ shell dùng cùng môi trường, chạy:

```sh
npm --prefix backend run worker
```

Lệnh xử lý outbox cập nhật fulfillment của đơn demo. Màn tra cứu hiển thị trạng thái thanh toán, trạng thái thực hiện và mã demo. Tài khoản giả lập có cờ `demo: true`; các API mô phỏng chuyển khoản và nạp/rút kiểm tra phạm vi tài khoản. Kiểm thử liên ngân hàng dùng `DEMO_BANK` với `0000000001` (chấp nhận) hoặc `0000000002` (từ chối). Sau OTP và step-up nếu cần, đợi ít nhất 5 giây và bấm kiểm tra để đối soát. Mã OTP demo chỉ hiện trong ứng dụng, không phải 2FA độc lập.

## Chatbot tùy chọn

`APP_MODE=emulator CHATBOT_LOCAL_DEMO=true` bật trợ lý hướng dẫn local, với trạng thái API `DEMO_ONLY`. Chế độ này hỗ trợ câu chào và các chủ đề điều hướng bằng câu trả lời có sẵn. Nó không gửi yêu cầu đến OpenAI.

Khi được phép dùng provider, chỉ đặt `CHATBOT_ENABLED=true`, `OPENAI_API_KEY` và `OPENAI_MODEL` trong môi trường backend hoặc kho bí mật. Chọn model hỗ trợ [Responses API](https://platform.openai.com/docs/api-reference/responses/create). Không cấu hình key trong Gradle, local.properties hoặc APK.

`POST /chatbot` yêu cầu khách hàng active. Bộ từ vựng nhận các câu như “nạp tiền điện thoại”, “hóa đơn”, “giao dịch tháng này”, “chuyển khoản”, “tìm chi nhánh” và “nạp tiền”. Server chỉ gửi mã chủ đề thuộc allowlist. Không gửi văn bản người dùng, ảnh, OTP, UID hoặc dữ liệu tài khoản, và không cấp tools cho model. Android chỉ mở màn khi người dùng bấm action đã cho phép. Thiếu cấu hình trả `CHATBOT_SETUP_REQUIRED`. Provider quá hạn hoặc lỗi báo trạng thái có thể thử lại.

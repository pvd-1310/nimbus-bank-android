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
│       ├── ekyc/              # Upload S3, submit, officer review
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
cp .env.production.example .env
npm ci
npm run build
npm start
```

`APP_MODE=connected`, `FIREBASE_PROJECT_ID` phải là project thật, `GOOGLE_APPLICATION_CREDENTIALS` phải trỏ tới Firebase Admin credential hoặc workload identity của runtime, và `OTP_SECRET` phải là secret manager value. Reverse proxy phải cung cấp HTTPS; không đưa service-account, OTP, AWS hoặc Stripe secret vào Android.

Container production:

```sh
docker build -t nimbus-bank-api ./backend
docker run --read-only --cap-drop=ALL --security-opt=no-new-privileges \
  -p 3000:3000 --env-file backend/.env \
  -v /secure/firebase-admin.json:/run/secrets/firebase-admin.json:ro \
  nimbus-bank-api
```

Chỉ mở cổng qua reverse proxy/API gateway có TLS, rate limit, access log và secret injection. Chưa chạy lệnh deploy vì repository chưa chỉ định AWS/GCP/Cloud Run/Render/Fly hay domain production.

## Chạy local bằng emulator (chỉ dành cho automated tests)

Yêu cầu Node 22 trở lên, Java và Firebase CLI. Tại thư mục gốc project:

```sh
firebase emulators:start --project demo-mobile-banking --only auth,firestore
```

Mở terminal khác tại `backend/`. Sao chép `.env.example` thành `.env`, tạo `OTP_SECRET` bằng `openssl rand -hex 32`, đặt `DEMO_PASSWORD` riêng dài ít nhất 12 ký tự. Các giá trị này chỉ lưu trên máy, không gửi vào chat.

```sh
npm ci
npm run build
npm run seed
npm run seed -- --apply
npm run dev
```

Lệnh seed đầu tiên chỉ xem trước; `--apply` tạo dữ liệu nếu chưa có và giữ nguyên số dư đã phát sinh. Tài khoản theo namespace mặc định: `local_demo.customer@example.com`, `local_demo.recipient@example.com`, `local_demo.officer@example.com`. Mật khẩu là `DEMO_PASSWORD` bạn tự đặt. Dữ liệu gồm checking/savings/mortgage, tài khoản người nhận, hóa đơn, danh mục dịch vụ và chi nhánh. Seed không có chức năng xóa dữ liệu.

API: `http://127.0.0.1:3000`; health: `GET /health`; hợp đồng API: `GET /openapi.json`. Đăng nhập bằng Firebase Auth emulator rồi gửi ID token qua `Authorization: Bearer ...`. Auth emulator REST: `POST http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=demo` với JSON `email`, `password`, `returnSecureToken: true`. Dùng `idToken` nhận được; không in token vào log hoặc chia sẻ.

OTP demo được giao vào file riêng `.local/otp/<intentId>.json` (quyền 0600), hết hạn cùng intent sau 5 phút. File này phục vụ demo trên máy, không đại diện cho SMS/2FA thật. Adapter này chỉ hoạt động với emulator và listener loopback. Connected mode yêu cầu provider OTP riêng trước khi giao dịch được bật.

Android Emulator truy cập API máy qua `10.0.2.2:3000`. Khi tích hợp Android, cần cấu hình Auth/Firestore emulator riêng và cho phép HTTP local trong debug build; Android hiện tại chưa tự chuyển sang emulator/backend này. App release sau này cần HTTPS.

## Luồng API chính

Tất cả endpoint dưới `/v1` yêu cầu bearer token, ngoại trừ webhook Stripe. Lỗi có `error` và `requestId`; không trả stack trace. Danh sách dùng `limit` (1–100, mặc định 25), `cursor` và trả `items`, `nextCursor`.

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
| Chi nhánh | GET `/branches`; POST `/branches/routes` hiện trả `SETUP_REQUIRED` |
| Thông báo | GET `/notifications`, POST `/notifications/:id/read` |
| Officer | GET `/officer/customers`, `/officer/ekyc`, `/officer/audit` |
| Officer quản trị | POST `/officer/customers`, PATCH `/officer/customers/:id`, POST `/officer/rate-policies` |
| Officer eKYC | POST `/officer/ekyc/:id/review`, GET `/officer/ekyc/:id/evidence/:index` |

Ví dụ tạo intent chuyển nội bộ:

```json
{"requestId":"transfer-demo-001","sourceAccountId":"local_demo_checking","destinationAccountId":"local_demo_recipient_checking","amountMinor":10000,"currency":"VND"}
```

Gọi tạo OTP bằng intent ID, nhập mã qua `verify-otp` với `{"code":"..."}`, sau đó commit. Retry cùng intent trả receipt cũ. Cùng `requestId` nhưng thay số tiền bị từ chối. Tiền VND giữ nguyên đơn vị nguyên, không chia 100. Giao dịch từ 100 triệu VND trở lên bị chặn đến khi có provider step-up; ngưỡng hiện là chính sách demo cần đưa vào cấu hình quản trị trước production.

Yêu cầu nạp/rút dùng `requestId`, `accountId`, `amountMinor`, `currency`, `type` (`DEPOSIT`/`WITHDRAWAL`); trạng thái `REQUESTED` không thay số dư. Chuyển liên ngân hàng chưa có provider nên trả `SETUP_REQUIRED`.

## Mốc cấu hình Firebase thật

Chỉ cần thực hiện khi muốn dùng Spark project hiện tại thay emulator. Mình sẽ hướng dẫn cùng bạn ở mốc này; không cần nâng Blaze.

1. Firebase Console → Project settings → xác nhận project ID và gói Spark. Authentication → bật Email/Password. Firestore → tạo database nếu chưa có.
2. Project settings → Service accounts → tạo thông tin Firebase Admin cho môi trường tin cậy. Lưu file ngoài repository, không gửi nội dung vào chat. Đặt `GOOGLE_APPLICATION_CREDENTIALS` thành đường dẫn tuyệt đối; tài khoản này phải có quyền Auth/Firestore cần thiết.
3. Trong `.env`: `APP_MODE=connected`, project ID thật, xóa cả hai biến emulator host; giữ API trên loopback.
4. Trước seed, xem dry run. Chỉ đặt `CONFIRM_SEED_PROJECT` bằng project ID sau khi kiểm tra namespace và tài khoản đích. Không chạy seed đè lên hai tài khoản Android cũ; seed mới tạo các UID có namespace.
5. Migration hai UID demo cũ sang custom claims sẽ thực hiện riêng sau khi đối chiếu dữ liệu. Sign out/in lại sau đổi claims. Không tự deploy rules/indexes lúc này; rules gốc vẫn chặn client ghi dữ liệu tài chính. API sử dụng Admin nên luôn tự kiểm tra quyền.

Connected mode hiện cố ý yêu cầu OTP delivery provider thật. Có thể kiểm thử các API đọc, eKYC và quyền officer sau cấu hình, nhưng cần bổ sung provider OTP trước khi demo giao dịch trên project thật. Emulator cung cấp toàn bộ OTP demo local.

## Mốc cấu hình S3 eKYC

1. AWS Console → S3 → tạo bucket private trong region đã chọn; bật toàn bộ Block Public Access, encryption mặc định và **bucket versioning**.
2. Tạo IAM principal chỉ cho prefix `ekyc/*` của bucket. Quyền cần: `s3:PutObject`, `s3:GetObject`, `s3:GetObjectVersion`. Không dùng root credentials. Cấu hình AWS profile trên máy hoặc credential chain phù hợp; không lưu AWS key vào Android.
3. Đặt `AWS_REGION`, `AWS_PROFILE`, `S3_BUCKET` trong `.env`, restart API. Dùng ảnh giả cho demo.
4. Tạo request với `{"consented":true}`, xin upload với `mime` (`image/jpeg`/`image/png`) và `bytes` chính xác, gửi multipart form theo `upload.url` và `upload.fields`, file ở field `file`.
5. Gọi confirm ticket; backend kiểm tra kích thước, MIME, header ảnh và lưu object version. Upload ít nhất hai ảnh trước submit. Officer cần claim và server permission `EKYC_REVIEW` để đọc/xét duyệt.
6. Đặt lifecycle cho upload bỏ dở và phiên bản cũ sau khi thống nhất thời hạn lưu bằng chứng. Chưa có job tự xóa ảnh trong code; không bật lifecycle ngắn có thể xóa ảnh đang xét duyệt.

URL upload sống 180 giây; URL đọc officer sống 120 giây. S3 không thực hiện nhận dạng khuôn mặt. Demo dùng quyết định của officer. AWS có cơ chế tính phí riêng, không nằm trong Firebase Spark.

## Mốc cấu hình Stripe

1. Dùng Stripe sandbox/test mode; cấu hình `STRIPE_SECRET_KEY=sk_test_...` trên máy. Live keys bị từ chối.
2. Chạy Stripe CLI: `stripe listen --forward-to localhost:3000/v1/webhooks/stripe`, lưu signing secret vào `STRIPE_WEBHOOK_SECRET`, restart API. Không gửi key/secret vào chat.
3. Tạo payment intent nghiệp vụ với `billId`, `requestId`; dùng cùng luồng OTP như chuyển khoản; gọi POST `/payments` với hai trường đó.
4. Dùng `clientSecret` trong Stripe test checkout/Android PaymentSheet khi nối UI. API tạo PaymentIntent; chưa có màn checkout trong Android.
5. Webhook hợp lệ cập nhật hóa đơn; gọi `npm run worker` để xử lý outbox. Đơn demo được ghi `DEMO_FULFILLED`; đơn thật cần provider riêng. Browser return không xác nhận thanh toán.

Payment dùng nguồn tiền Stripe, không tự trừ tài khoản nội bộ thêm lần nữa. Webhook phải khớp payment ID, số tiền, currency và provider ID. Stripe CLI `trigger` sự kiện ngẫu nhiên không có metadata payment ID sẽ bị từ chối; cần thanh toán PaymentIntent do backend tạo để kiểm thử toàn luồng.

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

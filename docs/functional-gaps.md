# Các chức năng còn thiếu và chưa hoàn thiện

> Đây là danh sách thiếu sót dùng làm mốc ban đầu. Đọc [đối chiếu đề bài, trạng thái sau triển khai và checklist](final-project-assessment.md) để xem các thay đổi và mục còn mở.

## Phạm vi đánh giá

Tài liệu này chỉ theo dõi các chức năng được yêu cầu trong đề bài và SRS còn thiếu, mới có một phần, hoặc chưa được chứng minh là hoạt động hoàn chỉnh trong mã hiện tại. Đây là đánh giá tĩnh dựa trên mã Android, backend và tài liệu dự án; trạng thái triển khai dịch vụ ngoài (Firebase, S3, Stripe, Google Routes) cần được xác minh riêng trong môi trường chạy.

**Nguyên tắc phạm vi:** chỉ điều chỉnh chức năng để đáp ứng yêu cầu. Không cần giữ hoặc hoàn thiện màn hình, nút, hiệu ứng hay luồng UI nếu nó không hỗ trợ yêu cầu trong đề bài/SRS; có thể loại bỏ phần UI thừa. Chỉ bổ sung giao diện tối thiểu khi cần để người dùng thực hiện chức năng bắt buộc. Các yêu cầu accessibility và nội dung xác nhận giao dịch được giữ lại vì chúng có trong SRS.

**Kết luận:** ứng dụng chưa đáp ứng toàn bộ yêu cầu. Các phần còn thiếu lớn nhất là chatbot, xác minh khuôn mặt, chuyển liên ngân hàng, thanh toán/tiện ích trên Android và các luồng mua vé/đặt phòng/thương mại điện tử. Một số chức năng có backend nhưng chưa có giao diện hoặc dịch vụ nhà cung cấp được cấu hình.

## 1. Hồ sơ người dùng và vai trò

### 1.1 Khách hàng cập nhật hồ sơ

**Đã bổ sung trong mã hiện tại:** khách hàng có thể sửa email/số liên hệ và tùy chọn ngôn ngữ/thông báo qua `PATCH /me`. Backend chỉ chấp nhận các trường này, xác thực định dạng, ghi audit với contact được che và giữ nguyên các trường định danh. Android có luồng sửa tối giản trong màn hồ sơ.

**Còn cần xác minh:** cập nhật rules/deploy theo `firestore.rules` trên Firebase project mục tiêu và xác nhận các trường contact phù hợp chính sách vận hành.

### 1.2 Nhân viên sửa dữ liệu khách hàng

**Đã bổ sung trong mã hiện tại:** Officer có thể cập nhật họ tên, email/số liên hệ và địa chỉ; backend bắt buộc lý do, kiểm tra quyền `CUSTOMER_ADMIN`, lưu before/after vào `customerChanges`, ghi audit đã che thông tin liên hệ và cung cấp API xem lịch sử. Android cho phép gửi thay đổi và xem giá trị trước/sau/lịch sử.

**Giới hạn chủ ý:** email đăng nhập, UID, role, mã khách hàng và trạng thái eKYC không sửa qua chức năng này.

### 1.3 Kích hoạt khách hàng mới

**Đã bổ sung trong mã hiện tại:** Officer tạo hồ sơ ở trạng thái `ONBOARDING` và tài khoản Firebase nhận mật khẩu ngẫu nhiên chỉ do backend tạo; Android yêu cầu Firebase gửi email đặt mật khẩu thay vì Officer đặt/chuyển mật khẩu ban đầu. Mã khách hàng được reserve nguyên tử để chặn trùng; Firebase Auth từ chối email/số điện thoại đã tồn tại. Trong onboarding, API chỉ cho phép `/me` và eKYC; các thao tác tài chính bị chặn. Rules Firestore cũng chặn khách hàng onboarding đọc tài khoản/giao dịch/thông báo. Khi Officer duyệt eKYC `VERIFIED`, backend chuyển hồ sơ customer sang `ACTIVE` trong cùng transaction với quyết định review; Android theo dõi thay đổi trạng thái và chuyển khách hàng vào ứng dụng.

**Còn cần xác minh:** cấu hình Firebase email action/reset, triển khai rules mới lên project mục tiêu, và chạy luồng tích hợp với Auth/Firestore thực tế. Nếu gửi email đặt mật khẩu lỗi, khách hàng có thể yêu cầu lại từ màn đăng nhập.

## 2. Bảo mật, eKYC và xác thực giao dịch

### 2.1 Xác minh khuôn mặt

- Chế độ FULL_ID yêu cầu đồng ý và đủ ảnh CCCD trước, CCCD sau, selfie. Chế độ tạm `EKYC_DEMO_SELFIE_ONLY=true` chỉ chụp selfie; hồ sơ gắn `mode: DEMO_SELFIE_ONLY` và UI ghi rõ chưa xác minh CCCD/danh tính pháp lý. Khi tắt flag, hồ sơ mới trở lại FULL_ID; hồ sơ đã nộp giữ nguyên mode. Khi nộp FULL_ID, backend đọc mặt trước CCCD và selfie từ S3, tạo hai embedding cùng Face API và so cosine theo `FACE_ID_SIMILARITY`. Với DEMO_SELFIE_ONLY, backend chỉ đọc selfie và yêu cầu đúng một khuôn mặt đủ chất lượng trước khi tạo template. Trong FULL_ID, mặt sau chỉ được kiểm tra upload/MIME/byte/version, chưa phân tích nội dung. Nếu ảnh lỗi, thiếu đúng một khuôn mặt hoặc không khớp, hồ sơ thành RESUBMIT_REQUIRED và khách chụp lại. Nếu dịch vụ lỗi, hồ sơ giữ REVIEWING để retry. Nếu FULL_ID khớp hoặc selfie demo đạt chất lượng, backend kiểm tra lại hồ sơ rồi ghi template selfie đã mã hóa, VERIFIED và customer ACTIVE trong cùng Firestore transaction. Officer chỉ xem hồ sơ/bằng chứng, không duyệt thủ công.
- Android có đăng nhập demo bằng camera, với email/mật khẩu làm phương thức dự phòng. Backend tìm 1:N trong các template còn hiệu lực của CUSTOMER ACTIVE có hồ sơ VERIFIED; hồ sơ selfie demo vẫn mang mode riêng để không hiểu nhầm là đã xác minh CCCD. Ngưỡng cosine và khoảng cách với ứng viên thứ hai có thể cấu hình. Backend cấp Firebase custom token sau khi xác minh.
- Chuyển nội bộ và checkout Stripe dùng ngưỡng giá trị ở backend. Intent vượt ngưỡng cần so 1:1 với template của chủ giao dịch và OTP riêng. Bằng chứng khuôn mặt gắn vào intent, có thời hạn và được tiêu thụ một lần.
- `face: ENABLED` phụ thuộc vào cấu hình khóa template và health của weights đang chạy. Khi cấu hình hoặc weights thiếu, API báo SETUP_REQUIRED. Luồng demo khớp ảnh tĩnh, chưa có liveness, xác minh nguồn ảnh phía máy chủ hay khả năng chống ảnh phát lại. Chưa kiểm chứng camera-to-Firebase với weights và provider thật. Ngưỡng so ảnh CCCD/selfie là cấu hình demo chưa hiệu chuẩn; không xác thực CCCD thật, không OCR, không liveness và không đủ cho định danh production.

### 2.2 Mã xác nhận giao dịch demo

- `otp: DEMO_DISPLAY_CODE`. Mỗi challenge có mã CSPRNG hiển thị đúng một lần trong app. Backend chỉ lưu HMAC, giữ intent bất biến, giới hạn số lần nhập/cấp mã, kiểm tra thời hạn và dùng một lần khi commit. App hiển thị nhãn demo cạnh mã và ô nhập. Cơ chế này không phải 2FA độc lập.
- Chuyển nội bộ ghi sổ và tiêu thụ bằng chứng trong cùng transaction, có idempotency và tra cứu khi timeout. Backend dùng cùng lớp xác thực trước khi tạo Stripe PaymentIntent cho hóa đơn, mobile top-up và đơn mua dịch vụ/hàng hóa đi qua hóa đơn. Webhook Stripe quyết định kết quả settlement. UI Android mới nối mã demo và recovery ở luồng chuyển nội bộ. Các màn checkout còn cần tích hợp UI.
- Nạp/rút hiện dừng ở REQUESTED và không ghi số dư hay ledger. Khi có bước settlement cho nạp/rút, bước đó cần dùng cùng lớp xác thực.

### 2.3 Bảo vệ ảnh eKYC và template

- S3/S3-compatible dùng bucket private có versioning, ticket upload 180 giây, URL đọc 120 giây và kiểm tra quyền Officer hoặc chủ hồ sơ. Chế độ DEMO_SELFIE_ONLY chỉ cấp ticket selfie ở index 2. Confirm kiểm tra MIME, số byte, chữ ký định dạng và version cụ thể. Mặt trước CCCD và selfie được giải mã và kiểm tra mặt/chất lượng bằng Face API khi nộp. Mặt sau chưa có kiểm tra nội dung; chưa có OCR hay xác thực tính thật của CCCD.
- Mặc định giữ ảnh hồ sơ đã có quyết định 90 ngày, ngoài hồ sơ đang review. Lệnh retention xử lý cả các version và delete marker, dọn ticket nháp thừa hoặc hết hạn. Template hết hạn mặc định sau 365 ngày, lưu mã hóa AES-GCM trong vùng Admin-only và có API thu hồi. Job retention cần được lên lịch ở môi trường chạy.
- Cần cấp cấu hình Firebase, S3 và weights hợp lệ trước khi chạy đủ luồng. Xem [hướng dẫn cấu hình](../backend/README.md).

## 3. Quản lý tài khoản

### 3.1 Chi tiết savings

- Màn chi tiết hiện có thông tin trình bày mẫu (ví dụ lãi suất 5.8%) và lưu ý backend sẽ xác nhận; chưa nối API ước tính lãi vào màn hình.
- Cần tải gốc tiền gửi, lãi suất policy hiện hành, lợi nhuận tháng, kỳ hạn và mốc hiệu lực từ dữ liệu thật.
- Công thức phải thống nhất day-count convention, cách làm tròn và phiên bản policy; không dùng số liệu tĩnh làm dữ liệu tài khoản.

### 3.2 Chi tiết mortgage

- Màn hiện hiển thị nội dung tĩnh/placeholder; chưa nối API lịch trả nợ.
- Cần hiển thị dư nợ, lãi suất, kỳ hạn, số tiền trả theo tháng và mỗi hai tuần, lịch từng kỳ và tổng tiền dự kiến từ dữ liệu khoản vay.
- Cần xác định và áp dụng nhất quán quy ước tính lãi, tần suất trả, thời điểm đến hạn và làm tròn.

### 3.3 Gửi tiết kiệm và rút tiền

- Đã có luồng tạo yêu cầu nạp/rút, nhưng request trạng thái `REQUESTED` không thay đổi số dư.
- Chưa có bước xử lý/phê duyệt/settlement để biến yêu cầu thành nghiệp vụ tiền hoàn tất.
- Cần tách yêu cầu khỏi giao dịch đã ghi sổ; chỉ cập nhật số dư/ledger sau xác thực, kiểm tra điều kiện và commit thành công.

### 3.4 Lịch sử giao dịch

- Có màn lịch sử và bộ lọc ở Android/backend.
- Cần xác minh phân trang và các bộ lọc ngày/tài khoản chạy trên dữ liệu thật; xử lý lỗi index, offline và trạng thái dữ liệu cũ.
- Biên lai cần dùng transaction ID/trạng thái authoritative từ backend, không suy ra thành công từ thao tác UI.

## 4. Chuyển tiền

### 4.1 Chuyển nội bộ

- Có luồng Android tạo intent, xác minh OTP và gọi commit backend.
- Cần chạy/kiểm chứng với OTP provider phù hợp thay cho chế độ demo; kiểm tra đủ số dư, trạng thái tài khoản, quyền sở hữu, hạn mức, idempotency, giao dịch đồng thời và khôi phục khi mất mạng sau commit.
- Bước xác nhận giao dịch phải thể hiện rõ người nhận, tài khoản nguồn, số tiền, phí (nếu có) và tổng tiền trước OTP, theo yêu cầu SRS.
- Không hiển thị thành công cho đến khi backend trả kết quả commit authoritative.

### 4.2 Chuyển liên ngân hàng

- Chưa tích hợp provider hoặc simulator hoàn chỉnh; backend capability `externalTransfer: SETUP_REQUIRED`.
- Cần thêm chọn ngân hàng, kiểm tra người nhận/định dạng, quote phí, xác thực OTP, trạng thái pending, provider reference, kết quả settlement và đối soát.
- Nếu chỉ mô phỏng cho đồ án, giao diện và biên lai phải ghi rõ trạng thái giả lập; không dùng VNPay như một rail chuyển liên ngân hàng.

## 5. Thanh toán và tiện ích

### 5.1 Hóa đơn điện/nước

- Màn tiện ích nhận mã tra cứu nhưng hiện trả thông báo không tìm thấy hóa đơn/đối tác đang đồng bộ; chưa gọi API tra cứu hóa đơn thực hoặc catalog demo có dữ liệu.
- Chưa có xác nhận invoice/quote, số tiền, hạn thanh toán, OTP, checkout và cập nhật hóa đơn sau settlement.
- Cần xử lý hóa đơn thay đổi sau khi tạo quote, thanh toán lặp, webhook trùng và trạng thái chờ xác nhận.

### 5.2 Nạp tiền điện thoại

- UI có lựa chọn nạp điện thoại, nhưng chưa có chọn nhà mạng/mệnh giá, quote, thanh toán và xác nhận nhà cung cấp đã nạp thành công.
- Cần kết nối provider hoặc demo catalog/fulfillment minh bạch; lưu mã giao dịch nhà cung cấp và cho phép tra cứu trạng thái.

### 5.3 VNPay/Stripe checkout

- Backend có tích hợp Stripe test mode và webhook, nhưng Android chưa có màn checkout/PaymentSheet.
- Chưa thấy tích hợp VNPay dù đề bài cho VNPay hoặc Stripe.
- Cần nối Android với payment intent, xử lý return URL không authoritative, chờ webhook/IPN xác nhận, hiển thị pending/failed/succeeded và xử lý webhook idempotent.
- Bí mật ký/gateway phải chỉ ở backend; cần kiểm tra chữ ký, payment reference, số tiền và currency trước khi settlement.

### 5.4 Vé máy bay, vé xem phim, khách sạn, thương mại điện tử

- Backend hiện hỗ trợ catalog/đơn hàng demo; chỉ nhận sản phẩm có `demo: true` và fulfillment có thể mang trạng thái `DEMO_FULFILLED`.
- Chưa có các luồng trải nghiệm hoàn chỉnh cho từng loại dịch vụ: tìm kiếm/chọn chuyến bay, chọn suất/ghế phim, tìm phòng/ngày ở, giỏ hàng/đơn hàng thương mại điện tử.
- Cần quote có thời hạn, giữ chỗ, xác nhận thông tin người mua, thanh toán, fulfillment riêng với payment, hủy/timeout và tra cứu trạng thái.
- Nếu không có API đối tác, cần dữ liệu seed và nhãn demo để phân biệt rõ với đặt chỗ thật.

## 6. Bản đồ và tìm chi nhánh

- Android có màn bản đồ, lấy vị trí thiết bị, tải chi nhánh và gọi endpoint tính Routes.
- Backend yêu cầu `GOOGLE_ROUTES_API_KEY`; capability hiện báo `routes: SETUP_REQUIRED`, nên chưa thể xem đây là luồng chạy hoàn chỉnh khi chưa cấu hình.
- Khi người dùng từ chối quyền vị trí, cần có cách tiếp tục xem/chọn chi nhánh theo yêu cầu SRS; không chỉ yêu cầu cấp quyền lại.
- Backend chọn ứng viên theo khoảng cách đường chim bay trước rồi tính route cho ứng viên đó. Cách này chưa chứng minh luôn chọn tuyến đi bộ ngắn nhất trong tập chi nhánh.
- Cần tính walking route cho các ứng viên thích hợp, chọn route có khoảng cách/thời gian phù hợp, hiển thị khoảng cách/thời gian/polyline và xử lý không có route/provider lỗi mà không tuyên bố sai là gần nhất.
- Cần kiểm tra location null, GPS kém chính xác, chi nhánh thiếu tọa độ, không có chi nhánh, mất mạng và API quota.

## 7. Chatbot AI

- Chưa có chatbot hoạt động trong ứng dụng; capability backend ghi `chatbot: DEFERRED`.
- Cần giao diện hội thoại, gọi backend/OpenAI an toàn, trạng thái loading/lỗi/timeout và câu trả lời dự phòng.
- Cần action registry allowlist theo vai trò, tối thiểu cho mở nạp tiền, hóa đơn, lịch sử giao dịch, chuyển khoản và bản đồ chi nhánh.
- Chatbot chỉ được đề xuất điều hướng; không tự thực hiện giao dịch hoặc bỏ qua màn xác nhận, OTP, eKYC và phân quyền.
- Không gửi OTP, secret, ảnh định danh hoặc dữ liệu cá nhân không cần thiết sang mô hình. Truy vấn số dư/lịch sử phải lấy từ backend đã kiểm tra quyền.
- Cần xử lý intent mơ hồ, action ID không hợp lệ, prompt injection, yêu cầu xem dữ liệu người khác và dịch vụ AI không khả dụng.

## 8. Officer operations và audit

- Có một số chức năng Officer: danh sách tài khoản/khách hàng, provision khách hàng, sửa tên, thẩm định eKYC, cập nhật policy lãi và xem audit.
- Cần xác minh các màn đều đọc dữ liệu thật và lỗi API không bị thay bằng dữ liệu mẫu.
- Màn audit hiện có nhánh hiển thị một số dòng hard-coded khi không lấy được log; cần bỏ fallback giả lập đó hoặc đánh dấu dữ liệu demo nổi bật, đồng thời hiển thị trạng thái tải lỗi.
- Cần hoàn thiện phân trang/lọc audit, nội dung before/after an toàn, actor, thời gian, request/correlation ID và đảm bảo log không chứa OTP/ảnh thô/secret.
- Cần kiểm tra mọi thao tác thay đổi khách hàng, rate policy, eKYC và giao dịch đều bị backend từ chối khi thiếu quyền; UI ẩn nút không thay thế kiểm tra phía server.

## 9. Trạng thái lỗi, mạng và dữ liệu

- Cần hoàn thiện trạng thái loading/empty/error/pending cho các luồng nghiệp vụ, nhất là payment, transfer, eKYC, branch route và order fulfillment.
- Khi mất mạng sau khi commit, ứng dụng cần truy vấn lại bằng request ID để lấy kết quả cũ, không gửi một giao dịch mới hoặc báo thất bại sai.
- Dữ liệu tài chính cache phải có nhãn stale; thao tác offline không được làm thay đổi số dư authoritative.
- Cần xử lý retry cùng idempotency key, timeout provider, callback đến sai thứ tự, callback trùng và reconciliation cần thiết.

## 10. Yêu cầu phi chức năng chưa được xác minh đầy đủ

- **Bảo mật:** rà soát Firestore/Storage rules, role claims, quyền sở hữu, deep link, thông tin nhạy cảm trong log và cấu hình release.
- **Tính nhất quán:** xác nhận ledger debit/credit cân bằng và các cập nhật nội bộ nguyên tử.
- **Idempotency:** xác minh retry không tạo trùng transfer, payment, order hoặc money request.
- **Hiệu năng:** đo thời gian tải dashboard, latency API và phân vị p95 trên thiết bị/mạng/load đã nêu.
- **Khả năng truy cập:** rà soát nhãn đọc màn hình, kích thước chữ, tương phản và trạng thái không chỉ phân biệt bằng màu.
- **Độ tin cậy:** kiểm tra offline, timeout, provider lỗi và khôi phục sau khi backend đã commit.
- **Triển khai:** chưa có bằng chứng release đã ký, kiểm tra quyền/disclosure và phát hành Play Store như đề bài yêu cầu.
- **Kiểm thử:** có unit/backend test trong repo, nhưng tài liệu này không xác nhận toàn bộ use case SRS đã được kiểm thử trên thiết bị và tích hợp provider.

## 11. Checklist hoàn thành trước khi báo cáo đã đáp ứng SRS

- [x] Khách hàng cập nhật được các trường hồ sơ được phép; Officer sửa dữ liệu có lý do và audit before/after trong mã hiện tại.
- [ ] eKYC lưu bằng chứng an toàn, có trạng thái review/resubmission và quy trình retention.
- [ ] Face verification/step-up được triển khai hoặc ghi rõ phạm vi mô phỏng; giao dịch giá trị cao bị backend chặn nếu thiếu bằng chứng.
- [ ] OTP production hoặc mô phỏng được gắn nhãn rõ, có TTL, giới hạn thử, one-time use và ràng buộc intent.
- [ ] Màn savings/mortgage lấy số liệu và lịch tính từ backend, không dùng placeholder.
- [ ] Nạp/rút có quy trình xử lý hoàn tất và ledger; không nhầm request với giao dịch đã ghi sổ.
- [ ] Chuyển nội bộ chống overspend, replay, duplicate và phục hồi được sau mất mạng.
- [ ] Chuyển liên ngân hàng có provider/simulator, trạng thái pending và đối soát.
- [ ] Hóa đơn điện/nước, mobile top-up và checkout có quote, OTP, payment, webhook/IPN và fulfillment.
- [ ] Có luồng demo hoặc provider cho vé máy bay, vé phim, khách sạn và e-commerce.
- [ ] Routes API được cấu hình; kiểm tra quyền vị trí bị từ chối và thuật toán chọn tuyến đi bộ.
- [ ] Chatbot có action allowlist, kiểm tra quyền và không thể tự thực hiện giao dịch.
- [ ] Dữ liệu audit hiển thị là dữ liệu thật hoặc có nhãn demo; không có fallback giả mạo.
- [ ] Xác minh luồng hồ sơ/onboarding với Firebase Auth/Firestore đã cấu hình và rules mới đã deploy.
- [ ] Hoàn thành kiểm thử tích hợp, kiểm thử trên thiết bị và bằng chứng build/release/Play Store.

## Tệp và mã nguồn liên quan

- Android: `app/src/main/java/com/example/final_term/ui/customer/AccountDetailFragment.java`, `TransferFragment.java`, `MoneyRequestFragment.java`, `BranchLocatorFragment.java`, `UtilitiesHubDialogFragment.java`, `ui/profile/ProfileFragment.java`, `ui/officer/OfficerFeatureFragment.java`.
- Backend: `backend/src/modules/capabilities/capabilities.routes.ts`, `payments/`, `orders/`, `branches/`, `transfers/`, `ekyc/`, `accounts/`, `rates/`, `officer/`.
- README dự án và backend mô tả thêm các dependency, capability setup và giới hạn demo hiện tại.

# Audit luồng Officer đối chiếu Business Requirements

Ngày rà soát: 2026-11-25  
Phạm vi: mã Android, backend, Firestore rules và test hiện có trong repository. Đây là rà soát tĩnh; chưa xác nhận cấu hình Firebase/S3/Face provider hay toàn bộ luồng trên thiết bị thật.

## Kết luận

**Mức hoàn thiện phần Officer: khoảng 70% (khá, nhưng chưa đủ để kết luận đạt đầy đủ yêu cầu).** Đây là đánh giá định tính theo các luồng bắt buộc được đối chiếu bên dưới, không phải điểm chấm học phần.

Các phần cốt lõi đã có bằng chứng triển khai: tách vai trò, cấp hồ sơ khách hàng, sửa một số trường theo lý do, kiểm quyền phía server, audit thao tác, theo dõi eKYC và thay đổi policy lãi suất. Những khoảng trống lớn: không có quy trình Officer tự xác minh/duyệt danh tính sau khi hỗ trợ khách chụp; kiểm tra sinh trắc hiện là screening/enrollment tự động phụ thuộc dịch vụ ngoài, còn demo mode chỉ selfie; không có bằng chứng liên kết một yêu cầu sửa hồ sơ của khách với lần sửa của Officer; hiệu lực policy lãi chưa được chứng minh là áp dụng cho tài khoản savings hiện hữu; và cấu hình/deploy/test với dịch vụ thật chưa được xác nhận.

## Luồng Officer hiện tại

1. Officer đăng nhập bằng Firebase email/password. Ứng dụng lấy `users/{uid}`, chấp nhận profile active có role `OFFICER`, rồi điều hướng sang Officer home. Backend xác thực Actor role và trạng thái profile; khi cần quyền cụ thể, kiểm tra cả quyền trong Actor và `officers/{uid}`.
2. Từ Officer home, cán bộ mở danh sách khách hàng/tài khoản, hàng đợi eKYC, audit log hoặc màn policy lãi suất. UI kiểm tra role để vào màn; quyền chi tiết được backend bảo vệ.
3. Provision khách hàng tạo user ở trạng thái `ONBOARDING`, hồ sơ customer và checking account ban đầu. Giao dịch nghiệp vụ bị giới hạn trong trạng thái onboarding; khách nhận luồng email đặt mật khẩu và nộp eKYC để chuyển sang `ACTIVE`.
4. Sửa customer chấp nhận tên, email liên hệ, số điện thoại liên hệ, địa chỉ và lý do. Backend ghi before/after vào `customerChanges`, che email/điện thoại trong audit summary và ghi actor, thời gian, request ID. Có API xem change history.
5. Khách tự tạo/nộp eKYC qua app. Backend kiểm tra ảnh, gọi Face provider để enroll template; hồ sơ được đánh dấu `VERIFIED` hoặc `RESUBMIT_REQUIRED` tự động. Officer xem danh sách, trạng thái, lý do và ảnh bằng URL tạm thời; không có API approve/reject.
6. Officer đọc audit theo trang và bộ lọc có sẵn; có thể tạo rate policy theo product ID, basis points và thời điểm hiệu lực. Backend tạo version mới và audit thay đổi.

## Đối chiếu yêu cầu Officer trong đề

| Yêu cầu | Trạng thái | Bằng chứng trong mã | Khoảng trống / đánh giá |
|---|---|---|---|
| Hai nhóm user có giao diện và quyền khác nhau | **Đạt trong mã** | [MainActivity.java](/Users/phanvanduong/AndroidStudioProjects/final_term/app/src/main/java/com/example/final_term/MainActivity.java:53), [FirebaseAuthRepository.java](/Users/phanvanduong/AndroidStudioProjects/final_term/app/src/main/java/com/example/final_term/auth/FirebaseAuthRepository.java:104), [banking.ts](/Users/phanvanduong/AndroidStudioProjects/final_term/backend/src/core/banking.ts:45) | UI route theo role; backend bắt buộc role/status và permission. Cần xác nhận claims, seed hồ sơ officers và rules đã deploy đúng project. |
| Officer tạo tài khoản cho khách hàng | **Đạt một phần, luồng provision có thật** | [AccountListFragment.java](/Users/phanvanduong/AndroidStudioProjects/final_term/app/src/main/java/com/example/final_term/ui/customer/AccountListFragment.java:708), [officer.routes.ts](/Users/phanvanduong/AndroidStudioProjects/final_term/backend/src/modules/officer/officer.routes.ts:26), [customer-provisioning.service.ts](/Users/phanvanduong/AndroidStudioProjects/final_term/backend/src/modules/officer/customer-provisioning.service.ts:14) | Tạo khách hàng ở ONBOARDING và mở checking account mặc định; không thấy Officer chọn/khởi tạo savings hoặc mortgage khi provision. Kích hoạt phụ thuộc email/Firebase/eKYC thực tế. |
| Officer sửa dữ liệu khách theo yêu cầu khách | **Đạt một phần** | [AccountListFragment.java](/Users/phanvanduong/AndroidStudioProjects/final_term/app/src/main/java/com/example/final_term/ui/customer/AccountListFragment.java:1040), [officer.routes.ts](/Users/phanvanduong/AndroidStudioProjects/final_term/backend/src/modules/officer/officer.routes.ts:57) | Cho sửa full name, contact email/phone, address; bắt buộc reason; có lịch sử before/after. Chưa thấy cơ chế ghi nhận/yêu cầu của khách hay mã yêu cầu để chứng minh sửa đúng theo customer request. Email đăng nhập, UID, role, KYC status không nằm trong tập trường được sửa. |
| Officer hỗ trợ khách cung cấp định danh bằng sinh trắc/ảnh khuôn mặt | **Đạt một phần / demo phụ thuộc cấu hình** | [EkycDialogFragment.java](/Users/phanvanduong/AndroidStudioProjects/final_term/app/src/main/java/com/example/final_term/ui/ekyc/EkycDialogFragment.java:1), [ekyc.service.ts](/Users/phanvanduong/AndroidStudioProjects/final_term/backend/src/modules/ekyc/ekyc.service.ts:303), [OfficerFeatureFragment.java](/Users/phanvanduong/AndroidStudioProjects/final_term/app/src/main/java/com/example/final_term/ui/officer/OfficerFeatureFragment.java:271) | Có chụp/nộp bằng chứng và Officer xem ảnh. Quá trình capture/nộp nằm ở app khách; chưa thấy thao tác Officer khởi tạo hoặc hỗ trợ trực tiếp. Face template được enroll bởi hệ thống; FULL_ID không chứng minh ảnh CCCD thật hoặc so khuôn mặt trên CCCD với selfie; demo selfie không xác minh CCCD. Thiếu liveness/chống ảnh phát lại. |
| eKYC review của Officer | **Không tương đương duyệt thủ công** | [OfficerFeatureFragment.java](/Users/phanvanduong/AndroidStudioProjects/final_term/app/src/main/java/com/example/final_term/ui/officer/OfficerFeatureFragment.java:68), [ekyc.routes.ts](/Users/phanvanduong/AndroidStudioProjects/final_term/backend/src/modules/ekyc/ekyc.routes.ts:20) | Officer chỉ có GET queue/evidence; submit và quyết định VERIFIED/RESUBMIT_REQUIRED chạy tự động trong backend. Đề yêu cầu hỗ trợ cung cấp thông tin sinh trắc, không bắt buộc manual approval, vì vậy đây là giới hạn vận hành chứ không tự động tính là vi phạm yêu cầu gốc. |
| Officer sửa lãi suất tiết kiệm theo chính sách ngân hàng | **Đạt một phần** | [OfficerFeatureFragment.java](/Users/phanvanduong/AndroidStudioProjects/final_term/app/src/main/java/com/example/final_term/ui/officer/OfficerFeatureFragment.java:513), [rates.routes.ts](/Users/phanvanduong/AndroidStudioProjects/final_term/backend/src/modules/rates/rates.routes.ts:21), [rates.service.ts](/Users/phanvanduong/AndroidStudioProjects/final_term/backend/src/modules/rates/rates.service.ts:7) | Tạo version policy, effectiveFrom phải ở tương lai, giới hạn 0–100%, audit được ghi. UI yêu cầu nhập product ID tự do và không yêu cầu lý do/phê duyệt kép. Chưa xác minh dữ liệu policy áp dụng vào lợi nhuận savings trên mọi tài khoản. |
| Officer xem khách hàng/tài khoản và hỗ trợ vận hành | **Đạt một phần** | [AccountListFragment.java](/Users/phanvanduong/AndroidStudioProjects/final_term/app/src/main/java/com/example/final_term/ui/customer/AccountListFragment.java:708), [firestore.rules](/Users/phanvanduong/AndroidStudioProjects/final_term/firestore.rules:45) | Có danh sách và truy cập đọc account theo CUSTOMER_ADMIN. Cần xác minh màn danh sách lỗi/empty/loading và data thật; yêu cầu Business chỉ bắt buộc tạo/sửa, nên các thao tác hỗ trợ khác là phần bổ sung. |
| Audit các thao tác Officer | **Đạt một phần khá tốt** | [officer.routes.ts](/Users/phanvanduong/AndroidStudioProjects/final_term/backend/src/modules/officer/officer.routes.ts:14), [OfficerFeatureFragment.java](/Users/phanvanduong/AndroidStudioProjects/final_term/app/src/main/java/com/example/final_term/ui/officer/OfficerFeatureFragment.java:438), [banking.ts](/Users/phanvanduong/AndroidStudioProjects/final_term/backend/src/core/banking.ts:94) | Có phân trang, actor, resource, timestamp, request ID và audit CUSTOMER_UPDATE/RATE_UPDATE/ekyc. UI bộ lọc hiện tập trung TRANSFER_COMMIT/CUSTOMER_UPDATE, nên chưa duyệt được toàn bộ loại audit qua filter; phần chi tiết UI chưa hiển thị before/after summary dù server trả về. |

## Điểm mạnh về quyền và dữ liệu

- Không dựa vào ẩn nút: API provision/update/customer list/audit/eKYC/rates gọi `authorize` với quyền riêng.
- Firestore direct writes bị từ chối; audit log chỉ đọc qua backend. Officer đọc account/customer/transactions cần permission tương ứng.
- Provision có idempotency theo request ID, giữ chỗ customer number/contact phone, tạo account checking nguyên tử ở bước ghi hồ sơ.
- Customer update bắt buộc reason và lưu lịch sử riêng; audit summary mask email/phone.
- eKYC evidence không được đưa thẳng ra danh sách; cần endpoint cấp link tải tạm và quyền `EKYC_REVIEW`.

## Rủi ro và thiếu sót cần ưu tiên

1. **Liên kết yêu cầu khách với thao tác sửa:** thêm request/ticket ID hoặc xác nhận khách hàng trong lịch sử change; hiện reason là văn bản do cán bộ nhập, không chứng minh nguồn yêu cầu.
2. **Scope sản phẩm khi mở tài khoản:** xác nhận đề/SRS chỉ cần mở checking mặc định; nếu phải mở cả savings/mortgage thì luồng provision hiện chưa đáp ứng.
3. **Tính áp dụng rate policy:** có API ghi policy nhưng cần chứng minh query/calc savings lấy policy mới nhất theo product và effective date; đồng thời giới hạn product ID hợp lệ.
4. **eKYC phù hợp mức production:** cấu hình provider thật, liveness/anti-spoof, kiểm tra giấy tờ và quy trình xử lý lỗi; ghi rõ FULL_ID hiện không xác minh tính thật của CCCD.
5. **Phân quyền giao diện:** backend có permission model, nhưng UI hiện chủ yếu kiểm role. Cán bộ thiếu permission có thể vẫn thấy màn/nút rồi nhận 403; nên tải capability/permission để ẩn/disable đúng thao tác và giữ kiểm tra server.
6. **Audit đầy đủ:** bổ sung filter cho RATE_UPDATE, CUSTOMER_PROVISION, EKYC_AUTO_REVIEW; hiển thị summary before/after an toàn, phân trang liên tục và lỗi truy vấn. Xác nhận mọi thay đổi customer/rate/eKYC được ghi audit.
7. **Runtime verification:** cấu hình/deploy Firestore rules, Firebase Auth email action, S3 bucket/versioning, encryption key, Face provider/weights; chạy provision, sửa hồ sơ, eKYC, rates và từ chối permission trên môi trường tích hợp.

## Phán định tổng thể

| Nhóm năng lực Officer | Mức hoàn thiện |
|---|---:|
| Role-based login/navigation và kiểm quyền backend | **80%** |
| Tạo khách hàng và tài khoản | **75%** |
| Sửa hồ sơ theo yêu cầu | **70%** |
| Hỗ trợ eKYC/sinh trắc | **45%** |
| Điều chỉnh lãi suất | **65%** |
| Audit và tra cứu vận hành | **70%** |
| Tích hợp môi trường thật, kiểm chứng end-to-end | **Chưa xác minh** |

Tổng thể **khoảng 70%**: có nền tảng backend và luồng vận hành đáng kể, nhưng phần eKYC phụ thuộc cấu hình/demo, customer request chưa được liên kết thành bằng chứng, và tính hiệu lực của rate policy cùng tích hợp thật chưa được chứng minh. Không nên mô tả officer flow là hoàn tất production; có thể mô tả là **đã triển khai phần chính ở mức đồ án/demo có backend, cần xác minh tích hợp và khép các khoảng trống trên**.

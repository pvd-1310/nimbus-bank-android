# Kế hoạch cải tiến màn quản lý tài khoản thanh toán Officer

> **Dành cho agent triển khai:** Bắt buộc dùng `superpowers:executing-plans` hoặc `superpowers:subagent-driven-development` khi bắt đầu triển khai. Theo dõi từng bước bằng checkbox.

**Mục tiêu:** Officer có màn hình riêng để tra cứu tài khoản thanh toán (checking) theo số tài khoản hoặc tên chủ tài khoản, xem tên/số điện thoại và mở form tạo mới hoặc chỉnh sửa hồ sơ với dữ liệu hiện tại được điền sẵn.

**Kiến trúc:** Thêm một màn hình Android riêng theo pattern officer hiện có. Bổ sung endpoint officer có phân quyền để trả về các tài khoản `CHECKING` cùng thông tin tối thiểu của chủ tài khoản; tìm kiếm phía server để không giới hạn kết quả vào trang đã tải. Tạo mới vẫn dùng provisioning hiện có (tạo hồ sơ onboarding và checking), còn chỉnh sửa tiếp tục dùng API PATCH có audit log.

**Công nghệ:** Android Java, Navigation Component, Material Components, Backend TypeScript/Express, Zod, store abstraction và Vitest.

**Đặc tả:** Yêu cầu người dùng trong cuộc hội thoại ngày 2026-11-25; phạm vi chốt là tài khoản thanh toán (checking).

## Ràng buộc chung

- Chỉ hiển thị tài khoản loại `CHECKING` trên màn quản lý mới.
- Thông tin danh sách cần có: số tài khoản, tên chủ tài khoản, số điện thoại liên hệ; trạng thái tài khoản có thể hiện như nhãn phụ.
- Tìm kiếm phía server theo chính xác số tài khoản hoặc theo tiền tố tên chủ tài khoản, không phân biệt hoa thường; kết quả có phân trang. Phạm vi tìm kiếm tên tiền tố được chọn để hỗ trợ truy vấn có chỉ mục, tránh tải toàn bộ tài khoản về ứng dụng.
- Chỉnh sửa hồ sơ phải tải giá trị hiện có trước khi hiển thị form; ô để trống không được hiểu là xóa dữ liệu.
- Chỉ hiển thị và cho sửa các trường backend đang hỗ trợ: họ tên, email liên hệ, số điện thoại liên hệ, địa chỉ; lý do điều chỉnh là bắt buộc. Không sửa email đăng nhập, UID, role, số tài khoản hoặc số dư.
- Cấp mới cần thêm số CCCD 12 chữ số; tiếp tục dùng `POST /officer/customers`, cấp email đặt mật khẩu và provisioning checking hiện hữu; không tạo thêm một luồng mở tài khoản checking khác.
- CCCD chỉ nhập/lưu trong bước cấp mới; không hiển thị trong danh sách, không đưa vào tìm kiếm, phản hồi danh sách hoặc audit log.
- CCCD được xử lý như dữ liệu định danh nhạy cảm: chỉ backend lưu và đọc; không ghi giá trị vào log ứng dụng hoặc đưa qua Bundle/navigation arguments.
- Mọi bản ghi seed được coi là dữ liệu thật trong giao diện: hiển thị và tìm kiếm như các tài khoản khác, không gắn nhãn “demo” hoặc lọc bỏ.
- Chạy seed lại chỉ bổ sung trường còn thiếu; không thay hồ sơ, account data, giao dịch, hóa đơn hay chính sách hiện có. Trường `ownerNameSearch` là dữ liệu dẫn xuất và được backfill riêng.
- Các API officer phải kiểm tra quyền `CUSTOMER_ADMIN`; không mở quyền tra cứu qua API tài khoản khách hàng `/accounts`.

## Tập trung rà soát

- Tên hoặc số tài khoản không có kết quả: endpoint trả trang rỗng; UI nêu rõ không tìm thấy, không nhầm với lỗi tải.
- Tài khoản được tạo từ seed: xuất hiện đúng như tài khoản thông thường, có thể tìm theo số tài khoản/tên và mở chỉnh sửa theo quyền officer.
- Tên trùng hoặc một chủ có nhiều checking: mỗi tài khoản có dòng riêng, giữ đúng số tài khoản và chủ tài khoản.
- Số điện thoại lưu ở dạng `+84`: hiển thị nhất quán, dễ đọc; dữ liệu PATCH tiếp tục theo chuẩn E.164.
- Hồ sơ thiếu email/địa chỉ hoặc có dữ liệu null: form hiển thị trống có nhãn rõ, không ghi đè bằng chuỗi rỗng nếu officer không đổi trường đó.
- Tải form thất bại hoặc hồ sơ thay đổi sau khi mở danh sách: không mở form với dữ liệu giả/cũ; báo lỗi và cho tải lại.

## Cấu trúc file dự kiến

- Tạo `app/src/main/java/com/example/final_term/ui/officer/OfficerCheckingAccountsFragment.java`: tìm kiếm, danh sách checking, tải trạng thái rỗng/lỗi và điều hướng tới form.
- Tạo `app/src/main/res/layout/fragment_officer_checking_accounts.xml`: bố cục riêng cho danh sách, ô tìm kiếm và trạng thái tải.
- Sửa `app/src/main/res/navigation/nav_graph.xml`: đăng ký đích màn hình officer mới.
- Sửa `app/src/main/java/com/example/final_term/ui/officer/OfficerHomeFragment.java` và `app/src/main/res/layout/fragment_officer_home.xml`: đặt lối vào quản lý tài khoản thanh toán trên dashboard officer, giữ lối quản lý hồ sơ khách hàng riêng.
- Tạo `app/src/main/java/com/example/final_term/ui/officer/OfficerCustomerFormFragment.java` và layout tương ứng: dùng chung một màn hình cho chế độ tạo mới và chỉnh sửa; chế độ chỉnh sửa lấy hồ sơ theo UID trước khi đổ dữ liệu vào form.
- Sửa `app/src/main/java/com/example/final_term/ui/customer/AccountListFragment.java`: bỏ trách nhiệm render danh sách checking officer khỏi màn tài khoản dùng chung; giữ luồng hồ sơ khách hàng và các chức năng hiện hữu khác.
- Sửa `backend/src/modules/officer/officer.routes.ts`: thêm GET danh sách checking có bộ lọc `q` (mã tài khoản hoặc tên) và phân trang; thêm GET chi tiết hồ sơ theo UID để form lấy giá trị đầy đủ; tận dụng POST/PATCH hiện có. Danh sách trả `customerId` chỉ để mở form chỉnh sửa; UI không hiển thị mã này.
- Sửa `backend/src/modules/officer/officer.routes.ts` và `backend/src/modules/officer/customer-provisioning.service.ts`: nhận CCCD 12 chữ số khi cấp mới và lưu vào hồ sơ khách hàng; không đưa CCCD vào response hoặc audit log.
- Sửa `backend/src/database/template-schema.ts` và tài liệu hợp đồng API: khai báo trường CCCD mới và cập nhật mô tả POST cấp mới.
- Tạo `backend/src/modules/officer/officer-accounts.service.ts`: tra cứu checking theo accountId chính xác hoặc `ownerNameSearch` đã chuẩn hóa, ghép số điện thoại liên hệ; không trả UID, email đăng nhập, số dư hoặc dữ liệu eKYC nếu UI không cần.
- Sửa `backend/src/core/store.ts`, `backend/src/infrastructure/firestore-store.ts` và `backend/test/support/memory-store.ts`: thêm truy vấn tiền tố có giới hạn cho một trường, để lọc tên bằng Firestore range query và hỗ trợ tương ứng trong test.
- Sửa điểm tạo/cập nhật hồ sơ và seed: lưu trường `ownerNameSearch` chuẩn hóa trên tài khoản checking và cập nhật khi họ tên đổi; bổ sung backfill cho checking hiện hữu. Seed chỉ thêm trường còn thiếu và không ghi đè hồ sơ hoặc financial records đã có.
- Tạo `backend/src/scripts/seed-preserve.ts` và `backend/test/seed-preserve.test.ts`: kiểm tra seed giữ nguyên thông tin hiện tại và chỉ điền dữ liệu còn thiếu.
- Tạo `backend/test/officer-accounts.test.ts`: kiểm tra phân quyền, lọc loại checking, tìm kiếm, phân trang và dữ liệu trả về tối thiểu.
- Sửa test officer route hiện có (nếu có) để bao phủ GET hồ sơ chi tiết và chỉ cho phép customer-admin.

## Task 1: API tra cứu tài khoản thanh toán Officer

**Files:**
- Tạo `backend/src/modules/officer/officer-accounts.service.ts`
- Sửa `backend/src/modules/officer/officer.routes.ts`
- Tạo `backend/test/officer-accounts.test.ts`

- [ ] Viết test cho GET danh sách: chỉ trả `CHECKING`; tìm theo số tài khoản chính xác và tiền tố tên không phân biệt hoa thường; trả trường hiển thị `accountId`, `ownerName`, `contactPhone`, `status` và `customerId` nội bộ cho điều hướng; không trả số dư, CCCD hoặc dữ liệu seed marker.
- [ ] Viết test cho phân trang, tìm kiếm không khớp, và trả 403 khi actor thiếu `CUSTOMER_ADMIN`.
- [ ] Viết test GET hồ sơ theo UID để lấy `fullName`, `contactEmail`, `contactPhone`, `address`; chỉ customer-admin được đọc và UID không phải CUSTOMER trả 404.
- [ ] Thêm trường chuẩn hóa tên tìm kiếm vào tài khoản checking khi provision/seed/chỉnh sửa tên; viết migration/backfill an toàn, lặp lại được cho checking hiện hữu.
- [ ] Thêm truy vấn tiền tố có giới hạn vào store interface và các implementation; service dùng accountId chính xác cho tra cứu số tài khoản, `ownerNameSearch` cho tìm tên, đồng thời duy trì cursor/phân trang ổn định.
- [ ] Cập nhật POST `/officer/customers` và provisioning để xác thực CCCD đúng 12 chữ số, lưu vào hồ sơ khách hàng và không trả số CCCD trong response/audit log.
- [ ] Thêm service nối tên/số điện thoại từ users và account status; không đọc hoặc trả các trường không cần cho màn danh sách.
- [ ] Đăng ký `GET /officer/checking-accounts?q=&limit=&cursor=` và `GET /officer/customers/:id` với validation đầu vào/đầu ra phù hợp; giữ nguyên POST/PATCH hiện có.
- [ ] Chạy test backend mục tiêu; kết quả mong đợi: toàn bộ test mới và test officer liên quan PASS.

## Task 2: Màn danh sách và tìm kiếm checking

**Files:**
- Tạo `app/src/main/java/com/example/final_term/ui/officer/OfficerCheckingAccountsFragment.java`
- Tạo `app/src/main/res/layout/fragment_officer_checking_accounts.xml`
- Sửa `app/src/main/res/navigation/nav_graph.xml`
- Sửa `app/src/main/java/com/example/final_term/ui/officer/OfficerHomeFragment.java`
- Sửa `app/src/main/res/layout/fragment_officer_home.xml`

- [ ] Tạo destination và lối vào từ dashboard officer với nhãn “Tài khoản thanh toán”.
- [ ] Thiết kế danh sách theo thẻ/dòng dễ quét: số tài khoản nổi bật, tên chủ tài khoản, số điện thoại và trạng thái; có nút “Cấp mới hồ sơ khách hàng”.
- [ ] Gọi endpoint ở Task 1, debounce ô tìm kiếm và tải lại từ trang đầu khi từ khóa đổi; phân trang tiếp tục theo cursor.
- [ ] Thể hiện riêng trạng thái đang tải, lỗi có nút thử lại, không có tài khoản và không có kết quả tìm kiếm.
- [ ] Chọn một dòng mở form chỉnh sửa với UID khách hàng; không đưa thông tin nhạy cảm qua Bundle ngoài ID cần thiết.
- [ ] Rà soát accessibility: nhãn tìm kiếm, nội dung mô tả thao tác dòng và cỡ chạm phù hợp.

## Task 3: Form cấp mới và chỉnh sửa hồ sơ

**Files:**
- Tạo `app/src/main/java/com/example/final_term/ui/officer/OfficerCustomerFormFragment.java`
- Tạo `app/src/main/res/layout/fragment_officer_customer_form.xml`
- Sửa `app/src/main/res/navigation/nav_graph.xml`
- Sửa `app/src/main/java/com/example/final_term/ui/customer/AccountListFragment.java`

- [ ] Tạo form toàn màn hình dùng chung: chế độ cấp mới có họ tên, email đăng nhập, số điện thoại, CCCD 12 chữ số, mã khách hàng tùy chọn, địa chỉ tùy chọn; chế độ chỉnh sửa có họ tên, email liên hệ, số điện thoại liên hệ, địa chỉ và lý do bắt buộc. CCCD chỉ xuất hiện ở chế độ cấp mới.
- [ ] Ở chế độ chỉnh sửa, gọi `GET /officer/customers/:id` trước; chỉ render form sau khi tải xong và điền sẵn đầy đủ dữ liệu hiện có.
- [ ] Dùng nhãn trường cố định (không chỉ dùng placeholder), nhóm trường rõ ràng, hỗ trợ cuộn khi bàn phím mở; giữ chỉ dẫn định dạng điện thoại và lỗi validation cạnh trường.
- [ ] Chỉ gửi trường chỉnh sửa nào đã thay đổi, kèm `reason`; nếu không thay đổi trường nào thì không gửi PATCH.
- [ ] Chế độ cấp mới giữ idempotency key khi retry cùng nội dung, gọi POST hiện tại và báo rõ tài khoản checking được tạo sau khi thành công.
- [ ] Kiểm tra định dạng CCCD ở UI để báo lỗi sớm; backend vẫn xác thực bắt buộc và giá trị CCCD không được đưa vào log/thông báo client.
- [ ] Sau khi cấp mới/chỉnh sửa thành công, quay lại danh sách, tải lại dữ liệu và giữ từ khóa tìm kiếm hiện hành; xóa dialog tạo/sửa cũ khỏi luồng officer.

## Task 4: Tích hợp và kiểm tra hồi quy

**Files:**
- Các file UI/API nêu trên
- Test backend và Android hiện có liên quan

- [ ] Kiểm tra luồng officer từ dashboard → danh sách → tìm số/tên → mở sửa → xác nhận dữ liệu được điền sẵn → lưu → danh sách cập nhật.
- [ ] Kiểm tra cấp mới từ màn danh sách, CCCD sai định dạng, gửi lại khi mất phản hồi và trạng thái email đặt mật khẩu.
- [ ] Kiểm tra tài khoản từ seed xuất hiện/tìm kiếm/chỉnh sửa như dữ liệu bình thường; test seed chạy lại không làm đổi hồ sơ hoặc số dư đã tồn tại.
- [ ] Kiểm tra customer-admin có quyền; actor không đủ quyền không xem danh sách hoặc chi tiết hồ sơ.
- [ ] Chạy bộ kiểm tra backend và build Android phù hợp với repo; ghi nhận lỗi có sẵn nếu có.
- [ ] Rà soát diff để bảo đảm không làm đổi luồng khách hàng, eKYC, audit, cấu hình lãi suất hoặc tài khoản tiết kiệm/vay.

## Tự rà soát

- Đã phủ tìm kiếm, lọc đúng checking, thông tin tối thiểu, prefill form, ghi audit qua PATCH hiện hữu, cấp mới theo provisioning hiện hữu kèm CCCD, phân quyền và điều kiện lỗi/tải rỗng.
- Đã thêm yêu cầu coi seed là dữ liệu thật, không gắn nhãn demo và không ghi đè khi seed chạy lại.
- Chi tiết GET hồ sơ officer là API bổ sung cần thiết vì danh sách hiện nay trả `customers` mà code UI chưa có hợp đồng đáng tin cậy để lấy đầy đủ `users` contact fields; account GET hiện tại chỉ cho chủ tài khoản của chính actor.
- Chưa thực hiện code hoặc chạy kiểm tra; đây là kế hoạch để người dùng duyệt.

# Chọn tài khoản Nimbus hoặc thẻ Stripe cho hóa đơn và tiện ích

## Ý định

Cho phép khách hàng chọn phương thức thanh toán ngay trên màn hình xác nhận chung: tài khoản thanh toán Nimbus hoặc thẻ Stripe. Áp dụng cho hóa đơn điện, nước, dịch vụ khác và toàn bộ đơn tiện ích hiện có (nạp điện thoại, vé máy bay, vé phim, khách sạn, mua sắm).

## Hiện trạng

- Các loại hóa đơn và đơn tiện ích đều đi qua `TransactionExecutionFragment`.
- `TransactionFlowRequest` có `sourceAccountId`; giao diện có thể ghi “Tài khoản thanh toán” khi giá trị này tồn tại.
- API thanh toán chấp nhận `sourceAccountId`, nhưng `Payments.prepare()` không dùng nó để chọn nguồn tiền. Backend hiện luôn tạo payment Stripe cho hóa đơn/đơn hàng.
- Stripe được xác nhận qua PaymentSheet và webhook. Fulfillment đơn tiện ích chạy qua outbox sau khi payment thành công.
- Backend có sẵn mẫu trừ số dư và ghi ledger trong transaction nội bộ; việc trả hóa đơn cần bổ sung nhánh tương ứng trong nghiệp vụ payment.

## Mục tiêu

- Hiển thị lựa chọn **Tài khoản Nimbus** hoặc **Thẻ Stripe** ngay trong bước xác nhận hiện có.
- Chỉ tài khoản thanh toán VND đang hoạt động và thuộc người dùng được dùng để trả từ Nimbus.
- Thanh toán Nimbus phải kiểm tra lại số dư phía server, trừ tiền, ghi ledger, đánh dấu hóa đơn đã trả và ghi transaction trong một transaction nguyên tử.
- Sau khi thanh toán đơn tiện ích bằng Nimbus, kích hoạt cùng outbox fulfillment hiện dùng cho Stripe.
- Giữ OTP và yêu cầu xác minh khuôn mặt hiện có cho cả hai phương thức. Stripe tiếp tục xác nhận qua webhook; Nimbus hoàn tất sau khi server xác nhận OTP và ghi sổ.
- Biên nhận và lịch sử thể hiện đúng phương thức, tài khoản nguồn và trạng thái cuối.

## Không thuộc phạm vi

- Không thay đổi luồng nạp tiền, chuyển khoản, rút tiền hoặc các loại giao dịch khác.
- Không tích hợp nhà cung cấp điện/nước, viễn thông, hãng bay, rạp phim, khách sạn hoặc cửa hàng thật; fulfillment hiện có vẫn được đánh dấu demo.
- Không thêm phương thức thẻ mới hay thay đổi cấu hình Stripe PaymentSheet.
- Không thay đổi schema nghiệp vụ hóa đơn hiện có ngoài các trường giao dịch cần để nhận diện phương thức/tài khoản nguồn.

## Hành vi dự kiến

1. Khi xác nhận hóa đơn hoặc đơn tiện ích, người dùng chọn Nimbus hoặc Stripe tại cùng màn hình.
2. Với Nimbus, màn hình dùng tài khoản đã được truyền vào luồng nếu có; nếu chưa có, người dùng chọn trong danh sách tài khoản VND đủ điều kiện. Hiển thị số dư khả dụng và báo khi số dư thấp hơn tổng tiền.
3. Lựa chọn phương thức, mã hóa đơn/đơn hàng, số tiền và tài khoản nguồn được gắn với intent trước khi OTP phát hành. Đổi phương thức hoặc tài khoản sau đó cần intent/OTP mới.
4. Sau OTP, Nimbus chạy commit nội bộ: server kiểm tra lại quyền sở hữu, trạng thái tài khoản, tiền tệ, số dư, hóa đơn chưa trả và báo giá còn hiệu lực. Nếu điều kiện không đạt, không ghi nợ hoặc đánh dấu đã trả.
5. Stripe tiếp tục tạo/lấy lại PaymentIntent, mở PaymentSheet và chỉ coi thanh toán thành công khi webhook hợp lệ cập nhật backend.
6. Cả hai nhánh dùng idempotency; retry không ghi nợ hai lần, không trả hóa đơn hai lần và không enqueue fulfillment trùng.
7. Lịch sử và biên nhận phân biệt “Tài khoản Nimbus” với “Thẻ Stripe”; chỉ hiện biên nhận thành công khi backend báo trạng thái cuối thành công.
8. Nếu server đã ghi nợ Nimbus nhưng phản hồi bị mất, app truy vấn lại cùng intent/request ID. Intent đã `COMMITTED` trả về biên nhận; retry cùng request ID không tạo khoản ghi nợ mới và không yêu cầu OTP lần nữa.
9. Thanh toán và đánh dấu hóa đơn `PAID` là kết quả tiền tệ chính thức. Nếu fulfillment đơn tiện ích bị lỗi sau đó, giữ giao dịch đã settle và đơn ở trạng thái chờ/reconciliation để retry; không tự hoàn tiền trong luồng này.

## Ràng buộc bảo mật và dữ liệu

- Backend là nguồn quyết định cuối về phương thức, tài khoản, số tiền, quyền sở hữu và số dư; không tin số dư hoặc giá do client gửi.
- Intent digest phải ràng buộc phương thức và `sourceAccountId` cho nhánh Nimbus để không thể đổi nguồn tiền sau OTP.
- Khấu trừ tài khoản, ledger debit, trạng thái bill, transaction, intent và outbox phải được cập nhật nguyên tử và idempotent.
- Lưu payment record cho cả Nimbus và Stripe với phương thức, trạng thái và account nguồn tương ứng để API recovery có thể trả cùng một trạng thái chuẩn.
- Ghi outbox trong cùng transaction settle. Gọi xử lý outbox sau khi Nimbus settle; nếu xử lý gián đoạn, giữ job để retry/reconciliation mà không đảo trạng thái thanh toán.
- Nhánh Stripe giữ yêu cầu chữ ký webhook, kiểm tra amount/currency/purpose/owner và chống event lặp như hiện tại.
- Nếu Nimbus bị thiếu tiền hoặc tài khoản không hợp lệ, giao dịch thất bại rõ ràng, số dư và bill giữ nguyên, người dùng có thể chọn Stripe bằng một intent mới.

## Phương án

1. **Bổ sung chọn phương thức vào màn hình xác nhận và mở rộng payment intent/commit hiện có — chọn phương án này.** Tái sử dụng OTP, recovery, biên nhận và outbox; backend dùng nhánh settle nội bộ hoặc Stripe theo intent đã xác thực. Ít thay đổi luồng giao diện và giữ một điểm điều phối giao dịch.
2. **Tạo màn hình và API thanh toán Nimbus riêng.** Tách trách nhiệm rõ nhưng tạo thêm nhánh UX, intent, retry, receipt và fulfillment cần đồng bộ với Stripe; dễ phát sinh trạng thái xử lý khác nhau.

## Quyết định giao diện

Đã chọn hiển thị hai phương thức ngay trên màn hình xác nhận giao dịch hiện có. Cách trình bày chi tiết, thứ tự/giá trị mặc định của phương thức và việc chọn tài khoản khi có nhiều tài khoản là quyết định triển khai; phải đảm bảo người dùng thấy rõ phương thức và tài khoản sẽ bị ghi nợ trước khi OTP.

## Tiêu chí hoàn tất

- Người dùng có thể trả mọi loại hóa đơn/đơn tiện ích bằng tài khoản Nimbus đủ điều kiện hoặc thẻ Stripe.
- Tài khoản thiếu tiền, không thuộc người dùng, không hoạt động hoặc sai tiền tệ không bị ghi nợ; bill/order giữ đúng trạng thái.
- Nimbus settlement không gọi Stripe; Stripe settlement không trừ tài khoản Nimbus.
- Retry, callback lặp và webhook lặp không tạo ghi nợ, giao dịch, trạng thái PAID hoặc fulfillment trùng.
- Mất phản hồi sau khi Nimbus đã ghi sổ có thể recovery bằng cùng intent và hiển thị biên nhận mà không tạo OTP hoặc ghi nợ mới.
- Lỗi fulfillment không đảo ghi nợ/PAID và không tự hoàn tiền; job còn khả năng retry hoặc được đánh dấu reconciliation.
- Giao diện phục hồi đúng trạng thái sau khi app bị dừng/khởi động lại và không hiện nhãn phương thức sai.
- Unit/integration tests bao phủ hai phương thức, quyền sở hữu, số dư thiếu, idempotency, xác thực OTP và fulfillment đơn tiện ích.

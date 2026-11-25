# Chọn ngân hàng nhận khi chuyển liên ngân hàng

## Ý định

Thêm trường chọn ngân hàng vào màn hình chuyển đến ngân hàng khác để khách hàng chọn đúng ngân hàng nhận. Luồng hiện tại dùng cố định một mã ngân hàng và chỉ có hai tài khoản mẫu.

## Thiết kế được chọn

- Giữ nguyên các bước chuyển khoản hiện tại: nguồn, người nhận, số tiền, xác nhận, OTP, ghi sổ và biên nhận.
- Chỉ bổ sung ô chọn ngân hàng nhận cho chuyển khoản ngân hàng khác, với Vietcombank, BIDV, VietinBank, Techcombank và MB là lựa chọn ban đầu.
- Gửi lựa chọn ngân hàng theo intent hiện tại, hiển thị ở xác nhận/biên nhận và lưu thành trường ngân hàng nhận trên giao dịch.
- Tiếp tục dùng cơ chế ghi sổ mô phỏng hiện tại; không thêm kết nối mạng liên ngân hàng thật.

## Phạm vi

- Bao gồm cập nhật form Android, payload chuyển khoản, dữ liệu intent/giao dịch và kiểm thử backend/UI liên quan.
- Giữ nguyên quy trình người nhận, OTP, xác nhận và ghi sổ; chỉ chuyển khoản ngoài ngân hàng có trường ngân hàng nhận.
- Không kết nối mạng thanh toán liên ngân hàng thật; lựa chọn ngân hàng sẽ được ghi nhận chính xác trong luồng mô phỏng để kiểm thử.

## Phương án

- Danh sách ngân hàng phổ biến đi qua luồng chuyển hiện tại: được chọn vì người dùng cần chọn ngân hàng nhận, còn backend chưa kết nối rail thật.
- Chỉ hiện ngân hàng duy nhất đang mã hóa trong backend: không đáp ứng mục đích chọn ngân hàng, nên không chọn.

## Quyết định còn mở

- Danh sách mặc định là năm ngân hàng nêu trên; có thể thay tên hoặc bổ sung trước khi triển khai.

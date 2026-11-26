# Kế hoạch: Màn hình chi tiết lịch sử giao dịch và thao tác

## Mục tiêu
Thay hộp thoại chi tiết giao dịch của khách hàng bằng màn hình chỉ xem; đưa lịch sử giao dịch và lịch sử thao tác của officer về nội dung tiếng Việt dễ hiểu, hạn chế mã kỹ thuật; mở màn hình riêng khi xem chi tiết.

## Các bước
1. Thêm màn hình chi tiết lịch sử dùng chung, trình bày loại, số tiền, trạng thái, thời gian, tài khoản và ngân hàng nhận (nếu có); hỗ trợ chi tiết thao tác officer.
2. Khai báo điều hướng và đổi click lịch sử giao dịch sang màn hình chi tiết, giữ phạm vi truy vấn theo vai trò hiện tại.
3. Dịch nội dung lịch sử thao tác sang nhãn thân thiện; mở chi tiết thao tác chỉ đọc thay cho hộp thoại.
4. Chỉnh placeholder/tìm kiếm nếu đang gợi ý mã kỹ thuật; build APK debug để xác nhận biên dịch.

## Kiểm tra
Chạy `./gradlew --offline assembleDebug`. Không thêm hoặc chạy test.

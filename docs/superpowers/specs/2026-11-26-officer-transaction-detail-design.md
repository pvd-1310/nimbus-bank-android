# Thiết kế lịch sử giao dịch và thao tác

## Mục tiêu

Redesign hai luồng trên trang điều hành: **Lịch sử GD** và **Lịch sử thao tác**. Thay nội dung kỹ thuật bằng mô tả nghiệp vụ tiếng Việt, và mở màn chi tiết trực quan khi người dùng chạm vào một dòng.

Ảnh hộp thoại hiện tại của khách hàng là điểm xuất phát: thay hộp thoại đó bằng màn chi tiết giao dịch toàn màn hình. Khách hàng và officer dùng cùng phong cách trình bày giao dịch; quyền xem dữ liệu vẫn theo vai trò hiện có. Officer có thêm màn chi tiết thao tác riêng.

## Phạm vi và giới hạn

- Redesign danh sách giao dịch và danh sách thao tác trên giao diện officer.
- Thay hộp thoại chi tiết giao dịch của khách hàng bằng màn hình toàn màn hình cùng phong cách với màn giao dịch của officer.
- Mọi màn chi tiết chỉ đọc; không có thao tác sửa, thực hiện lại hay thay đổi giao dịch.
- Ẩn UID, resource ID, request ID và mã sự kiện khỏi nội dung chính. Dùng tên, loại tài khoản và mô tả dễ hiểu khi dữ liệu sẵn có.
- Khách hàng chỉ xem giao dịch của chính mình; officer chỉ xem theo quyền hiện có.
- Không thay đổi xử lý giao dịch hay dữ liệu gốc.

## Các hướng đã cân nhắc

1. **Một màn chi tiết giao dịch dùng chung, cùng với màn chi tiết thao tác riêng cho officer (đề xuất).** Dùng cùng thứ bậc thông tin và kiểu trình bày cho giao dịch của cả khách hàng và officer; giới hạn dữ liệu theo vai trò. Nhật ký thao tác có cấu trúc riêng nên có màn chi tiết riêng.
2. Giữ dialog cho khách hàng và chỉ tạo màn mới cho officer. Cách này không đáp ứng yêu cầu cập nhật giao diện khách hàng trong ảnh.
3. Gộp chi tiết giao dịch và thao tác vào một màn chung. Hai loại bản ghi có ý nghĩa khác nhau, dễ gây nhầm giữa dòng tiền và nhật ký kiểm toán.

## Thiết kế đề xuất

### Lịch sử GD

- Mỗi dòng dùng tên giao dịch dễ hiểu, ngày giờ, trạng thái và số tiền; thể hiện rõ tiền vào/ra.
- Chạm dòng mở màn **Chi tiết giao dịch** toàn màn hình cho cả khách hàng và officer.
- Màn chi tiết hiển thị loại giao dịch, số tiền, ngày giờ, trạng thái và tài khoản nguồn. Giao dịch liên ngân hàng hiển thị thêm ngân hàng nhận, người thụ hưởng và tài khoản nhận khi dữ liệu có sẵn.
- Dùng tên/loại tài khoản thân thiện thay cho document ID như `local_demo_checking`. Che số tài khoản nếu cần theo vai trò.

### Lịch sử thao tác

- Dịch mã thao tác thành tiêu đề tiếng Việt, ví dụ: `DEMO_MOVEMENT_INTENT` → “Đã khởi tạo giao dịch”, `DEMO_MOVEMENT_PENDING` → “Giao dịch đang xử lý”, `DEMO_MOVEMENT_SETTLED` → “Giao dịch hoàn tất”, `CUSTOMER_UPDATE` → “Đã cập nhật hồ sơ khách hàng”.
- Mỗi dòng nêu người thực hiện, đối tượng liên quan và thời điểm; không hiển thị UID hay mã đích thô.
- Chạm dòng mở màn **Chi tiết thao tác**, gồm mô tả sự kiện, người thực hiện, thời điểm, đối tượng, lý do và nội dung trước/sau nếu có.
- Che hoặc lược bỏ dữ liệu nhạy cảm trong phần trước/sau theo quy tắc hiện có.

## Cách triển khai dự kiến

- Giữ hai nút **Lịch sử GD** và **Lịch sử thao tác** trên officer home.
- Cập nhật `TransactionHistoryFragment` và `OfficerFeatureFragment` để hiển thị nội dung nghiệp vụ thay cho hộp thoại/dòng mã thô.
- Thêm một màn chi tiết giao dịch toàn màn hình dùng chung cho khách hàng và officer, và một màn chi tiết thao tác cho officer.
- Chuyển dữ liệu bản ghi đã tải sang màn chi tiết. Nếu audit API hiện chỉ trả UID hoặc mã đối tượng, bổ sung dữ liệu hiển thị từ backend nhưng không thay đổi API ghi.
- Dùng tiếng Việt cho nhãn; bố cục cuộn được và có nút quay lại trên màn nhỏ.

## Tiêu chí hoàn thành

- Hai danh sách officer mô tả sự kiện bằng nội dung dễ hiểu, không đưa mã kỹ thuật vào phần chính.
- Khách hàng và officer mở được màn chi tiết giao dịch toàn màn hình; dữ liệu chỉ nằm trong phạm vi quyền của vai trò.
- Officer mở được chi tiết thao tác với nội dung trước/sau khi có sẵn.
- Tất cả màn chi tiết chỉ đọc; không thay đổi nghiệp vụ giao dịch.
- Mọi nội dung UI mới dùng tiếng Việt.

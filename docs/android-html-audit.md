# Đối chiếu thiết kế HTML với Android

Ma trận dưới đây ghi nhận điểm vào và chênh lệch theo mã nguồn. Mức độ khớp hình ảnh cần kiểm tra trên thiết bị.

| Thiết kế HTML | Phần tương ứng trên Android | Chênh lệch |
|---|---|---|
| Splash_Screen | Biểu tượng khởi động hệ thống | Thiếu bố cục thương hiệu riêng |
| Onboarding_1 | WelcomeGuide, bước 1 | Đã thêm. Minh họa dùng icon ví hiện có |
| Onboarding_2 | WelcomeGuide, bước 2 | Đã thêm. Minh họa dùng icon bảo mật hiện có |
| Onboarding_3 | WelcomeGuide, bước 3 | Đã thêm. Nội dung mô tả theo khả năng thực tế của chatbot |
| Login_Screen | LoginFragment | Có form đăng nhập, cần đối chiếu khoảng cách và nhóm phương thức xác thực |
| Home_Screen | CustomerHomeFragment | Có số dư, thao tác nhanh và giao dịch gần đây |
| Wallet_Screen | AccountListFragment | Dùng chung đích điều hướng với tab tài khoản |
| Cards_Screen | AccountListFragment | Thiếu màn thẻ riêng |
| Account_Details | AccountDetailFragment | Có màn chi tiết. Cần đối chiếu từng trường và thao tác |
| Savings_Detail | SavingsDetailFragment | Có màn chi tiết tiết kiệm |
| Mortgage_Detail | MortgageDetailFragment | Có màn chi tiết khoản vay |
| Deposit_Withdraw | TransactionExecutionFragment | Ghép vào luồng giao dịch dùng chung |
| Transfer_Screen | TransferFragment | Có chọn phương thức và nhập thông tin chuyển tiền |
| Transfer_Confirmation | TransactionExecutionFragment | Là bước trong luồng giao dịch |
| OTP_BottomSheet | OtpBottomSheetDialog | Có bottom sheet nhập mã |
| Success_Screen | TransactionExecutionFragment | Trạng thái kết quả hiển thị trong luồng giao dịch |
| Transactions_History | TransactionHistoryFragment | Có lịch sử và bộ lọc |
| Quick_Actions_BottomSheet | QuickActionsBottomSheet | Có bottom sheet từ nút cộng |
| Profile_Screen | ProfileFragment | Có hồ sơ, thông tin liên hệ và điểm vào eKYC |
| eKYC_CCCD | EkycDialogFragment | Bước giấy tờ nằm chung trong dialog eKYC |
| eKYC_Face | EkycDialogFragment, InAppCameraDialogFragment | Bước khuôn mặt dùng dialog và camera |
| Utilities_Hub | UtilitiesHubFragment, UtilitiesHubDialogFragment | Có cả màn hình và bottom sheet |
| Electricity_Bill | BillPaymentFragment | Dùng layout chung cho các loại hóa đơn |
| Mobile_Topup | MobileTopupFragment | Có màn nạp điện thoại |
| Flight_Booking | UtilityCatalogFragment | Dùng danh mục chung thay cho bố cục đặt vé riêng |
| Branch_Locator | BranchLocatorFragment | Có bản đồ và danh sách chi nhánh |
| AI_Assistant | ChatbotDialogFragment | Hiển thị trong dialog thay cho màn độc lập |

Khi thêm trường chưa có đối ứng, gắn nhãn dữ liệu mẫu ngay tại khu vực hiển thị và giữ giá trị ngoài dữ liệu dùng để gửi giao dịch.

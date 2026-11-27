package com.example.final_term.ui.transaction;

import org.junit.Test;
import static org.junit.Assert.*;

public class TransactionPresentationTest {
    @Test
    public void internalTransferConfirmationIncludesVerifiedRecipientAndHidesBlankNote() {
        TransactionFlowRequest req = TransactionFlowRequest.internalTransfer(
                "acc_1234564125", 1_500_000L, "VND",
                "acc_999", "NGUYỄN VĂN AN", "0901234567", "");
        TransactionPresentation.ConfirmationModel model = TransactionPresentation.buildConfirmation(req, 0L);
        assertEquals("Xác nhận chuyển tiền", model.headerTitle);
        assertEquals("Tổng số tiền chuyển", model.heroLabel);
        assertEquals("1.500.000 ₫", model.amountFormatted);
        assertTrue(model.feeFree);
        assertEquals(5, model.rows.size()); // source, recipient(verified), bank(Nimbus Bank), target, method; note omitted
        assertTrue(model.rows.get(1).verifiedBadge);
        assertEquals("Nimbus Bank", model.rows.get(2).value);
    }

    @Test
    public void externalTransferOmitsRecipientRowWhenNameMissing() {
        TransactionFlowRequest req = TransactionFlowRequest.externalTransfer(
                "acc_4125", 2_000_000L, "VND",
                "VCB", "Vietcombank", "0000000001", "Tra tien nha");
        TransactionPresentation.ConfirmationModel model = TransactionPresentation.buildConfirmation(req, 0L);
        for (TransactionPresentation.DetailRow row : model.rows) {
            assertNotEquals("Người thụ hưởng", row.label);
        }
        assertEquals("Nội dung", model.rows.get(model.rows.size() - 1).label);
        assertEquals("Tra tien nha", model.rows.get(model.rows.size() - 1).value);
    }

    @Test
    public void depositAndWithdrawalBuildDistinctMinimalRows() {
        TransactionFlowRequest dep = TransactionFlowRequest.deposit("acc_88884125", 500_000L, "VND");
        TransactionPresentation.ConfirmationModel depModel = TransactionPresentation.buildConfirmation(dep, 0L);
        assertEquals("Xác nhận nạp tiền", depModel.headerTitle);
        assertEquals("Tổng tiền nạp", depModel.heroLabel);
        assertEquals(3, depModel.rows.size()); // source(Stripe), target account, method

        TransactionFlowRequest wdr = TransactionFlowRequest.withdrawal("acc_88884125", 300_000L, "VND");
        TransactionPresentation.ConfirmationModel wdrModel = TransactionPresentation.buildConfirmation(wdr, 0L);
        assertEquals("Xác nhận rút tiền", wdrModel.headerTitle);
        assertEquals("Tổng tiền rút", wdrModel.heroLabel);
        assertEquals(3, wdrModel.rows.size()); // source account, destination(cash), method
    }

    @Test
    public void receiptShortensHexIdPreservingFullCopyValueAndFormatsTimestamp() {
        TransactionFlowRequest req = TransactionFlowRequest.billPayment(
                "bill_evn_01", "Hóa đơn tiền điện", "EVNHCMC", "PE01000123",
                "Kỳ 11/2026", 450_000L, "VND", null);
        String fullId = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890";
        TransactionPresentation.ReceiptModel receipt = TransactionPresentation.buildReceipt(
                req, fullId, "", 0L, 1794820800000L);
        assertEquals("BIÊN LAI THANH TOÁN", receipt.miniHeaderTitle);
        assertEquals("Thanh toán thành công", receipt.headline);
        assertEquals("ABCDEF123456", receipt.rows.get(0).value);
        assertEquals(fullId, receipt.rows.get(0).copyValue);
        assertTrue(receipt.timestampText.contains("Tháng"));
        assertTrue(receipt.shareText.contains(fullId));
    }

    @Test
    public void shortTransactionIdHandlesEmptyAndProviderReference() {
        assertEquals("DEMO-ABC12345", TransactionPresentation.shortTransactionId("DEMO-ABC12345", "fullhash"));
        assertEquals("—", TransactionPresentation.shortTransactionId("", ""));
    }

    @Test
    public void billAndOrderPaymentConfirmationSyncsSourceAndMethodOnSwitch() {
        TransactionFlowRequest billReq = TransactionFlowRequest.billPayment(
                "bill_evn_01", "Thanh toán tiền điện", "EVNHCMC", "PE01000123",
                null, 450_000L, "VND", null);

        TransactionPresentation.ConfirmationModel stripeBill =
                TransactionPresentation.buildConfirmation(billReq, 0L, "STRIPE_CARD", null);
        assertEquals("Nguồn thanh toán", stripeBill.rows.get(0).label);
        assertEquals("Thẻ (Stripe)", stripeBill.rows.get(0).value);
        assertEquals("Phương thức", stripeBill.rows.get(stripeBill.rows.size() - 1).label);
        assertEquals("Thẻ Stripe", stripeBill.rows.get(stripeBill.rows.size() - 1).value);

        TransactionPresentation.ConfirmationModel nimbusBill =
                TransactionPresentation.buildConfirmation(billReq, 0L, "NIMBUS_ACCOUNT", "acc_checking");
        assertEquals("Nguồn thanh toán", nimbusBill.rows.get(0).label);
        assertEquals("Tài khoản Nimbus (••• king)", nimbusBill.rows.get(0).value);
        assertEquals("Phương thức", nimbusBill.rows.get(nimbusBill.rows.size() - 1).label);
        assertEquals("Tài khoản Nimbus", nimbusBill.rows.get(nimbusBill.rows.size() - 1).value);

        TransactionFlowRequest orderReq = TransactionFlowRequest.orderPayment(
                "HOTEL", "prod_hotel_1", "Đặt khách sạn", "Phòng Superior · một đêm tại TP. Đà Lạt",
                null, null, null, null, 1_250_000L, "VND", null);

        TransactionPresentation.ConfirmationModel nimbusOrder =
                TransactionPresentation.buildConfirmation(orderReq, 0L, "NIMBUS_ACCOUNT", "acc_88884125");
        assertEquals("Tài khoản Nimbus (••• 4125)", nimbusOrder.rows.get(0).value);
        assertEquals("Tài khoản Nimbus", nimbusOrder.rows.get(nimbusOrder.rows.size() - 1).value);

        TransactionPresentation.ReceiptModel receipt = TransactionPresentation.buildReceipt(
                orderReq, "tx_1234567890", "", 0L, 1794820800000L, "NIMBUS_ACCOUNT", "acc_88884125");
        assertEquals("Nguồn thanh toán", receipt.rows.get(1).label);
        assertEquals("Tài khoản Nimbus (••• 4125)", receipt.rows.get(1).value);

        TransactionPresentation.ReceiptModel pendingExt = TransactionPresentation.buildReceipt(
                TransactionFlowRequest.externalTransfer(
                        "acc_88884125", 500_000L, "VND", "VCB", "Vietcombank", "0000000001", null),
                "tx_ext_1", "DEMO-123", 0L, 1794820800000L, null, null, true);
        assertEquals("Đang xử lý chuyển tiền", pendingExt.headline);
        assertEquals("Đang xử lý", pendingExt.rows.get(pendingExt.rows.size() - 1).value);
    }
}

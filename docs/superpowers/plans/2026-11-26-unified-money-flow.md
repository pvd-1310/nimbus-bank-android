# Unified 3-Step Money Flow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Standardize all customer money flows (internal transfer, external transfer, deposit/top-up, withdrawal, bill payment, utility orders) onto a unified 3-step execution flow (Confirmation -> OTP BottomSheet -> Success Receipt) visually matching the three reference HTML designs.

**Architecture:** Extract a pure presentation/state layer (`TransactionFlowRequest`, `TransactionPresentation`, `OtpDigitController`), build a reusable `OtpBottomSheetDialog` and `TransactionExecutionFragment` registered as `R.id.transactionFlow` in `nav_graph.xml` (with bottom navigation hidden), and rewire all Step 0 entry screens (`TransferFragment`, `MoneyRequestFragment`, `BillPaymentFragment`, `MobileTopupFragment`, `UtilityCatalogFragment`, `UtilitiesHubDialogFragment`) to launch `R.id.transactionFlow`.

**Tech Stack:** Android Java 11, ViewBinding, Material Components 3 (`BottomSheetDialog`/`DialogFragment`), AndroidX Navigation, Stripe Android PaymentSheet, CameraX FaceCamera, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-11-26-unified-money-flow-design.md`

## Global Constraints

- Minimum SDK 26, Java 11, no new Gradle dependencies (no `androidx.biometric`).
- Preserve existing backend API endpoints, payload schemas, idempotency (`requestId`), Face step-up (`faceRequired`), Stripe `PaymentSheet` flow, and demo reconciliation without modifying backend code.
- Hide masked phone number on OTP BottomSheet; auto-fill `DEMO_DISPLAY_CODE` OTP directly into the 6 digit boxes.
- Hide empty detail rows and their hairline dividers on Confirmation and Receipt cards; never invent placeholder beneficiary names or account names.
- Suppress bottom navigation (`main_bottom_navigation`) on `R.id.transactionFlow`.
- Preserve unrelated in-progress changes in the workspace outside the planned files unless superseded by this unification.

## Review Focus

- `ORDER_PAYMENT` (`TOPUP`, `FLIGHT`, `MOVIE`, `HOTEL`, `ECOMMERCE`) double-order creation on retry/network loss: `TransactionExecutionFragment` must persist the `orderRequestId` and created `billId` in `savedInstanceState` and recover via `/orders/by-request/:id` before creating `/payments/intents`. (Pinned in Task 4.)
- Back press or cancel while `commitPending == true`: prevent accidental edit/cancel while payment/demo movement is settling, and allow safe recovery via `SharedPreferences` or retry CTA. (Pinned in Task 4.)
- OTP auto-fill vs manual edit vs backspace when `code` arrives or user taps keypad rapidly: `OtpDigitController` must clamp to 6 digits, never overflow/underflow index, and ignore non-digit inputs. (Pinned in Task 2.)
- External transfer or Deposit/Withdrawal missing optional fields (`recipientName`, `bankName`, `targetNumber`, `note`): `TransactionPresentation` must omit null/blank rows cleanly without leaving dangling dividers. (Pinned in Task 1.)
- Empty or malformed transaction ID on Success screen: `TransactionPresentation.shortTransactionId` must fall back gracefully to `intentId` / `requestId` / `"—"` while keeping full raw ID for clipboard copy. (Pinned in Task 1.)

---

### Task 1: Pure Flow Request Model & Presentation Mapper

**Files:**
- Create: `app/src/main/java/com/example/final_term/ui/transaction/TransactionFlowRequest.java`
- Create: `app/src/main/java/com/example/final_term/ui/transaction/TransactionPresentation.java`
- Test: `app/src/test/java/com/example/final_term/ui/transaction/TransactionPresentationTest.java`

**Interfaces:**
- Produces:
  - `TransactionFlowRequest` (`Parcelable` + `Kind` enum: `INTERNAL_TRANSFER`, `EXTERNAL_TRANSFER`, `DEPOSIT`, `WITHDRAWAL`, `BILL_PAYMENT`, `ORDER_PAYMENT`; static factories `internalTransfer(...)`, `externalTransfer(...)`, `deposit(...)`, `withdrawal(...)`, `billPayment(...)`, `orderPayment(...)`; `toBundle()` / `fromBundle(Bundle)`)
  - `TransactionPresentation.DetailRow` (`label`, `value`, `subtitle`, `verifiedBadge`, `monospace`, `methodPill`, `copyValue`, `walletIcon`, `bankIcon`)
  - `TransactionPresentation.ConfirmationModel` (`headerTitle`, `heroLabel`, `amountFormatted`, `feeText`, `feeFree`, `List<DetailRow> rows`)
  - `TransactionPresentation.ReceiptModel` (`miniHeaderTitle`, `headline`, `amountFormatted`, `timestampText`, `List<DetailRow> rows`, `shareText`)
  - `TransactionPresentation.buildConfirmation(TransactionFlowRequest req, long feeMinor)`
  - `TransactionPresentation.buildReceipt(TransactionFlowRequest req, String fullTxId, String providerReference, long feeMinor, long completedAtMillis)`
  - `TransactionPresentation.maskAccountLabel(String accountId, String prefix)`
  - `TransactionPresentation.shortTransactionId(String providerRef, String txId)`

- [ ] **Step 1: Write the failing unit tests in `TransactionPresentationTest.java`**

```java
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
}
```

- [ ] **Step 2: Run the unit test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.example.final_term.ui.transaction.TransactionPresentationTest" -PfirebaseDemo=true`
Expected: FAIL compilation (missing `TransactionFlowRequest` and `TransactionPresentation`).

- [ ] **Step 3: Implement `TransactionFlowRequest.java` and `TransactionPresentation.java`**

Implement `TransactionFlowRequest` as an immutable `Parcelable` model with `Kind` enum, static factories for all 6 kinds, and `Bundle` helpers (`toBundle()`, `fromBundle(Bundle)`). Implement `TransactionPresentation` with `DetailRow`, `ConfirmationModel`, `ReceiptModel`, `maskAccountLabel(accountId, prefix)` returning `<prefix> (••• <last4>)`, `shortTransactionId(providerRef, txId)`, `formatTimestamp(millis)` (`HH:mm · dd 'Tháng' MM, yyyy` in `Asia/Ho_Chi_Minh` / default locale), `buildConfirmation`, and `buildReceipt` matching the exact Vietnamese labels in the spec. Use `ReadSummaryFormatter.formatCurrency` for all currency strings.

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.example.final_term.ui.transaction.TransactionPresentationTest" -PfirebaseDemo=true`
Expected: PASS (5 tests).

---

### Task 2: 6-Digit OTP State Controller & Custom OTP BottomSheet UI

**Files:**
- Create: `app/src/main/java/com/example/final_term/ui/transaction/OtpDigitController.java`
- Create: `app/src/main/java/com/example/final_term/ui/transaction/OtpBottomSheetDialog.java`
- Create: `app/src/main/res/layout/dialog_otp_bottom_sheet.xml`
- Create: `app/src/main/res/drawable/bg_otp_sheet_surface.xml`
- Create: `app/src/main/res/drawable/bg_otp_digit_filled.xml`
- Create: `app/src/main/res/drawable/bg_otp_digit_active.xml`
- Create: `app/src/main/res/drawable/bg_otp_digit_empty.xml`
- Create: `app/src/main/res/drawable/ic_fingerprint.xml`
- Create: `app/src/main/res/drawable/ic_backspace.xml`
- Create: `app/src/main/res/drawable/ic_schedule.xml`
- Test: `app/src/test/java/com/example/final_term/ui/transaction/OtpDigitControllerTest.java`

**Interfaces:**
- Consumes: nothing outside JDK + Android `Dialog`/`View`.
- Produces:
  - `OtpDigitController`: `appendDigit(char digit)`, `backspace()`, `setCode(String code)`, `clear()`, `String getCode()`, `int getLength()`, `boolean isComplete()`, `int getActiveIndex()`, `char digitAt(int index)`
  - `OtpBottomSheetDialog`: full-screen transparent `Dialog` / `BottomSheet` styled after `OTP_BottomSheet.html` with listener interface `Listener { void onSubmitOtp(String code); void onResendRequested(); void onDismissRequested(); }`, public methods `show()`, `dismiss()`, `setDemoCode(String code)`, `setCountdownText(String text, boolean canResend)`, `setStatusMessage(String message)`, `setBusy(boolean busy)`, `clearDigits()`.

- [ ] **Step 1: Write the failing unit tests in `OtpDigitControllerTest.java`**

```java
package com.example.final_term.ui.transaction;

import org.junit.Test;
import static org.junit.Assert.*;

public class OtpDigitControllerTest {
    @Test
    public void appendBackspaceAndClampToSixDigits() {
        OtpDigitController controller = new OtpDigitController();
        assertEquals(0, controller.getActiveIndex());
        assertFalse(controller.isComplete());

        for (char c : new char[]{'5', '9', '2', '4', '0', '1', '8'}) {
            controller.appendDigit(c);
        }
        assertEquals("592401", controller.getCode());
        assertTrue(controller.isComplete());
        assertEquals(-1, controller.getActiveIndex());

        controller.backspace();
        assertEquals("59240", controller.getCode());
        assertEquals(5, controller.getActiveIndex());
        assertFalse(controller.isComplete());
    }

    @Test
    public void setCodeAutoFillsValidSixDigitsAndIgnoresInvalidInput() {
        OtpDigitController controller = new OtpDigitController();
        controller.setCode("123456");
        assertEquals("123456", controller.getCode());
        assertTrue(controller.isComplete());
        assertEquals('6', controller.digitAt(5));

        controller.appendDigit('x'); // non-digit ignored
        assertEquals("123456", controller.getCode());

        controller.clear();
        assertEquals("", controller.getCode());
        assertEquals(0, controller.getActiveIndex());
    }
}
```

- [ ] **Step 2: Run the unit test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.example.final_term.ui.transaction.OtpDigitControllerTest" -PfirebaseDemo=true`
Expected: FAIL compilation (missing `OtpDigitController`).

- [ ] **Step 3: Implement `OtpDigitController.java`**

Pure Java class managing a `StringBuilder` capped at 6 ASCII digits (`'0'..'9'`), `activeIndex()` returning `length < 6 ? length : -1`, and safe `digitAt(index)` returning `'\0'` when out of range.

- [ ] **Step 4: Run `OtpDigitControllerTest` to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.example.final_term.ui.transaction.OtpDigitControllerTest" -PfirebaseDemo=true`
Expected: PASS.

- [ ] **Step 5: Create drawables and `dialog_otp_bottom_sheet.xml` matching `OTP_BottomSheet.html`**

Build the layout matching `OTP_BottomSheet.html`:
- Root `FrameLayout` (`match_parent`, `match_parent`, `#730F1530` dimmed backdrop)
- Bottom-aligned `LinearLayout`:
  - Top-right floating circular close button (`40dp`, `#D9FFFFFF`, `ic_close`) with `8dp` bottom margin
  - Frosted sheet `LinearLayout` (`bg_otp_sheet_surface`: `#F0FFFFFF`, top corners `32dp`, white top stroke):
    - Drag handle (`40dp x 4dp`, `#4D9AA4BC`, centered)
    - Shield icon badge (`48dp` circle, `#F4F6FA`, white border, `ic_shield` `#2F3E6E`)
    - Title `"Xác thực giao dịch"` (`20sp`, bold `#1B2340`)
    - Subtitle `"Nhập mã OTP 6 số để xác thực giao dịch"` (`13sp`, `#6B7694`) — no phone number
    - Row of 6 OTP boxes (`52dp` height, `8dp` gap), each a `FrameLayout` containing digit `TextView`, blinking cursor `View`/`TextView` (`|`), and empty dot `View` (`8dp` circle)
    - Countdown row: `ic_schedule` + countdown text (`"Gửi lại mã sau 00:30"` / `"Gửi lại mã"`)
    - Compact status/error `TextView` (`visibility="gone"` when empty)
    - Primary `"Xác nhận"` `MaterialButton` (`48dp`, `#2F3E6E`, cornerRadius `20dp`)
    - Custom 3x4 keypad (`GridLayout`, `3` columns): `1`..`9`, bottom-left `ic_fingerprint`, `0`, bottom-right `ic_backspace`

- [ ] **Step 6: Implement `OtpBottomSheetDialog.java`**

Wire `OtpDigitController`, `ObjectAnimator` blinking cursor on the active OTP box, keypad click handlers (`0..9`, `backspace`, `fingerprint` showing `"Xác thực sinh trắc học chưa khả dụng; vui lòng dùng mã OTP"`), floating close button, resend click handler, and `Xác nhận` button. Guard against leaked animations in `dismiss()`.

- [ ] **Step 7: Compile check**

Run: `.\gradlew.bat compileDebugJavaWithJavac -PfirebaseDemo=true`
Expected: PASS.

---

### Task 3: Confirmation & Success UI Layouts and Drawables

**Files:**
- Create: `app/src/main/res/layout/fragment_transaction_flow.xml`
- Create: `app/src/main/res/layout/item_transaction_detail_row.xml`
- Create: `app/src/main/res/drawable/ic_bolt.xml`
- Create: `app/src/main/res/drawable/ic_verified.xml`
- Create: `app/src/main/res/drawable/ic_account_balance.xml`
- Create: `app/src/main/res/drawable/bg_method_pill.xml`
- Create: `app/src/main/res/drawable/bg_success_outer_ring.xml`
- Create: `app/src/main/res/drawable/bg_glow_orb_white.xml`
- Create: `app/src/main/res/drawable/bg_glow_orb_blue.xml`

**Interfaces:**
- Produces: ViewBinding `FragmentTransactionFlowBinding` and `ItemTransactionDetailRowBinding` matching all IDs needed by `TransactionExecutionFragment`.

- [ ] **Step 1: Create vector icons (`ic_bolt`, `ic_verified`, `ic_account_balance`) and glass/pill drawables**

Match exact colors from `Transfer_Confirmation.html` and `Success_Screen.html`: `#2E9E7B` success, `#99DCE1FF` method pill, `#2F3E6E` primary container, `#66FFFFFF` hairline dividers, `#73FFFFFF` / `#7AFFFFFF` glass cards, `#A6FFFFFF` frosted buttons.

- [ ] **Step 2: Create `item_transaction_detail_row.xml`**

Reusable horizontal row layout (`label` on left `13sp` `ink_muted`, right-aligned container supporting optional wallet/bank icon, primary value `13sp` `ink`, optional verified checkmark badge, optional subtitle below value, optional method pill background, optional copy icon `ic_copy`, optional status pill `• Hoàn tất` / `Miễn phí`).

- [ ] **Step 3: Create `fragment_transaction_flow.xml`**

Root `FrameLayout` with `@drawable/background_gradient` and two subtle decorative glow orbs, containing two mutually exclusive full-screen containers:
1. **`step_confirm_container`** (matching `Transfer_Confirmation.html`):
   - Top bar (`56dp`): circular `back_button`, centered `confirm_header_title`, circular `security_button` (`ic_shield`)
   - ScrollView: Hero amount (`confirm_hero_label`, `confirm_amount_value` + `confirm_currency_symbol`, `confirm_fee_pill` with `ic_check` + `confirm_fee_text`), `confirm_details_card` (`LinearLayout` populated dynamically with `ItemTransactionDetailRowBinding`), security guarantee banner (`ic_lock` in white circle + E2E / Nimbus Smart OTP copy), optional `face_step_up_card` (hidden unless `faceRequired`), optional `pending_status_card` + `pending_action_button` (hidden unless `commitPending` or inline status message)
   - Pinned bottom bar: `btn_confirm_submit` (`54dp`, `#2F3E6E`, cornerRadius `16dp`, `"Xác nhận & nhập OTP"` + `ic_arrow_forward`)
2. **`step_success_container`** (matching `Success_Screen.html`):
   - Mini header (`52dp`): circular `success_close_button` (`ic_close`), centered uppercase `success_mini_title`, circular `success_share_top_button` (`ic_share`)
   - ScrollView: Dual-layer check badge (`64dp` outer glass ring + `44dp` `#2F3E6E` inner circle + white `ic_check`), `success_headline` (`22sp`), `success_amount_value` (`38sp` light) + `success_currency_symbol`, `success_timestamp`, `receipt_details_card` (populated with `ItemTransactionDetailRowBinding` + `Trạng thái: • Hoàn tất`), trust note (`ic_shield` + `"Giao dịch được bảo mật bởi Nimbus SafeGuard 256-bit"`)
   - Pinned bottom actions: `btn_share_receipt` (full-pill frosted button `50dp`, `ic_share` + `"Chia sẻ biên lai"`) and `btn_success_home` (full-pill `#2F3E6E` button `54dp`, `"Về trang chủ"`).

- [ ] **Step 4: Verify XML inflation via Gradle resource merge/compile**

Run: `.\gradlew.bat compileDebugJavaWithJavac -PfirebaseDemo=true`
Expected: PASS (generated `FragmentTransactionFlowBinding` and `ItemTransactionDetailRowBinding`).

---

### Task 4: Unified `TransactionExecutionFragment` + Navigation & BottomNav Suppression

**Files:**
- Create: `app/src/main/java/com/example/final_term/ui/transaction/TransactionExecutionFragment.java`
- Modify: `app/src/main/res/navigation/nav_graph.xml`
- Modify: `app/src/main/java/com/example/final_term/MainActivity.java:82-96`

**Interfaces:**
- Consumes: `TransactionFlowRequest`, `TransactionPresentation`, `OtpBottomSheetDialog`, `BackendApiClient`, `FaceCamera`, Stripe `PaymentSheet`, `AuthRepository`.
- Produces: `R.id.transactionFlow` destination accepting `TransactionFlowRequest.toBundle()` (and optional `intentId` / `pendingKey` recovery).

- [ ] **Step 1: Register `R.id.transactionFlow` in `nav_graph.xml` and hide bottom nav in `MainActivity.java`**

Add `<fragment android:id="@+id/transactionFlow" android:name="com.example.final_term.ui.transaction.TransactionExecutionFragment" android:label="Xác nhận giao dịch" />` to `nav_graph.xml`. Update `MainActivity.syncBottomNavigation(int destination)` so `show` is `false` when `destination == R.id.transactionFlow`.

- [ ] **Step 2: Implement `TransactionExecutionFragment.java`**

Implement the full 3-step coordinator:
- Restore `TransactionFlowRequest` from arguments/savedInstanceState, plus `orderRequestId`, `resolvedBillId`, `requestId`, `intentId`, `challengeId`, `faceRequired`, `faceVerified`, `commitPending`, `completed`, `authoritativeFee`, `providerReference`, `completedAtMillis`.
- Bind Step 1 (`TransactionPresentation.buildConfirmation`) dynamically into `confirm_details_card`.
- On `btn_confirm_submit` click:
  - If `ORDER_PAYMENT` and `resolvedBillId == null`: create/recover order via `/orders/by-request/:id` or `POST /orders`, store `resolvedBillId`, then prepare payment intent.
  - Otherwise create/recover intent for `INTERNAL_TRANSFER` (`/transfers/intents`), `EXTERNAL_TRANSFER` (`/external-transfers`), `WITHDRAWAL` (`/money-requests/intents`), `DEPOSIT` (`/topups/intents`), `BILL_PAYMENT` (`/payments/intents`), using `requestId` idempotency and `/by-request/:id` recovery.
- Inspect `/intents/:id`:
  - If `COMMITTED` -> transition to Step 3 (`Success`).
  - If `faceRequired && !faceVerified` -> show `face_step_up_card` and open `FaceCamera` (`verifyIntentFace`).
  - Otherwise show `OtpBottomSheetDialog`, call `POST /intents/:id/otp`, auto-fill the 6-digit code via `otpDialog.setDemoCode(code)`, and start a 30s `CountDownTimer` updating `otpDialog.setCountdownText(...)`.
- On OTP submit: call `api.verifyIntentOtp(...)`:
  - On success -> remember pending intent in `SharedPreferences` (`pending_bank_transfer`), then branch by `kind`:
    - `INTERNAL_TRANSFER`: `POST /transfers/:id/commit` -> `SETTLED` -> Step 3.
    - `EXTERNAL_TRANSFER` / `WITHDRAWAL`: `POST /demo-movements/:id/commit` -> poll `POST /demo-movements/:id/reconcile` (up to 6 retries every 1500ms while `COMMIT_PENDING`) -> `COMMITTED` -> Step 3 (or show error if `FAILED`).
    - `DEPOSIT` / `BILL_PAYMENT` / `ORDER_PAYMENT`: `POST /topups` or `POST /payments` (or `/transfers/:id/commit`-style if `STRIPE_TREASURY` handled on backend `/payments` response). If `SUCCEEDED`/`SETTLED` -> Step 3; if `clientSecret` returned -> dismiss `OtpBottomSheetDialog`, launch `PaymentSheet`, and on `Completed` poll `/topups/:id` or `/intents/:id` until `SUCCEEDED`/`COMMITTED` -> Step 3.
- Bind Step 3 (`TransactionPresentation.buildReceipt`) into `receipt_details_card`, wire clipboard copy on `Mã giao dịch`, `Chia sẻ biên lai` via `Intent.ACTION_SEND`, and `Về trang chủ` / `X` via `popBackStack(R.id.customerHome, false)`.
- Handle back button / `back_button`: if `commitPending && !completed`, block accidental cancellation and guide recovery; if intent created and not `commitPending`, call `POST /intents/:id/cancel` before navigating up.

- [ ] **Step 3: Compile & run unit tests**

Run: `.\gradlew.bat testDebugUnitTest compileDebugJavaWithJavac -PfirebaseDemo=true`
Expected: PASS.

---

### Task 5: Rewire All Entry Screens (`MoneyRequestFragment`, `TransferFragment`, Utilities)

**Files:**
- Modify: `app/src/main/java/com/example/final_term/ui/customer/Phase2Presentation.java`
- Modify: `app/src/test/java/com/example/final_term/ui/customer/Phase2PresentationTest.java`
- Modify: `app/src/main/java/com/example/final_term/ui/customer/MoneyRequestFragment.java`
- Modify: `app/src/main/java/com/example/final_term/ui/customer/TransferFragment.java`
- Modify: `app/src/main/res/layout/fragment_transfer.xml`
- Modify: `app/src/main/java/com/example/final_term/ui/utilities/BillPaymentFragment.java`
- Modify: `app/src/main/java/com/example/final_term/ui/utilities/MobileTopupFragment.java`
- Modify: `app/src/main/java/com/example/final_term/ui/utilities/UtilityCatalogFragment.java`
- Modify: `app/src/main/java/com/example/final_term/ui/utilities/UtilitiesHubDialogFragment.java`

**Interfaces:**
- Consumes: `TransactionFlowRequest`, `R.id.transactionFlow`.
- Produces: Every money entry point navigates directly to `R.id.transactionFlow` with a populated `TransactionFlowRequest`.

- [ ] **Step 1: Update `Phase2PresentationTest.java` and `Phase2Presentation.java`**

Update `Phase2Presentation.usesRequestForm(String operation)` so both `"DEPOSIT"` and `"WITHDRAWAL"` return `true` (since both now collect account + amount directly in `MoneyRequestFragment`), and update `Phase2PresentationTest.withdrawalUsesRequestFormWhileDepositUsesCardFlow` -> `depositAndWithdrawalBothUseRequestForm`.

- [ ] **Step 2: Run `Phase2PresentationTest` to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.example.final_term.ui.customer.Phase2PresentationTest" -PfirebaseDemo=true`
Expected: PASS.

- [ ] **Step 3: Rewire `MoneyRequestFragment.java`**

Show account selector + amount input for both `DEPOSIT` and `WITHDRAWAL`, update button labels (`"Tiếp tục · nạp tiền"` / `"Tiếp tục · rút tiền"`), validate selected `CHECKING` `VND` account and positive amount via `Phase2Presentation.validRequestAmount`, and navigate directly to `R.id.transactionFlow` with `TransactionFlowRequest.deposit(...)` or `TransactionFlowRequest.withdrawal(...)`.

- [ ] **Step 4: Simplify `TransferFragment.java` and `fragment_transfer.xml` to Step 0 input only**

Remove `demoCashOperation` routing and legacy embedded Step 2/3/4 UI state machine (`TransactionSheet`, `PaymentSheet`, OTP/Success containers) from `TransferFragment` and `fragment_transfer.xml`. Keep Step 1 input (Internal/External tabs, Source account spinner, External bank spinner, Target phone/account input, Amount + quick chips, Note input, QR shortcut). On `"Tiếp tục"`, validate inputs; for internal transfers call `POST /transfers/recipient`, then navigate to `R.id.transactionFlow` with `TransactionFlowRequest.internalTransfer(...)` or `TransactionFlowRequest.externalTransfer(...)`.

- [ ] **Step 5: Rewire `BillPaymentFragment`, `MobileTopupFragment`, `UtilityCatalogFragment`, and `UtilitiesHubDialogFragment`**

- `BillPaymentFragment`: `openCheckout()` navigates to `R.id.transactionFlow` with `TransactionFlowRequest.billPayment(...)` (passing `billId`, service label, provider name, `invoiceRef`, billing period/details, `amountMinor`, `currency`, `sourceAccountId`).
- `MobileTopupFragment`: `submitTopup()` navigates to `R.id.transactionFlow` with `TransactionFlowRequest.orderPayment("TOPUP", product.id, carrierName, product.name, phone, amountMinor, currency, sourceAccountId)`.
- `UtilityCatalogFragment`: `openCheckout(product)` navigates to `R.id.transactionFlow` with `TransactionFlowRequest.orderPayment(serviceType, product.id, serviceTitle(serviceType), product.name, null, amountMinor, currency, sourceAccountId)`.
- `UtilitiesHubDialogFragment`: When initiating checkout from the hub dialog (`startCheckout` / `openOrderInvoice`), dismiss the dialog and navigate on `NavHostFragment.findNavController(this)` (or parent activity's `NavController`) to `R.id.transactionFlow` with the corresponding `TransactionFlowRequest`, removing the legacy inline checkout/OTP/receipt UI path.

- [ ] **Step 6: Full verification build & unit test suite**

Run: `.\gradlew.bat testDebugUnitTest assembleDebug -PfirebaseDemo=true`
Expected: BUILD SUCCESSFUL and all unit tests PASS with 0 failures.

---

## Completion Review

- Visually inspect `fragment_transaction_flow.xml` and `dialog_otp_bottom_sheet.xml` against `Transfer_Confirmation.html`, `OTP_BottomSheet.html`, and `Success_Screen.html` to verify colors, radii, hierarchy, spacing, and copy.
- Verify all 6 transaction kinds map cleanly without empty rows or dangling dividers.
- Verify `MainActivity` hides bottom navigation on `R.id.transactionFlow` and keeps `BaseFlowInstrumentedTest` expectations intact.
- Run `.\gradlew.bat testDebugUnitTest assembleDebug -PfirebaseDemo=true` after the final edit and report exact observed counts.

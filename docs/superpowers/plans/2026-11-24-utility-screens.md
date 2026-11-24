# Utility Screens Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Align the Android electricity, water, mobile top-up, and flight screens with the supplied HTML designs while retaining current backend and checkout behavior.

**Architecture:** Keep the existing Java fragments, ViewBinding layouts, catalog/bill APIs, and order/payment routes. Add optional itinerary attributes to the existing demo catalog seed records and render those fields with fallbacks, without changing persisted schema requirements.

**Tech Stack:** Android Java/XML, AndroidX Navigation, Material Components, Firebase/Firestore demo seed, TypeScript backend.

**Spec:** `docs/superpowers/specs/2026-11-24-utility-screens-design.md`

## Global Constraints

- Do not change the real Firestore schema or add a migration.
- Keep the current bill lookup, order, OTP, and payment flows connected.
- Keep new catalog fields optional and preserve rendering of old records.
- Do not require broad contacts permission for contact selection.
- Preserve unrelated in-progress workspace changes.

## Review Focus

- Missing optional flight fields: existing catalog records must still display a usable name and price. (Task 3 fallback rendering.)
- Phone numbers without a recognized carrier prefix: keep manual carrier selection usable. (Task 2 carrier fallback.)
- Repeated fragment requests or view recreation: callbacks must not update a destroyed view or replace newer data. (Task 2 request lifecycle; existing request versioning in Task 3.)
- Water bill mismatch or already-paid bill: preserve service-type validation and keep payment disabled. (Task 1 lookup behavior.)
- Contact selection cancellation: remain on the top-up screen with the entered phone value intact. (Task 2 picker behavior.)

---

### Task 1: Electricity and water bill screens

**Files:**
- Modify: `app/src/main/res/layout/fragment_bill_payment.xml`
- Modify: `app/src/main/java/com/example/final_term/ui/utilities/BillPaymentFragment.java`
- Modify: `backend/src/scripts/seed-demo.ts`

**Interfaces:**
- Consumes: existing `serviceType`, `sourceAccountId`, `billId` navigation arguments and `/bills`, `/bills/lookup/:reference` API responses.
- Produces: one shared bill screen styled after `html/Electricity_Bill.html`, with service-specific title, provider labels, icon and copy for `ELECTRICITY` or `WATER`; optional period, meter reading, and consumption rows when demo bill fields exist.

- [x] **Step 1: Rework the shared bill layout** to follow the reference's header/help affordance, provider selector card, customer-code field, lookup-history action, bill status/details, and bottom payment action. Retain existing ViewBinding IDs unless a renamed binding is updated in the fragment in this task.
- [x] **Step 2: Set service-specific presentation** in `BillPaymentFragment` for electricity and water while retaining existing lookup validation, selected bill, and checkout dialog behavior.
- [x] **Step 3: Add optional demo billing details** (`billingPeriod`, `previousReading`, `currentReading`, `consumption`, `consumptionUnit`) to the electricity and water seed bills. The UI hides each unavailable detail and does not invent values for older records.
- [x] **Step 4: Verify the XML and bindings** by inspecting generated binding-compatible IDs and the fragment's references; do not alter API semantics.

### Task 2: Mobile top-up screen

**Files:**
- Modify: `app/src/main/res/layout/fragment_mobile_topup.xml`
- Modify: `app/src/main/java/com/example/final_term/ui/utilities/MobileTopupFragment.java`

**Interfaces:**
- Consumes: `/catalog?limit=100&serviceType=TOPUP`, `TOPUP` product `network`/`amountMinor`/`currency`, and the existing order checkout flow.
- Produces: reference-aligned phone entry, carrier and denomination selection, and a working submit action.

- [x] **Step 1: Rework the top-up layout** around the HTML hierarchy: phone number card, carrier selection, denomination grid, and persistent primary action. Keep existing IDs or update all binding references in this task.
- [x] **Step 2: Add phone interaction**: format/display typed number, infer a carrier only for recognized prefixes, and retain manual carrier selection otherwise. Use Android's system contact picker without broad contacts permission; preserve the field if the picker is cancelled.
- [x] **Step 3: Preserve catalog and checkout behavior** for selected network and denomination, including loading/error/empty states and submit enablement.
- [x] **Step 4: Inspect binding references and lifecycle guards** after the layout and interaction changes.

### Task 3: Flight catalog screen

**Files:**
- Modify: `app/src/main/res/layout/fragment_utility_catalog.xml`
- Modify: `app/src/main/java/com/example/final_term/ui/utilities/UtilityCatalogFragment.java`

**Interfaces:**
- Consumes: `/catalog?limit=100&serviceType=FLIGHT` and existing catalog name, amount, currency, and ID fields.
- Produces: flight cards with optional itinerary details and a checkout action using the same product ID.

- [x] **Step 1: Rework the catalog layout** to support the flight search/route summary and flight result card hierarchy in `html/Flight_Booking.html`, retaining status, count, scroll, and back navigation.
- [x] **Step 2: Render optional itinerary fields** (`airline`, `flightNumber`, `origin`, `destination`, `departureTime`, `arrivalTime`, `duration`, `fareClass`) when present; fall back to product name and amount when fields are absent.
- [x] **Step 3: Keep checkout unchanged**: tapping a result still passes the existing service type and product ID to `newOrderCheckoutInstance`.
- [x] **Step 4: Inspect the screen for incomplete data and narrow-width truncation** using layouts that permit wrapping or ellipsizing without hiding price or selection.

### Task 4: Compatible demo data

**Files:**
- Modify: `backend/src/scripts/seed-demo.ts`
- Inspect: `backend/src/modules/orders/orders.routes.ts`

**Interfaces:**
- Consumes: current seed namespace and catalog collection shape.
- Produces: multiple deterministic, schema-compatible demo flights with optional itinerary attributes and clearer demo carrier names while retaining current product IDs/contracts where practical; richer demo bill details are also added without making fields required.

- [x] **Step 1: Extend demo catalog fixtures** with airline, flight number, origin/destination, departure/arrival, duration, and fare class for each flight; add multiple distinct deterministic flight choices.
- [x] **Step 2: Replace opaque carrier labels** with clear demo carrier names while keeping the current `network` values accepted by the mobile client and order route.
- [x] **Step 3: Confirm seed serialization** writes only optional catalog attributes and does not add required schema fields or alter order/payment fields.

## Completion Review

- Check the four modified Android screens against their respective HTML references and the approved spec.
- Confirm existing binding IDs, service type routing, product IDs, and checkout entry points remain connected.
- Review the final diff against the pre-existing workspace changes so unrelated user edits are preserved.
- Do not run tests unless the user requests verification.

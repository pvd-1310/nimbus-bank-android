# Utility screens alignment design

## Intent

Bring the electricity, water, mobile top-up, and flight screens in the Android app closer to their supplied HTML references while keeping the current demo backend and checkout flows usable. The user approved enriching demo data without changing the real Firestore schema.

## Goals and boundaries

- Rework the Android utility layouts to follow the matching HTML hierarchy, labels, cards, and interaction cues.
- Treat the electricity screen as the visual reference for water, with water-specific provider and copy.
- Improve the top-up screen around phone entry, carrier selection, denomination selection, and the existing purchase action.
- Give demo flight products optional itinerary details needed by the flight design and render those details when present.
- Keep existing endpoint contracts and order/payment behavior. Optional catalog fields remain backward-compatible; no Firestore migration is included.
- Preserve unrelated in-progress workspace changes.

## Approaches

1. Keep the present generic utility layouts and only add more catalog records. This would not address the reported visual mismatch.
2. Align the four Android screens to their HTML references and add compatible demo catalog details. **Chosen:** this addresses both visual structure and the missing flight content without changing the persisted schema or real data.

## Design calls

- Electricity and water continue to use the existing bill lookup and checkout endpoints. UI labels and provider presentation vary by service type.
- Top-up continues to use the existing catalog, order, OTP, and payment flow. Carrier detection can use the entered phone prefix; contact selection should use the Android system picker if practical and must not require broad contacts access.
- Flight itinerary attributes are optional demo catalog fields (airline, flight number, origin/destination, departure/arrival, duration, and fare class). Existing name and amount remain valid fallbacks.
- No production provider integration, real airline inventory, database migration, or changes to payment semantics are in scope.

## Acceptance

- The four Android screens visibly follow the corresponding HTML composition and content hierarchy.
- Water uses the electricity screen's structure with water-specific copy and iconography.
- Demo flights show itinerary details while old or incomplete catalog records still render safely.
- Existing lookup, top-up, order, checkout, and navigation paths remain connected.
- Any required demo data additions are confined to the seed path and remain compatible with current catalog consumers.

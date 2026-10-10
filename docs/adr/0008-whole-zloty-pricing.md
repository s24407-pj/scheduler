# ADR-0008: Whole-złoty pricing

**Status:** Accepted

## Decision

- All prices are integers in **whole Polish złoty (PLN)**. The application does not use grosze anywhere.
- `offerings.price` and `reservations.price` are `INTEGER`; Kotlin uses `Int`.
- `reservations.price` is a snapshot taken at booking time, after any last-minute discount. Later catalog changes do not affect existing reservations.
- The last-minute discount is rounded **down** to whole złoty: `price * (100 - percent) / 100` with integer division. Example: 99 zł with 15% discount → 84 zł (84.15 truncated).

## Consequences

- No currency field and no decimal handling in the API.
- If grosze or multiple currencies are ever needed, prices must migrate to a minor-unit integer plus currency code; this ADR would be superseded.

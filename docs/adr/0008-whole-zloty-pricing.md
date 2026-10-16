# ADR-0008: Whole-złoty pricing

**Status:** Accepted

## Decision

- All prices are integers in **whole Polish złoty (PLN)**. The application does not use grosze anywhere.
- `offerings.price` and `reservations.price` are `INTEGER`; Kotlin uses `Int`.
- `reservations.price` is a snapshot taken at booking time, after any last-minute discount. Later catalog changes do not affect existing reservations. A valid [commercial quote](./0013-commercial-quotes.md) protects the accepted name, price and duration for ten elapsed minutes. Staff manual booking defaults to the regular catalog price.
- The last-minute discount rounds to the **nearest** whole złoty, **ties to even**, using decimal arithmetic without integer overflow. Examples: 101 zł at 50% → 50 zł; 103 zł at 50% → 52 zł; 101 zł at 15% → 86 zł. The discount interval includes both endpoints and measures elapsed time through DST transitions.

## Consequences

- No currency field and no decimal handling in the API.
- If grosze or multiple currencies are ever needed, prices must migrate to a minor-unit integer plus currency code; this ADR would be superseded.

# ADR-0014: Effective schedules and explicit qualification

Status: Accepted — revised scheduler product contract.

## Decision

One continuous weekly shift per company employee/weekday is the default schedule. A date-specific replacement or day off takes precedence. Only an explicit empty weekly list clears the week. Missing/null lists reject without mutation. The customer grid starts at effective shift start and requires the full duration to fit, with shift-end equality permitted.

Qualification is explicit: `ALL_SERVICES` or `SPECIFIC_SERVICES`. An empty specific assignment set qualifies for no service. Disabled employees cannot receive new bookings. Public lists, availability and booking use the same qualification model.

Policy changes acquire company then employment locks, matching booking allocation. Concurrent decisions are serialized, while existing appointments are preserved. Employee blocks are separate intervals; they never reduce the full scheduled elapsed-minute denominator used for any-employee allocation.

Availability uses Warsaw dates independently of the JVM default timezone. Zero notice means strictly future; positive elapsed notice is inclusive. The whole final horizon date is included. Ambiguous/nonexistent local appointment endpoints and transition-spanning appointments are excluded. Multiday blocks may span transitions but their endpoints must be unambiguous.

An authenticated customer's conflicts annotate otherwise-free salon slots instead of removing those slots from public availability. Customer conflicts remain independent across companies.

## Consequences

Schedule changes do not rewrite appointment history. Availability is an advisory read and may change immediately after a response; authoritative booking validation occurs after locking. Company-level serialization favors correctness for the accepted single-instance v1 deployment.

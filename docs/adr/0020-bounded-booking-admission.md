# ADR-0020: Bounded booking admission and fingerprint storage

**Status:** Accepted (2026-10-30), user-authorized remediation of the verified submission-storage abuse defect.

## Decision

Booking request accounting remains atomic and fail-closed in Redis, including rejected attempts
and retries. Apply its existing 10/account/minute and 60/IP/minute gates before buffering a body
or creating any submission identity. A rate-rejected new key remains unbound. Cap booking
bodies at 64 KiB, checking both advertised length and at most 65537 streamed bytes. Oversize
requests return 413 `PAYLOAD_TOO_LARGE` before new binding, including when the required key
is absent. These gates supersede ADR-0016/0018's unconditional pre-validation binding only
at the admission boundary. Admitted malformed/invalid requests still bind before MVC validation.

Keep the same canonical fingerprint algorithm, permanent realm/account/key identity, bounded
concurrent waits and reservation outcome links. Store only the SHA-256 fingerprint and outcome;
request bodies are unnecessary for comparison or replay. V4 drops only the old payload column.
Existing failed bindings and successful outcomes remain recoverable without expiry.

A later 429/413 never proves that an earlier uncertain attempt failed. Clients retain the
original body/key/account and never silently create another intent or modify its payload.

## Deployment and rollback

Deploy the paired dashboard verified against the exact backend head. Its existing bounded
form inputs fit within 64 KiB and it must preserve uncertain submissions across admission errors.
Stop old backend binaries before V4: they still insert the removed column. To roll back an
older binary, first apply a reviewed forward migration restoring
`request_payload text NOT NULL DEFAULT ''`. The old implementation compares the retained
fingerprint, so original bodies need not be reconstructed. Never reset bindings or outcomes.

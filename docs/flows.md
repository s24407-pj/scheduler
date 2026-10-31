# User flows

Public booking pages know their company ID and read its public profile, categories, active services, eligible staff and availability without signing in. Customer authentication is needed only when submitting a booking.

1. Fetch `/api/auth/csrf`; request and verify the six-digit phone OTP with cookies and CSRF. Include the public page’s `companyId` in both OTP requests. Verification needs no names and creates a pending customer local to that salon.
2. Select a service, time and employee (or any employee); optionally acquire a ten-minute commercial quote.
3. Submit with a stable `Idempotency-Key`. Read `/api/users/me.profileState`; supply grouped `firstVisitDetails` only for a pending salon profile. If establishment races with staff booking, `CUSTOMER_PROFILE_CHANGED` requires refreshed explicit acknowledgement before a changed submission. Protected customer writes also require the captured session-context header described in [architecture](./architecture.md#csrf-cookies-and-frontend-contract). Success completes the local profile and establishes staff visibility atomically, then creates BOOKED immediately; no confirmation SMS/email is sent.
4. Read current details/history or cancel strictly before start using the returned revision. Customers cannot reschedule themselves.

Staff sign in with normalized email and password; independent company accounts are verified before offering a company choice when needed. The company-scoped endpoint remains a compatibility and high-membership fallback. Staff cookie sessions have a 24-hour inactivity timeout; customer sessions are bound to one salon and have an absolute 24-hour lifetime. Switching identity requires logout. Logout-all and password changes affect only that realm/account.

Owners create employee accounts and hand off one-use setup/recovery credentials, manage local staff presentation, enable/disable employees, catalog, schedules and company settings. Ownership changes remain operator-controlled. Disabling staff preserves appointments and reports affected future visits.

Both staff roles read company-local customers/notes/history and manage company bookings. Manual creation explicitly selects an existing customer ID or requests a new phone profile; duplicate established phones require staff review and explicit existing selection. Manual creation may be ongoing/off-grid; edits retain identity and accepted terms for an unchanged service. Staff cancellation requires a private reason. Status corrections, restoration and owner archive/unarchive use revision preconditions; see [reservations](./reservations.md).

Owner blocking cancels future visits and creates one filtered summary delivery without affecting authentication or other salons. No-show correction removes the mistaken penalty; owner unblock preserves history.

Automatic completion runs each minute and catches up after restart. Reminders run at 09:00 Warsaw on the previous calendar day, with a 10:00 deadline and no missed-run recovery. Durable delivery state distinguishes accepted, definite temporary failure and ambiguous provider results; see [notifications](./notifications.md).

## Mandatory staff sign-in

Email/password → optional verified company selection → TOTP_REQUIRED or TOTP_SETUP_REQUIRED → code verification or QR/manual-key enrollment → AUTHENTICATED → refresh CSRF and auth/me. Every intermediate step is anonymous and cannot read staff data. Owner backup use leads to replacement enrollment, never directly to the panel. After a lost recovery or setup response, the original challenge can resume enrollment in the same anonymous browser until its original deadline, without spending another backup code. Employee loss requires owner-verified reset. [Identity](./identity.md#mandatory-staff-totp) defines the full contract.

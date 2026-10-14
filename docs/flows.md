# User flows

End-to-end journeys through the API, per actor. Each journey shows the order of calls; the rules behind each step live in the linked docs and are not repeated here. Paths are relative to `/api`. Public reads need no authentication; all API calls use credentials/cookies, and unsafe calls also send `X-XSRF-TOKEN` obtained from `/auth/csrf`. Refresh CSRF after login/logout; see [the frontend contract](./architecture.md#csrf-cookies-and-frontend-contract).

| Actor | Signs in with | Journeys |
| ----- | ------------- | -------- |
| Owner | Email and password | [Salon setup](#1-salon-setup-owner), [staff sign-in](#4-staff-sign-in), [day-to-day](#5-staff-day-to-day), [visit outcome](#6-visit-outcome) |
| Employee | Email and password | [Staff sign-in](#4-staff-sign-in), [day-to-day](#5-staff-day-to-day), [visit outcome](#6-visit-outcome) |
| Customer | SMS code | [Booking](#2-customer-booking), [self-service](#3-customer-self-service) |
| System | — | [Background jobs](#7-background-jobs) |

## 1. Salon setup (owner)

Companies and staff accounts are not created through the API; they come from the database (locally, the `dev` seed in `DataInitializer`, see [development](./development.md)). From there the owner configures the salon. Until an employee has a work schedule, availability returns no slots for them and customer bookings are rejected.

```mermaid
sequenceDiagram
    actor O as Owner
    participant API
    participant R2 as Cloudflare R2
    O->>API: POST /auth/login-staff (see journey 4)
    API-->>O: HttpOnly session (owner employment)
    O->>API: PUT /company/settings (slot interval, advance, discount, maxNoShows)
    O->>API: POST /offering-categories
    O->>API: POST /offerings
    O->>API: PATCH /offerings/{id}/category
    O->>API: POST /offerings/{id}/image (multipart "image", up to 5)
    API->>R2: upload
    O->>API: GET /employees
    API-->>O: employees of the company (id = user ID)
    O->>API: PUT /employees/{id}/work-schedule (per-day hours)
    O->>API: POST /employees/{id}/offerings/{offeringId} (optional)
    O->>API: POST /employees/{id}/photo (multipart "photo")
    API->>R2: upload, delete previous photo
```

Rules: [company settings](./availability.md#company-settings-that-affect-booking), [work schedules](./availability.md#work-schedules), [offering assignments](./availability.md#offering-assignments), [file storage](./architecture.md#file-storage-cloudflare-r2).

## 2. Customer booking

The customer browses anonymously and signs in only to book. There are two ways in; both end in the same slot pick, sign-in, and booking.

- **Offering first:** pick an offering, then an employee who performs it.
- **Employee first:** start from a known employee and list what they perform at this salon. There is no public endpoint listing all employees of a salon; the employee ID comes from the client (for example a link to the employee's page) or from an earlier offering-first listing.

```mermaid
sequenceDiagram
    actor C as Customer
    participant API
    participant SMS as SMS sender
    C->>API: GET /company/public/{companyId}
    API-->>C: salon profile
    alt Offering first
        C->>API: GET /offerings/public/company/{companyId}
        API-->>C: active offerings with images
        C->>API: GET /employees/public/company/{companyId}?offeringId={id}
        API-->>C: employees who perform it
    else Employee first
        C->>API: GET /offerings/public/company/{companyId}/employee/{employeeId}
        API-->>C: offerings that employee performs at this salon (404 if not employed there)
    end
    C->>API: GET /availability?employeeId=&serviceId=&date=
    API-->>C: free slots with price and originalPrice
    opt Not signed in
        C->>API: POST /auth/request-code (phoneNumber)
        API->>SMS: OTP code
        C->>API: POST /auth/verify-code (phoneNumber, code, firstName and lastName for new users)
        API-->>C: HttpOnly customer session (registers the user on first sign-in)
    end
    C->>API: POST /reservations (employeeId, serviceId, startTime)
    alt New booking
        API-->>C: 201 reservation (PENDING)
        API--)SMS: confirmation (after commit)
    else Identical active booking exists
        API-->>C: 200 existing reservation, no SMS
    else Slot taken or off the grid
        API-->>C: 409 SLOT_UNAVAILABLE, pick another slot
    end
```

Rules: [slot calculation](./availability.md#slot-calculation), [public endpoints](./availability.md#public-booking-endpoints), [booking rules](./reservations.md#booking-rules), [retries](./reservations.md#retries-and-idempotency), [rate limits](./architecture.md#rate-limiting-redis).

## 3. Customer self-service

```mermaid
sequenceDiagram
    actor C as Customer
    participant API
    participant SMS as SMS sender
    C->>API: GET /users/me
    C->>API: PUT /users/me (name, email)
    C->>API: GET /reservations/me
    API-->>C: own reservations, newest first
    C->>API: PATCH /reservations/{id}/cancel
    alt Before startTime
        API-->>C: 204
        API--)SMS: cancellation (after commit)
    else At or after startTime, or already final
        API-->>C: 409 INVALID_STATUS_TRANSITION
    end
    C->>API: POST /auth/logout (or /auth/logout-all)
```

Rules: [lifecycle](./reservations.md#lifecycle), [sessions](./architecture.md#sessions-and-logout).

## 4. Staff sign-in

A staff member can work at several salons. Each session is scoped to one employment. Switching salons requires CSRF-protected logout, CSRF refresh, then signing in again with another `employmentId`.

```mermaid
sequenceDiagram
    actor S as Staff
    participant API
    S->>API: POST /auth/login-staff (email, password)
    alt One employment
        API-->>S: AUTHENTICATED + session cookie
    else Several employments
        API-->>S: EMPLOYMENT_SELECTION_REQUIRED + employments (employmentId, companyId, companyName, role)
        S->>API: POST /auth/login-staff (email, password, employmentId)
        API-->>S: AUTHENTICATED + session cookie for that salon and role
    else Wrong credentials
        API-->>S: 400 (same message for every cause)
    else Too many attempts from this IP
        API-->>S: 429
    end
    S->>API: POST /auth/logout (this session) or /auth/logout-all (all sessions)
    API-->>S: 204
```

Rules: [authentication](./architecture.md#authentication), [login hardening](./architecture.md#secret-and-login-hardening).

## 5. Staff day-to-day

Everything is scoped to the salon in the session principal. An employee sees and blocks only their own time; an owner can act for any employee of the salon.

```mermaid
sequenceDiagram
    actor S as Staff
    participant API
    participant SMS as SMS sender
    S->>API: GET /reservations?employeeId=&start=&end=
    API-->>S: calendar with customer names (employee: own ID only, else 403)
    S->>API: GET /schedule-blocks/employee?start=&end= (owner: optional employeeId)
    S->>API: POST /schedule-blocks (startTime, endTime, reason, owner: optional employeeId)
    API-->>S: 201, or 409 if it overlaps a reservation or another block
    S->>API: POST /reservations/staff (employeeId, serviceId, startTime, customerPhone, names for new clients)
    API-->>S: 201 (or 200 for an identical active booking)
    API--)SMS: confirmation (after commit)
    S->>API: PATCH /reservations/{id}/cancel-by-staff
    API-->>S: 204
    API--)SMS: cancellation (after commit)
    S->>API: GET /customers, GET /customers/{id}
    S->>API: PUT /customers/{id}/notes
    S->>API: PATCH /customers/{id}/block or /unblock (owner only)
```

Staff bookings skip the advance-time and slot-grid checks but not the overlap check. Rules: [booking rules](./reservations.md#booking-rules), [schedule blocks](./availability.md#schedule-blocks), [customer blocking](./architecture.md#customer-blocking-and-no-shows), [reading reservations](./reservations.md#reading-reservations).

## 6. Visit outcome

After the visit, staff record what happened, or the hourly job completes it. A completed visit can still be corrected to no-show for 24 hours after it ends.

```mermaid
stateDiagram-v2
    [*] --> PENDING: POST /reservations or /reservations/staff
    PENDING --> CANCELLED: customer cancel (before start)
    PENDING --> CANCELLED: cancel-by-staff (any time)
    PENDING --> COMPLETED: complete (staff)
    PENDING --> COMPLETED: hourly job (after endTime)
    PENDING --> NO_SHOW: no-show (staff, after start)
    COMPLETED --> NO_SHOW: no-show correction (until endTime + 24 h)
    CANCELLED --> [*]
    COMPLETED --> [*]
    NO_SHOW --> [*]
```

`CONFIRMED` is never set and behaves like `PENDING`. An owner can also hard-delete a reservation (`DELETE /reservations/{id}`).

```mermaid
sequenceDiagram
    actor S as Staff
    participant API
    S->>API: PATCH /reservations/{id}/complete
    API-->>S: 204
    S->>API: PATCH /reservations/{id}/no-show
    alt Allowed by the time window
        API-->>S: 204, no-show count +1, customer blocked at this salon when it reaches maxNoShows
    else Before start, or more than 24 h after endTime, or already final
        API-->>S: 409 INVALID_STATUS_TRANSITION
    end
```

Rules: [lifecycle and transitions](./reservations.md#lifecycle), [customer blocking](./architecture.md#customer-blocking-and-no-shows), [ADR-0010](./adr/0010-cancellation-window-and-no-show-correction.md).

## 7. Background jobs

Both jobs run every hour on the hour in the business time zone, on a single instance.

```mermaid
sequenceDiagram
    participant J as NotificationScheduler
    participant DB as PostgreSQL
    participant SMS as SMS sender
    J->>DB: PENDING or CONFIRMED with endTime in the past become COMPLETED
    J->>DB: find reservations starting in 23 to 25 h with reminder_sent = false
    loop each reservation
        J->>SMS: reminder (a failure is logged and retried on the next run while still in the window)
    end
    J->>DB: set reminder_sent for the ones sent
```

Rules: [notifications and jobs](./architecture.md#notifications-and-scheduled-jobs), [time handling](./time-handling.md). SMS is currently written to the log only; see the known gap there.

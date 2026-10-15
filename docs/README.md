# Documentation

| Document | Read it when you... |
| -------- | ------------------- |
| [flows.md](./flows.md) | want the end-to-end journeys per actor (owner, employee, customer, background jobs) and the order of API calls |
| [development.md](./development.md) | set up the project, configure it, use seed data, or write tests |
| [deployment.md](./deployment.md) | deploy to the VPS (Dokploy + Cloudflare): client IP, origin lockdown, production compose |
| [architecture.md](./architecture.md) | need the module map, auth, authorization, notifications, caching, storage, or schema overview |
| [reservations.md](./reservations.md) | touch booking, cancellation, status changes, pricing, or concurrency |
| [pricing.md](./pricing.md) | touch whole-złoty rounding, elapsed discount windows or durable commercial quotes |
| [availability.md](./availability.md) | touch slot calculation, work schedules, schedule blocks, offering assignments, or company settings |
| [time-handling.md](./time-handling.md) | work with dates, times, "now", or scheduled jobs |
| [api/endpoints.md](./api/endpoints.md) | need the list of endpoints and who can call them |
| [api/errors.md](./api/errors.md) | handle or return errors |
| [adr/](./adr/README.md) | want to know why something is built the way it is, or need to change a recorded decision |

Docs describe the code as it is. Where an accepted decision is not implemented yet, the text says **Pending** and links the ADR.

- [Identity and staff administration](./identity.md) — separate realms, salon profiles and password lifecycle.

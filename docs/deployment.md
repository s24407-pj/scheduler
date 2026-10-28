# Deployment

Target: a VPS running [Dokploy](https://dokploy.com/) (Traefik as the reverse proxy), with DNS proxied through Cloudflare.

`docker-compose.yml` is the production configuration: `prod` by default, required deployment settings, no host port mappings, and a project-scoped `backend` bridge shared explicitly by `app`, `db`, and `redis`. The app uses `db:5432` and `redis:6379`. The bridge is not an external/shared network; it permits outbound access needed for R2 and JustSend.

The `prod` profile registers the JustSend sender and requires nonblank `JUSTSEND_APP_KEY` and `JUSTSEND_SENDER` at startup. The sender value must be permitted on the operator's JustSend account. The console sender is available only outside `prod`.

## Dokploy setup and networking

1. Create a **Compose** service connected to `s24407-pj/scheduler`, select the deployment branch, and use `docker-compose.yml`. It pulls the approved GHCR image; the production service has no `build:` section. Disable **Auto Deploy** so a Git push cannot deploy before checks finish. Do not include `docker-compose.dev.yml` or copy the local `.env.example` into production.
2. Set the required values below in Dokploy's protected **Environment** settings. They are referenced by Compose and forwarded to the appropriate containers; missing required values fail Compose interpolation.
3. In **Domains**, route the API hostname to service `app`, container port `8080`, path `/`, with HTTPS. Use [Dokploy's native domain management](https://docs.dokploy.com/docs/core/docker-compose/domains); it generates Traefik routing labels. No manual labels or host ports are needed.
4. On Dokploy versions with per-service network attachment settings (introduced in [v0.30.0](https://dokploy.com/blog/v0-30-0-networks-management-dns-and-vault-providers-concurrent-builds-and-env-encryption)), disable **Isolated Deployment** in Advanced. Keep all three services on the Compose `backend` network. Attach only `app` to `dokploy-network` (or a dedicated ingress network also attached to Traefik); detach `db` and `redis` from any Dokploy-added ingress/shared networks. Apply through service network settings and redeploy, rather than relying on a one-off container network change.
5. Older Dokploy versions without those settings should also leave **Isolated Deployment** disabled: native Domains adds the ingress network to `app`, while explicit `backend` membership keeps database DNS reachable. [Isolated Deployment](https://docs.dokploy.com/docs/core/docker-compose/utilities#isolated-deployments) attaches every service and Traefik to a deployment network; it isolates the whole stack from other deployments but also makes the databases reachable from Traefik. Avoid it for this split backend/ingress setup.
6. Use **Preview Compose** before deploying. Confirm all three services retain a common `backend` network (Dokploy may suffix its name), only `app` joins the ingress network, and routing targets `8080` on the network shared with Traefik. Check actual attachments after deployment too; UI behavior varies by Dokploy version. Do not fix missing connectivity by putting the databases on `dokploy-network`.

Request path: browser → Cloudflare → Traefik → `app:8080` on ingress; app → PostgreSQL/Redis on `backend`. No service publishes a host port.

Required Dokploy variables:

```text
SCHEDULER_IMAGE=ghcr.io/s24407-pj/scheduler@sha256:<digest from successful Security run>
SPRING_PROFILE=prod
DB_USERNAME=scheduler
DB_PASSWORD=<strong secret>
CORS_ORIGINS=https://<dashboard-domain>
R2_ENDPOINT=<Cloudflare R2 S3 endpoint>
R2_ACCESS_KEY=<secret>
R2_SECRET_KEY=<secret>
R2_BUCKET_NAME=scheduler
R2_PUBLIC_URL=<public base URL for R2 objects>
OTP_GLOBAL_DAILY_SMS_CAP=<positive production limit>
JUSTSEND_APP_KEY=<secret App-Key>
JUSTSEND_SENDER=<sender permitted on the JustSend account>
```

Keep frontend and API on HTTPS subdomains of the same site. Production enables Secure, HttpOnly, host-only `SESSION` cookies with SameSite=Lax. Use explicit CORS origins and credentials, with no broad cookie Domain. Verify forwarded HTTPS detection for the CSRF cookie.

## Client IP for rate limiting

The app uses `request.remoteAddr` for every per-IP limit (see [architecture.md](./architecture.md#rate-limiting-redis)). Behind Traefik that is the Traefik container IP, so with the defaults **all users share one rate-limit bucket** (10 staff logins per minute in total).

Cloudflare sends the real client IP in `CF-Connecting-IP`. Traefik does not trust Cloudflare by default and rewrites `X-Forwarded-For` to the Cloudflare edge IP, so use `CF-Connecting-IP`:

1. Inspect the ingress network on the VPS and identify the actual Traefik peer address seen by the app (use the selected ingress network name if different):

   ```bash
   docker network inspect dokploy-network
   ```

2. Set a regex matching only the trusted proxy peer address(es). For example, if Traefik is `10.0.1.2`:

   ```bash
   TRUSTED_PROXIES=10\.0\.1\.2
   SERVER_TOMCAT_REMOTEIP_REMOTE_IP_HEADER=CF-Connecting-IP
   ```

   Tomcat then takes the client IP from `CF-Connecting-IP`, but only when the direct peer is Traefik. Compose forwards both variables. By default `TRUSTED_PROXIES` is empty (trust none) and `SERVER_TOMCAT_REMOTEIP_REMOTE_IP_HEADER` is `X-Forwarded-For`. Never use a catch-all proxy regex or trust an entire shared Docker network. Recheck the peer address after network changes or container recreation.

3. **Allow only Cloudflare to reach the origin.** Anyone who can reach the VPS directly can forge `CF-Connecting-IP`. Use one of:
   - the VPS provider's firewall, allowing 80/443 only from [Cloudflare's IP ranges](https://www.cloudflare.com/ips/) (`ufw` does not work: Docker-published ports, including Traefik's, bypass it);
   - Cloudflare Tunnel (no inbound ports);
   - Authenticated Origin Pulls (Cloudflare client certificate checked by Traefik).

Rejected alternative: making Traefik trust Cloudflare ranges and having Tomcat walk `X-Forwarded-For`. Tomcat matches proxies by regex, so the Cloudflare ranges would have to be maintained as regexes in two places.

## Security before release

The release path is: main commit → build once → scan → publish the same image →
manual promotion of its registry digest. Pull requests also build and scan the production
image, without registry credentials or publication. Scheduled security checks do not build
or scan containers. See [security checks](./security.md) for policy and required PR checks.

1. Merge the change to `main` and wait for the **Security** workflow to succeed. It calls
   the reusable CI build/test/formatting/coverage job for the same commit. Publication
   requires that job, secrets, CodeQL/JAR, container scanning and dependency submission
   all succeed; failed, skipped or cancelled prerequisites cannot publish.
2. Open **Publish scanned image** and copy `SCHEDULER_IMAGE` from its summary or the
   `release-image` artifact (`release.env`). It contains a registry manifest digest,
   not the local image ID recorded by Trivy. The artifact also records the commit,
   workflow URL, local scanned image ID and platform. Tags identify a commit/run/attempt
   for navigation only; deploy the digest, never `latest` or a commit tag.
3. Set that exact `SCHEDULER_IMAGE` in Dokploy's Environment settings. Keep **Auto Deploy**
   disabled and use only `docker-compose.yml`. Preview Compose: `app.image` must contain
   the approved `ghcr.io/s24407-pj/scheduler@sha256:…`, with no `app.build`. The image is
   `linux/amd64`; the VPS must use that architecture.
4. Ensure the VPS can pull from GHCR before deployment. New packages may be private:
   either intentionally make the application package public or configure protected
   registry credentials with read-only package access in Dokploy. CI publishes using its
   job-scoped `GITHUB_TOKEN` (`packages: write`); no registry PAT is needed in CI. If an
   existing package rejects publication, grant this repository Actions access to it.
5. Trigger **Deploy** manually and verify the running image and health. On the VPS,
   in the same Compose project/environment used by Dokploy:

   ```bash
   docker compose -f docker-compose.yml ps -q app
   docker inspect <app-container-id> --format '{{.Config.Image}} {{.Image}}'
   ```

   `Config.Image` must be the selected GHCR digest; `Image` must match the scanned local
   image ID in `release-image/image-source.txt`. Check `/actuator/health` and routing as
   described below. Record the promoted digest and release run in the deployment record.

Missing `SCHEDULER_IMAGE` fails Compose interpolation. For a host-managed production
stack, use `docker compose -f docker-compose.yml pull app` followed by
`docker compose -f docker-compose.yml up -d --no-build`; never rebuild a release on the VPS.
Existing deployments need the Dokploy settings changed explicitly; repository changes
alone cannot disable an existing webhook or set registry credentials.

Keep the previous approved digest for rollback, subject to database/client compatibility.
Re-running a release pipeline may produce different bytes, so use the digest from the
specific successful run. Manual **Security** dispatch scans a candidate but does not
publish or deploy; publication occurs only on a push to `main`. There is no scheduled
container rescan in this release process.

### Consumer compatibility before promotion

For breaking API changes and consumer-visible semantic changes, reviewers must verify the
[paired PR evidence](./development.md#paired-pr-evidence). Passing backend CI and image
scans does not establish dashboard compatibility. Keep Auto Deploy disabled while the
paired changes are merged and validated.

After a squash merge, update the dashboard's `.github/scheduler-api-revision` to the
resulting backend commit, regenerate both artifacts and rerun Dashboard CI. Require
successful final Dashboard CI against the backend commit selected for deployment
before promoting either application. Record the backend digest, dashboard revision
and CI run with the agreed deployment order and compatible rollback pair. For an
incompatible transition, specify write maintenance and client refresh requirements;
verify database compatibility before rolling back.

## Persistence and verification

Keep the Compose project identity and named volumes `postgres_data` (PostgreSQL 18, `/var/lib/postgresql`) and `redis_data` (`/data`) across redeployments. Redis uses AOF (`--appendonly yes`). Health checks and `unless-stopped` restart policies apply to both dependencies. Do not run `down -v` or enable fresh-volume replacement on production data. Back up PostgreSQL and Redis and verify restoration.

Flyway applies migrations at application startup. Verify `https://<api-domain>/actuator/health` and logs for successful Flyway validation and application startup; production health hides component details. A missing JustSend credential fails startup before SMS can be sent.

To diagnose networking, inspect the deployed containers' network memberships and aliases. From the app's network namespace (or a temporary diagnostic container attached to the same backend network), resolve `db` and `redis`, then test TCP connections to `db:5432` and `redis:6379`. From Traefik's ingress network, check HTTP access to app port `8080` once the app is running. A successful Compose render alone does not prove Dokploy's deployed attachments or HTTP routing work.

All instances must share `SPRING_SESSION_DATA_REDIS_NAMESPACE` and Redis, with indexed sessions enabled. The anonymous default is `SPRING_SESSION_TIMEOUT=24h`; authenticated customer sessions have an absolute 24-hour deadline and staff sessions have a 24-hour inactivity timeout. Keep Redis private and trusted: it holds serialized security contexts. Deploy frontend changes together with the API; existing sessions require re-login. Scheduled jobs still assume a single instance.

The pre-production Flyway history was consolidated with explicit authorization. Existing developer databases must be recreated before using the new V1 baseline; never point this fresh-only baseline at a database containing an earlier applied V1. The application does not reset databases automatically. Configure a positive `OTP_GLOBAL_DAILY_SMS_CAP`, shared across OTP and booking notifications.

## Native customer booking contract (ADR-0018)

Release the API, staff dashboard and customer website as one coordinated contract change. There are no compatibility adapters for removed phone-only staff booking fields, flat first-visit names or OTP name fields. Refresh open clients so they use the new payloads; do not silently transform an unresolved old submission into a new booking. The same-body/key recovery guarantee applies to the current contract. See [ADR-0018](./adr/0018-explicit-booking-customer-intent.md).

An environment already using the authorized company-local V1 baseline upgrades in place with `V2__customer_profile_revision.sql`, which adds the customer row version. V1 is unchanged by this feature. No data reset, Flyway rebaseline or checksum bypass is needed for V2. Verify the migration on an isolated copy before rollout and retain backups; rolling the API back while leaving new clients active is unsupported.

The customer website needs a fixed private upstream `SCHEDULER_API_ORIGIN` and build-time `VITE_SCHEDULER_COMPANY_ID` plus `VITE_SCHEDULER_SERVICE_MAP` (marketing service ID to Scheduler offering UUID). Keep the upstream private and route same-origin `/api` traffic to Spring. The relay forwards each browser's own cookies and approved response cookies; it must never use a shared login or cookie jar. Responses with sessions, readiness, quotes or reservations must be `Cache-Control: no-store` at the relay and any CDN. Verify HttpOnly/host-only/Secure/SameSite cookie behavior, CSRF refresh after authentication and the customer context header through the actual proxy chain. Preserve the explicit trusted-proxy/IP policy described above.

Before enabling booking links, confirm mapped offerings belong to the configured salon and are active, establish employee schedules/qualification, and perform the [read-only phone-key audit](./identity.md#phone-key-audit-before-enabling-native-booking) when an existing dataset is used. Exercise first-time and returning OTP booking, profile-establishment conflict, revision conflict and lost-response replay using test SMS infrastructure. Do not send production SMS as a test. A successful booking retry returns the current state, including cancellation; it must not allocate again or emit a second confirmation/conversion.

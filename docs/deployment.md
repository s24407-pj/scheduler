# Deployment

Target: a VPS running [Dokploy](https://dokploy.com/) (Traefik as the reverse proxy), with DNS proxied through Cloudflare.

`docker-compose.yml` is the production configuration: `prod` by default, required deployment settings, no host port mappings, and a project-scoped `backend` bridge shared explicitly by `app`, `db`, and `redis`. The app uses `db:5432` and `redis:6379`. The bridge is not an external/shared network; it permits outbound access needed for R2 and Twilio.

## Dokploy setup and networking

1. Create a **Compose** service connected to `s24407-pj/scheduler`, select the deployment branch, and use `docker-compose.yml`. It builds the Java 25 `Dockerfile`. Do not include `docker-compose.dev.yml` or copy the local `.env.example` into production.
2. Set the required values below in Dokploy's protected **Environment** settings. They are referenced by Compose and forwarded to the appropriate containers; missing required values fail Compose interpolation.
3. In **Domains**, route the API hostname to service `app`, container port `8080`, path `/`, with HTTPS. Use [Dokploy's native domain management](https://docs.dokploy.com/docs/core/docker-compose/domains); it generates Traefik routing labels. No manual labels or host ports are needed.
4. On Dokploy versions with per-service network attachment settings (introduced in [v0.30.0](https://dokploy.com/blog/v0-30-0-networks-management-dns-and-vault-providers-concurrent-builds-and-env-encryption)), disable **Isolated Deployment** in Advanced. Keep all three services on the Compose `backend` network. Attach only `app` to `dokploy-network` (or a dedicated ingress network also attached to Traefik); detach `db` and `redis` from any Dokploy-added ingress/shared networks. Apply through service network settings and redeploy, rather than relying on a one-off container network change.
5. Older Dokploy versions without those settings should also leave **Isolated Deployment** disabled: native Domains adds the ingress network to `app`, while explicit `backend` membership keeps database DNS reachable. [Isolated Deployment](https://docs.dokploy.com/docs/core/docker-compose/utilities#isolated-deployments) attaches every service and Traefik to a deployment network; it isolates the whole stack from other deployments but also makes the databases reachable from Traefik. Avoid it for this split backend/ingress setup.
6. Use **Preview Compose** before deploying. Confirm all three services retain a common `backend` network (Dokploy may suffix its name), only `app` joins the ingress network, and routing targets `8080` on the network shared with Traefik. Check actual attachments after deployment too; UI behavior varies by Dokploy version. Do not fix missing connectivity by putting the databases on `dokploy-network`.

Request path: browser → Cloudflare → Traefik → `app:8080` on ingress; app → PostgreSQL/Redis on `backend`. No service publishes a host port.

Required Dokploy variables:

```text
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
TWILIO_ACCOUNT_SID=<AC... account SID>
TWILIO_API_KEY_SID=<SK... API key SID>
TWILIO_API_KEY_SECRET=<API key secret>
TWILIO_AUTH_TOKEN=<optional account Auth Token instead of API key SID/secret>
TWILIO_MESSAGING_SERVICE_SID=<MG... Messaging Service SID>
```

Create a Twilio Messaging Service with an SMS sender in its sender pool. Supply the account and Messaging Service SIDs plus either an API key SID/secret for that account or its Auth Token. If `TWILIO_AUTH_TOKEN` is set, it takes precedence and the SDK authenticates with the Account SID; otherwise it uses the API key. Leave the unused credential fields empty. The Messaging Service chooses the sender; no `From` number is configured. SDK and HTTP-client retries are disabled so each durable attempt makes one provider request. Keep secrets in Dokploy's protected environment settings. A 401 response with Twilio code `70051` means Twilio rejected the credentials; check the account, key status and Messages permission for a Restricted key.

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

## Persistence and verification

Keep the Compose project identity and named volumes `postgres_data` (PostgreSQL 18, `/var/lib/postgresql`) and `redis_data` (`/data`) across redeployments. Redis uses AOF (`--appendonly yes`). Health checks and `unless-stopped` restart policies apply to both dependencies. Do not run `down -v` or enable fresh-volume replacement on production data. Back up PostgreSQL and Redis and verify restoration.

Flyway applies migrations at application startup. Verify `https://<api-domain>/actuator/health` and logs for successful Flyway validation and application startup; production health hides component details. Missing Twilio settings fail startup.

To diagnose networking, inspect the deployed containers' network memberships and aliases. From the app's network namespace (or a temporary diagnostic container attached to the same backend network), resolve `db` and `redis`, then test TCP connections to `db:5432` and `redis:6379`. From Traefik's ingress network, check HTTP access to app port `8080` once the app is running. A successful Compose render alone does not prove Dokploy's deployed attachments or HTTP routing work.

All instances must share `SPRING_SESSION_DATA_REDIS_NAMESPACE` and Redis, with indexed sessions enabled. The anonymous default is `SPRING_SESSION_TIMEOUT=24h`; authenticated customer sessions have an absolute 24-hour deadline and staff sessions have a 24-hour inactivity timeout. Keep Redis private and trusted: it holds serialized security contexts. Deploy frontend changes together with the API; existing sessions require re-login. Scheduled jobs still assume a single instance.

The pre-production Flyway history was consolidated with explicit authorization. Existing developer databases must be recreated before using the new V1 baseline; never point this fresh-only baseline at a database containing an earlier applied V1. The application does not reset databases automatically. Configure a positive `OTP_GLOBAL_DAILY_SMS_CAP`, shared across OTP and booking notifications.

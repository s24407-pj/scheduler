# Deployment

Target: a VPS running [Dokploy](https://dokploy.com/) (Traefik as the reverse proxy), with DNS proxied through Cloudflare.

Request path: browser → Cloudflare edge → Traefik container on `dokploy-network` → app container.

> **Pending:** none of the steps below are applied in the repo yet. `docker-compose.yml` is still the local-development setup.

## Client IP for rate limiting

The app uses `request.remoteAddr` for every per-IP limit (see [architecture.md](./architecture.md#rate-limiting-redis)). Behind Traefik that is the Traefik container IP, so with the defaults **all users share one rate-limit bucket** (10 staff logins per minute in total).

Cloudflare sends the real client IP in `CF-Connecting-IP`. Traefik does not trust Cloudflare by default and rewrites `X-Forwarded-For` to the Cloudflare edge IP, so use `CF-Connecting-IP`:

1. Find the Dokploy network subnet on the VPS:

   ```bash
   docker network inspect dokploy-network -f '{{range .IPAM.Config}}{{.Subnet}}{{end}}'
   ```

2. Set, for example for `10.0.1.0/24`:

   ```bash
   TRUSTED_PROXIES=10\.0\.1\.\d{1,3}
   SERVER_TOMCAT_REMOTEIP_REMOTE_IP_HEADER=CF-Connecting-IP
   ```

   Tomcat then takes the client IP from `CF-Connecting-IP`, but only when the direct peer is Traefik. Spring Boot binds the second variable without code changes, but `docker-compose.yml` must list it under `environment:` (Dokploy's environment variables reach the container only when the compose file references them).

3. **Allow only Cloudflare to reach the origin.** Anyone who can reach the VPS directly can forge `CF-Connecting-IP`. Use one of:
   - the VPS provider's firewall, allowing 80/443 only from [Cloudflare's IP ranges](https://www.cloudflare.com/ips/) (`ufw` does not work: Docker-published ports, including Traefik's, bypass it);
   - Cloudflare Tunnel (no inbound ports);
   - Authenticated Origin Pulls (Cloudflare client certificate checked by Traefik).

Rejected alternative: making Traefik trust Cloudflare ranges and having Tomcat walk `X-Forwarded-For`. Tomcat matches proxies by regex, so the Cloudflare ranges would have to be maintained as regexes in two places.

## Compose changes before going live

A production compose file (for example `docker-compose.prod.yml`) should:

- **Not publish `5432`, `6379`, or `8080`.** On a VPS, Docker-published ports bypass `ufw`. Exposed Redis lets anyone read `otp:<phone>` codes (log in as any customer) or delete every session; Postgres would be reachable with its password. Traefik reaches the app over `dokploy-network`.
- **Set `SPRING_PROFILE=prod`.** The compose default is `dev`, which seeds the `admin123` / `employee123` accounts and accepts the dev `JWT_SECRET`.
- **Set a unique `JWT_SECRET`** (`openssl rand -base64 48`). With `prod` and the default secret, startup fails by design.
- Set `TRUSTED_PROXIES` and `SERVER_TOMCAT_REMOTEIP_REMOTE_IP_HEADER` as above, and `CORS_ORIGINS` to the real frontend origins.
- Use strong `DB_PASSWORD`, and configure a real `SmsSender` (`ConsoleSmsSender` only logs, see the known gap in [architecture.md](./architecture.md#notifications-and-scheduled-jobs)).

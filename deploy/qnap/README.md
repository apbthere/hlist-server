# Running HList on a QNAP NAS with HTTPS

This runs HList on a QNAP TS-251+ (Intel, `linux/amd64`) behind [Caddy](https://caddyserver.com/),
which gets a free Let's Encrypt certificate for your myQNAPcloud name and renews it automatically.

```
Internet ──► router :80/:443 ──► NAS :8880/:8443 ──► Caddy ──► HList server (not exposed)
```

## What you need

- Container Station installed on the NAS (App Center → Container Station).
- SSH enabled on the NAS: Control Panel → Network & File Services → Telnet / SSH → *Allow SSH connection*.
- Your myQNAPcloud name (e.g. `<your-name>.myqnapcloud.com`) pointing at your home IP. myQNAPcloud
  keeps it up to date.
- Docker Desktop on the Mac, with `server/` and `hlist-client/` checked out side by side.

## 1. Build the image on the Mac

```sh
cd server
./deploy/qnap/build-image.sh
```

This builds for the NAS's Intel CPU and writes `deploy/qnap/hlist-image.tar.gz` (about 150 MB).

## 2. Copy the files to the NAS

Replace `admin@<nas-ip>` with your NAS login and local address:

```sh
ssh admin@<nas-ip> 'mkdir -p /share/Container/hlist'
scp deploy/qnap/compose.yml deploy/qnap/.env.example deploy/qnap/hlist-image.tar.gz \
    admin@<nas-ip>:/share/Container/hlist/
```

## 3. Configure

On the NAS:

```sh
ssh admin@<nas-ip>
cd /share/Container/hlist
cp .env.example .env
openssl rand -base64 18        # use the output as the invite code
vi .env                        # set HLIST_DOMAIN and HLIST_INVITE_CODE
```

Keep the invite code long and random, and give it only to people who should have accounts.

`HLIST_DOMAIN` also tells the server where passkeys (Face ID / Touch ID sign-in) work. A passkey belongs to
that domain, so changing it later makes existing passkeys stop working; passwords still work.

## 4. Forward ports on the router

In the TP-Link router's port forwarding (often under *Advanced → NAT Forwarding → Virtual Servers*),
add two rules pointing at the NAS's local IP:

| External port | Internal port (NAS) | Protocol |
|---|---|---|
| 80 | 8880 | TCP |
| 443 | 8443 | TCP |

Port 80 must be forwarded: Let's Encrypt uses it to confirm you control the name. If the router's
own remote management uses port 443, turn it off (*System Tools → Administration → Remote
Management*), or the rule can't take effect.

Give the NAS a fixed local IP (a DHCP reservation in the router) so the rules keep working.

## 5. Start it

```sh
cd /share/Container/hlist
docker load < hlist-image.tar.gz
docker compose up -d
docker compose logs -f caddy     # watch for "certificate obtained successfully", then Ctrl+C
```

Open `https://<your-name>.myqnapcloud.com/`. Phones and tablets get the iOS app, desktops the classic
pages. Create the first account with the invite code.

Both containers restart automatically after a NAS reboot. They also appear in Container Station, where
you can see their status and logs.

## Updating to a new version

On the Mac, run `./deploy/qnap/build-image.sh` and copy `hlist-image.tar.gz` to the NAS again. Then on
the NAS:

```sh
cd /share/Container/hlist
docker load < hlist-image.tar.gz
docker compose up -d
```

Your data and certificates are kept in Docker volumes, so they survive updates.

## Backups

The database is in the `hlist_server-data` volume. To copy it out:

```sh
docker compose stop server
docker run --rm -v hlist_server-data:/data -v "$PWD":/backup alpine \
    tar czf /backup/hlist-data-$(date +%F).tar.gz -C /data .
docker compose start server
```

## Troubleshooting

- **No certificate / Caddy logs show "timeout during connect":** Let's Encrypt couldn't reach port 80.
  Check the router rule for 80 → 8880 and that `HLIST_DOMAIN` resolves to your current home IP
  (`nslookup <your-name>.myqnapcloud.com`). Don't restart over and over: Let's Encrypt limits failed
  attempts, and Caddy already retries on its own.
- **It works from outside but not on home Wi-Fi:** some routers don't route traffic for their own
  public IP back inside ("NAT loopback"). Try from mobile data, or use the NAS's local address at home.
- **A port is already in use:** change `HTTP_PORT` / `HTTPS_PORT` in `.env`, update the router
  rules to match, and run `docker compose up -d` again.

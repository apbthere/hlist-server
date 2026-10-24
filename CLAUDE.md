# HList server

Spring Boot server for HList, a self-hosted shopping list app. It serves the JSON API under `/api`, a desktop web UI
(plain HTML/JS in `src/main/resources/static/`) and the iOS-style mobile client (the sibling `../hlist-client`
repository's build) under `/app/`. All on one origin, so cookies work without CORS. See `../hlist-client/CLAUDE.md`
for the client.

## Stack

- Java 25, Spring Boot 4.1, Spring Security 7, Spring Data JDBC, Jackson 3 (`tools.jackson.*`; `JacksonException`
  is a `RuntimeException`, so don't list it next to `RuntimeException` in a multi-catch).
- H2 file database at `${H2_DB_PATH:./data/server}` (`DATABASE_TO_LOWER=TRUE`). The schema is
  `src/main/resources/schema.sql`, run on every start: `CREATE TABLE IF NOT EXISTS` plus idempotent
  `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`/`ADD CONSTRAINT IF NOT EXISTS`. There are no migrations, so new
  columns must be added this way.
- Columns the database maintains (`created_at`, `updated_at`, `item.photo_id`) are `@ReadOnlyProperty` on the
  records in `model/`.

## Commands

```sh
./mvnw test                      # all tests (about 90; under a minute)
./mvnw -q -DskipTests package    # target/server-0.0.1-SNAPSHOT.jar
./mvnw clean test                # when tests fail with "Unresolved compilation problem" (see Gotchas)
java -jar target/server-0.0.1-SNAPSHOT.jar                     # http://localhost:8080, data in ./data/
H2_DB_PATH=/tmp/x/db java -jar target/server-*.jar --server.port=18080   # throwaway instance for experiments
```

The server serves the client from `../hlist-client/dist/` (`hlist.client.location`), so run `npm run build` there
first. CI (`.github/workflows/ci.yml`) runs `./mvnw verify` on Java 25.

## Layout (`src/main/java/com/abcconsulting/slme/server/`)

- `config/`: security (form login at `/api/auth/login`, persistent remember-me with `RenewableRememberMeServices`,
  CSRF via `csrf.spa()`, passkeys), `/app/` serving with SPA fallback, scheduling.
- `controller/`: `/api/shopping-lists`, items, departments, brands, users, auth, `/api/version` (build
  watermark), `ApiExceptionHandler` (IllegalArgumentException → 400 with `{message}`, shown to users as is).
- `service/ShoppingListService`: lists (newest first), items, catalog. `UserAccountService`: case-insensitive
  usernames. `RegistrationPolicy`: invite code (`hlist.invite-code`).
- `live/`: server-sent events at `/api/events`. Services publish `DataChangedEvent` (lists, list-items(listId),
  items, catalog); delivered after commit by `@TransactionalEventListener(fallbackExecution = true)`. The
  `X-HList-Client` request header becomes the event's origin, so a device ignores its own changes.
- `stores/`: recognises stores in list names (Publix, Costco, Aldi, Walmart, Target, Home Depot, Lowe's, Whole Foods,
  The Fresh Market) and fetches
  and caches their icons (`/api/store-icons/{key}`). A store can have more than one website (`Store.otherDomains`:
  costcobusinessdelivery.com is Costco); "Add to HList" picks the list by them.
- `photos/`: item photos (see below).
- `web/`: `SafeDownloader` and `AddressPolicy`, the only way the server fetches outside URLs (HTTPS only, public
  addresses only, checked again on every redirect, size and time limits). Use it for any new outbound fetch.
- `assistant/`: the MCP server for AI assistants (see below).
- `demo/`: `DemoData` seeds an empty database with the demo account (demo/demo), lists and a past trip when
  `hlist.demo=true` (env `HLIST_DEMO`, set by the Dockerfile's `demo` stage and `compose.demo.yml`), then fetches
  product photos in the background. It never touches a database that has accounts. The production image must
  stay the Dockerfile's last stage.

## Passkeys

Face ID / Touch ID sign-in through Spring Security's WebAuthn support (`spring-security-webauthn`, webauthn4j).

- Spring's endpoints, outside `/api`: `POST /webauthn/register/options` and `/webauthn/register` add a passkey to
  the signed-in user; `POST /webauthn/authenticate/options` and `/login/webauthn?remember-me=true` sign in. Ours:
  `GET /api/auth/passkeys` (origins where passkeys work, public) and `GET/DELETE /api/users/me/passkeys[/{id}]`.
- Stored by Spring's JDBC repositories in `user_entities` (one random user handle per username) and
  `user_credentials`.
- `PasskeySettings`: the relying party ID is `hlist.domain` (env `HLIST_DOMAIN`, also Caddy's domain), default
  `localhost` with origins `http://localhost:<port>` and the Vite dev server. LAN addresses can't use passkeys.
- `SecurityConfiguration` post-processes the `WebAuthnAuthenticationFilter`: our 204/401 handlers, remember-me,
  and the session authentication strategy (new session ID and CSRF token), which Spring's configurer leaves out.
- User verification is required. `PasskeyTests` registers and signs in with webauthn4j-test's software
  authenticator; keep `webauthn4j-test`'s version in step with the `webauthn4j-core` Spring brings in.
- The desktop pages use `hlist.passkeys` in `static/api.js`; the mobile client has `src/lib/passkeys.ts`.

## AI assistants (`assistant/`)

An MCP server at `POST /mcp` (Streamable HTTP, plain JSON replies, no sessions; `GET` answers 405) lets Claude or
another assistant find lists, read them, search the user's item history (`search_item_history`, optionally by store)
and add items (`add_item_to_list`: copy a past item with `fromItemId`, or a new one with a `productUrl` for its
photo). The workflow the assistant should follow is in `McpController.INSTRUCTIONS`.

- Auth: access tokens (`hlist_...`, stored as SHA-256 in `access_token`), created in the mobile client's Account
  sheet or `POST /api/users/me/access-tokens`. `AccessTokenFilter` accepts `Authorization: Bearer hlist_...` (or the bare token, for the xAI API) on `/mcp` only.
  The setup guide for each assistant is `docs/connecting-assistants.md`.
- Claude Code: `claude mcp add --transport http hlist https://<host>/mcp --header "Authorization: Bearer <token>"`.
- OAuth (ChatGPT, claude.ai, the Claude apps): `OAuthController`/`OAuthService` implement the MCP authorization spec.
  A 401 from `/mcp` names `/.well-known/oauth-protected-resource/mcp`; clients register themselves at
  `/oauth/register` (stored in `oauth_client`), send the user to `/oauth/authorize`, which forwards to
  `static/authorize.html` (sign in, Allow), then trade the code with PKCE (S256 only) at `/oauth/token` for an ordinary
  access token named after the client. Reconnecting replaces that client's token. Pending approvals and codes live in
  memory. `/oauth/token`, `/oauth/register` and the metadata have their own security chain (no CSRF, sessions or
  Basic sign-in). `/mcp` accepts only access tokens, never a session cookie, so it needs no CSRF token.
- Adding never duplicates: an item already on the list is reported, or unchecked if it was checked off.
- Product pages of another brand: assistants pick links from search results without opening them. When the page's
  brand (or, without one, its product name) isn't the item's brand, `add_item_to_list` warns (and doesn't use a page
  that names another brand) and `set_item_photo` refuses. A past item copied as another brand loses its photo.
- Departments are never created by the assistant (they're shared); brands are.

## Photos (`photos/`)

- Upload: `POST /api/photos` with the raw image body. `ImageProcessor` accepts JPEG/PNG/GIF only (ImageIO can't
  read WebP/AVIF/HEIC; the client converts first), downsizes to 1600 px, re-encodes as JPEG (drops EXIF/GPS) and
  makes a 256 px thumbnail. Photos are private to their owner and immutable (cached for a year).
- `PUT/DELETE /api/items/{id}/photo` attaches/removes. One photo per item; replaced photos are deleted, and unused
  ones are removed nightly (`PhotoService.deleteUnusedPhotos`).
- `POST /api/photos/import {url}` (`PhotoImporter`): an image address, or a product page. Its picture is, in order,
  the schema.org Product's `image` (Whole Foods' og:image is a site banner), og:image, then a preloaded image
  (Instacart storefronts such as delivery.thefreshmarket.com name a "missing-item" placeholder in their Product).
  Placeholder images are skipped.
  It asks for a larger `width=` and sends `Accept: image/jpeg,...` (Costco's CDN converts AVIF on request). It
  returns the page's product name (og:title, without " | Site") and brand (schema.org JSON-LD `brand`), with the
  brand dropped from the front of the name.
- `GET /api/photos/suggestions?name=&brand=` (`ProductPhotoFinder`): product photos from Open Food Facts, searched
  in this order:
  1. The brand among US products.
  2. A close spelling of the brand (`brands:word~1`).
  3. The brand among all countries (many products have no country tag).
  4. The older `world.openfoodfacts.org/cgi/search.pl`, whose index has products the newer one misses. It answers
     503 when busy, so it's retried after 1 and 3 seconds.
  5. Only then, any brand sold in the US.

  Every result must contain the item's words, and brand searches must have a similar brand. Results are cached
  for a day; failures aren't cached.

## Testing conventions

- `@SpringBootTest` + MockMvc for API tests. Outbound fetchers are tested against a local
  `com.sun.net.httpserver.HttpServer` with `new SafeDownloader(address -> true, false)` (allow-all policy, plain
  HTTP). See `PhotoImporterTests`, `ProductPhotoFinderTests` and `StoreIconFetcherTests`.
- `photos/TestImages` builds JPEG/PNG test images, including one with GPS EXIF and a PNG that only claims a size.

## Gotchas

- **VS Code's Java extension compiles into `target/`.** If a file was briefly broken while open in VS Code,
  Maven may run stale classes and report "Unresolved compilation problem". Run `./mvnw clean test`.
- **H2 file lock.** A stopping server takes up to 30 s (graceful shutdown) and holds `data/server.mv.db`. Wait for
  the old process to exit before starting another one on the same database, or it fails with an MVStore error.
- **H2 generated keys.** With more than one generated column, `KeyHolder` needs the key named:
  `connection.prepareStatement(sql, new String[]{"photo_id"})`.
- **Server-sent events.** Never call `completeWithError` from the heartbeat thread. Just drop the emitter.
- **Remember-me.** Parallel requests rotating the same token used to trigger `CookieTheftException`.
  `RenewableRememberMeServices` locks per series and accepts the just-replaced token for 60 s. Keep that.
- **Sites that block servers.** publix.com answers server requests with an Akamai bot challenge. Don't try to get
  around it. Publix products come in through the client's "Add to HList" bookmark instead
  (`../hlist-client/src/lib/addFromPage.ts`). Publix's image CDN (`images.publixcdn.com`) is not blocked.
- **Open Food Facts** asks apps to identify themselves. Its downloader sends `HList/1.0 (self-hosted shopping
  list)`; don't change that to a browser User-Agent.

## Deploying (QNAP NAS)

Production runs in Docker on a QNAP NAS behind Caddy (Let's Encrypt), at the `HLIST_DOMAIN` in the NAS's `.env`.
`deploy/qnap/README.md` has the one-time setup. To release, run `/deploy` (`.claude/skills/deploy/`), or
`sh deploy/qnap/release.sh` yourself (`--check` only checks the repositories and the NAS). The NAS's ssh login is
in `deploy/qnap/release.env` (`HLIST_NAS=...`, git-ignored) or the `HLIST_NAS` variable. The script does these
steps:

1. Commit and push both repositories, so the build watermark names real commits.
2. `sh deploy/qnap/build-image.sh`. It needs Docker Desktop running (the release script starts it) and writes `deploy/qnap/hlist-image.tar.gz`
   (linux/amd64, server and client built together; git-ignored).
3. On the NAS (over SSH). If `deploy/qnap/compose.yml` changed, copy it to `/share/Container/hlist/` too:
   - Copy `server.mv.db` from the `server-data` Docker volume into `/share/Container/hlist/backups/` with a
     timestamp.
   - Copy the image to `/share/Container/hlist/`.
   - Run `docker load < hlist-image.tar.gz && docker compose up -d`. Container Station's `docker` isn't on the
     SSH `PATH`; it's in `/share/CACHEDEV1_DATA/.qpkg/container-station/bin`.
4. Check the server log for "Started ServerApplication", then check that `GET /api/version` shows the new commits.

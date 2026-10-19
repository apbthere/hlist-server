# API reference

Every endpoint the server answers. [architecture.md](architecture.md) explains the security model behind the
**Auth** column.

**Auth** means:

- **Public**: no sign-in.
- **User**: signed in with the session or remember-me cookie, or with Basic credentials.
- **Token**: `Authorization: Bearer hlist_...`.
- **OAuth client**: an assistant's server, with no cookies and no CSRF token.

**Conventions:**

- **CSRF:** every POST, PUT, PATCH and DELETE needs the `X-XSRF-TOKEN` header, holding the `XSRF-TOKEN`
  cookie's value, even when not signed in (e.g. sign-up and sign-in). `GET /api/auth/csrf` sets the cookie.
  Exempt: requests with an `Authorization` header, `/mcp` and the OAuth client endpoints. Without the header the
  answer is 401.
- Bodies are JSON unless noted.
- Errors are `{"message": "..."}`, with status 400 (invalid input), 401, 404 (not found or not yours), 409 (still
  in use) or 413 (photo too large).
- Clients may send an `X-HList-Client` header, which is echoed in live-update events (see below).

## Sign-in and account

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| GET | `/api/auth/csrf` | Public | Sets the `XSRF-TOKEN` cookie |
| GET | `/api/auth/registration` | Public | Whether sign-up needs an invite code |
| POST | `/api/auth/login` | Public | Password sign-in. Form-encoded `username`, `password`, optional `remember-me=true`. 204 or 401 |
| POST | `/api/auth/logout` | User | Signs out. 204 |
| POST | `/api/users` | Public | Creates an account: `{username, password, inviteCode?}`. 201 |
| GET | `/api/users/me` | User | `{username}` |
| PUT | `/api/users/me/password` | User | `{currentPassword, newPassword}`; signs out other devices. 204 |

## Passkeys

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| GET | `/api/auth/passkeys` | Public | The origins where passkeys work, so pages offer them only there |
| POST | `/webauthn/register/options` | User | Options for adding a passkey (Spring Security) |
| POST | `/webauthn/register` | User | Saves the new passkey (Spring Security) |
| POST | `/webauthn/authenticate/options` | Public | A sign-in challenge (Spring Security) |
| POST | `/login/webauthn?remember-me=true` | Public | Signs in with a passkey. 204 or 401 |
| GET | `/api/users/me/passkeys` | User | The user's passkeys |
| DELETE | `/api/users/me/passkeys/{id}` | User | Removes a passkey |

## Shopping lists

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| GET | `/api/shopping-lists?page=0&size=20` | User | The user's lists, newest first, each with the store its name mentions. Paged; `size` up to 100 |
| POST | `/api/shopping-lists` | User | Creates a list: `{name}`. 201 |
| PATCH | `/api/shopping-lists/{listId}/completion` | User | `{completed}`: marks the list done or not. 204 |
| GET | `/api/shopping-lists/{listId}/items` | User | The list's items, with brand, department, photo and checked-off state |
| POST | `/api/shopping-lists/{listId}/items` | User | Adds an item: `{name, quantity?, departmentId?, brandId?, comment?}`. 201 |
| PUT | `/api/shopping-lists/{listId}/items/{itemId}` | User | Changes an item on the list (same body) |
| PATCH | `/api/shopping-lists/{listId}/items/{itemId}/completion` | User | `{completed}`: checks the item off or back on. 204 |
| DELETE | `/api/shopping-lists/{listId}/items/{itemId}` | User | Removes the item from the list. 204 |

## Items and catalog

Items are a user's history of things they buy, reused across lists. Departments and brands are shared by all
users.

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| GET | `/api/items` | User | All the user's items |
| POST | `/api/items` | User | Creates an item without adding it to a list (same body as list items). 201 |
| PUT | `/api/items/{itemId}` | User | Changes an item |
| DELETE | `/api/items/{itemId}` | User | Deletes an item and removes it from every list. 204 |
| GET | `/api/departments` | User | All departments |
| POST | `/api/departments` | User | `{name}`. 201 |
| PUT | `/api/departments/{id}` | User | Renames: `{name}` |
| DELETE | `/api/departments/{id}` | User | Deletes; 409 while items use it |
| GET | `/api/brands` | User | All brands |
| POST | `/api/brands` | User | `{name}`. 201 |
| PUT | `/api/brands/{id}` | User | Renames: `{name}` |
| DELETE | `/api/brands/{id}` | User | Deletes; 409 while items use it |

## Photos

Photos are private to their owner and never change once stored.

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| POST | `/api/photos` | User | Uploads a photo: the raw JPEG, PNG or GIF as the body, up to 10 MB. 201 `{photoId, width, height}` |
| POST | `/api/photos/import` | User | `{url}`: an image's address, or a product page whose og:image is used. 201, with the page's product `title` and `brand` |
| GET | `/api/photos/suggestions?name=&brand=` | User | Product photos from Open Food Facts that match the item |
| GET | `/api/photos/{photoId}` | User | The JPEG, up to 1600 px. Cached for a year |
| GET | `/api/photos/{photoId}/thumbnail` | User | A 256 px JPEG. Cached for a year |
| PUT | `/api/items/{itemId}/photo` | User | `{photoId}`: sets the item's photo. 204 |
| DELETE | `/api/items/{itemId}/photo` | User | Removes the item's photo. 204 |

## Stores

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| GET | `/api/stores` | Public | The stores HList recognises in list names, with their websites |
| GET | `/api/store-icons/{key}` | Public | A store's icon, fetched from its website on first use |

## Live updates

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| GET | `/api/events` | User | Server-sent events (`text/event-stream`) |

The stream starts with a `ready` event. After that, each `change` event names what to reload:

```json
{"type": "list-items", "listId": 12, "origin": "<the X-HList-Client header of the request that made the change>"}
```

`type` is `lists`, `list-items`, `items` or `catalog`. There is no `origin` when the change came from a request
without that header, such as an assistant's.

## AI assistants

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| POST | `/mcp` | Token | MCP over Streamable HTTP: JSON-RPC 2.0 with plain JSON replies (`initialize`, `ping`, `tools/list`, `tools/call`). The tools are listed in the [README](../README.md#ai-assistant-tools) |
| GET | `/mcp` | Token | 405: the server never opens a stream to the client |
| GET | `/api/users/me/access-tokens` | User | The user's tokens: label, created, last used |
| POST | `/api/users/me/access-tokens` | User | `{label}`. 201, with the secret `token`, shown only this once |
| DELETE | `/api/users/me/access-tokens/{id}` | User | Revokes a token |

### OAuth 2.1 (how claude.ai and ChatGPT get a token)

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| GET | `/.well-known/oauth-protected-resource[/mcp]` | Public | Protected-resource metadata (RFC 9728). A 401 from `/mcp` points here |
| GET | `/.well-known/oauth-authorization-server`, `/.well-known/openid-configuration` | Public | Authorization server metadata (RFC 8414) |
| POST | `/oauth/register` | OAuth client | Dynamic client registration (RFC 7591) |
| GET | `/oauth/authorize` | Public | Checks the request and sends the browser to `authorize.html` to sign in and approve |
| GET | `/api/oauth/requests/{requestId}` | Public | Which app is asking, for `authorize.html` |
| POST | `/api/oauth/requests/{requestId}` | User | `{approve}`: the user's answer. Returns `{redirect}`, where to send the browser |
| POST | `/oauth/token` | OAuth client | Exchanges the code with a PKCE verifier (S256) for an access token. Form-encoded |

## Other

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| GET | `/api/version` | Public | The server and client commits the build was made from, and when it was built |
| GET | `/app/**` | Public | The mobile client; paths without a file extension get `index.html` |
| GET | `/`, `/*.html` | Public | The desktop pages |

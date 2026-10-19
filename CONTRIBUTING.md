# Contributing to the HList server

Thanks for helping. This is the server: the JSON API, the desktop web UI, the MCP server for AI assistants, and
the host for the mobile client, which is [hlist-client](https://github.com/apbthere/hlist-client) and has its own
[CONTRIBUTING.md](https://github.com/apbthere/hlist-client/blob/main/CONTRIBUTING.md).

## Setup

You need Java 25 (Maven comes with the repository as `./mvnw`), and Node 24 to build the mobile client. Clone
both repositories side by side:

```sh
git clone https://github.com/apbthere/hlist-server.git
git clone https://github.com/apbthere/hlist-client.git
(cd hlist-client && npm ci && npm run build)
```

## Running it

```sh
cd hlist-server
./mvnw spring-boot:run   # http://localhost:8080 (desktop UI), http://localhost:8080/app/ (mobile)
```

Data goes to `./data/`. To experiment without touching it, run a throwaway instance:

```sh
./mvnw -q -DskipTests package
H2_DB_PATH=/tmp/hlist-try/db java -jar target/server-0.0.1-SNAPSHOT.jar --server.port=18080
```

`docker compose up --build` builds the server and the client into one image, as production does.

## Tests

```sh
./mvnw test          # all tests, under a minute
./mvnw verify        # what CI runs
./mvnw clean test    # if tests fail with "Unresolved compilation problem" (an IDE compiled into target/)
```

CI runs `./mvnw verify` on every pull request, then builds the Docker image with the client's `main` branch.
The client's browser tests (`npm run e2e` in hlist-client) run against this server's jar; run them too when you
change the API that the client uses.

- API tests use `@SpringBootTest` and MockMvc.
- Code that fetches outside URLs is tested against a local `com.sun.net.httpserver.HttpServer`, with
  `new SafeDownloader(address -> true, false)`. See `PhotoImporterTests` for an example.

## Conventions

- **Schema changes** go in `src/main/resources/schema.sql`, which runs on every start. There are no migrations,
  so they must be idempotent: `CREATE TABLE IF NOT EXISTS`, `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`.
- **Outbound requests** go through `web/SafeDownloader`: HTTPS only, public addresses only, size and time limits.
- **Messages in `IllegalArgumentException`** are shown to users as is (`ApiExceptionHandler`), so write them for
  users.
- **Be a good citizen to other sites.** Don't work around bot protection (publix.com blocks servers on purpose),
  and keep the `HList/1.0` User-Agent for Open Food Facts.
- `CLAUDE.md` describes the code in more detail, including gotchas.

## Pull requests

- Start a branch from `main` for each change, named for what it does, e.g. `fix/remember-me`,
  `feature/share-list` or `docs/contributing`.
- Write commit subjects as short sentences in the imperative, without a final period ("Add passkey sign-in").
  Use the body to explain why.
- Put `Closes #<issue>` in the description. Refer to a client issue as `apbthere/hlist-client#<issue>`.
- Add or update tests for the behaviour you change.
- If a change needs both repositories, open a pull request in each with the same branch name, and say which one
  to merge first.
- CI must pass before merging.

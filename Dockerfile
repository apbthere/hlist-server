# Builds the server together with the mobile client (the sibling hlist-client repository),
# passed in as the named build context "client":
#
#   docker build --build-context client=../hlist-client -t hlist .
#
# or simply `docker compose up --build`, which wires the context up via compose.yml.
# The build stages run on the build machine's own platform (their output is platform-neutral), so
# cross-building for another CPU (e.g. --platform linux/amd64 on Apple Silicon) stays fast.

FROM --platform=$BUILDPLATFORM node:24-slim AS client-build

WORKDIR /client
COPY --from=client package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY --from=client . .
# Build watermark compiled into the app (see deploy/qnap/build-image.sh).
ARG CLIENT_COMMIT=dev
ARG BUILD_TIME=
RUN VITE_BUILD_COMMIT="$CLIENT_COMMIT" VITE_BUILD_TIME="$BUILD_TIME" npm run build

FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk AS build

WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.m2 ./mvnw -DskipTests package

FROM eclipse-temurin:25-jre

WORKDIR /app
COPY --from=build /workspace/target/server-*.jar app.jar
COPY --from=client-build /client/dist ./client

# Serve the mobile client at /app/ (see ClientAppConfiguration).
ENV HLIST_CLIENT_LOCATION=file:/app/client/

# Build watermark reported by GET /api/version (see deploy/qnap/build-image.sh).
ARG SERVER_COMMIT=dev
ARG CLIENT_COMMIT=dev
ARG BUILD_TIME=
ENV HLIST_BUILD_SERVER_COMMIT=$SERVER_COMMIT \
    HLIST_BUILD_CLIENT_COMMIT=$CLIENT_COMMIT \
    HLIST_BUILD_TIME=$BUILD_TIME

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

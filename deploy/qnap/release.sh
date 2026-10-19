#!/bin/sh
# Releases the committed and pushed server and client to the QNAP NAS: builds the image, backs up the
# database, copies the image and compose.yml, restarts, and waits until /api/version shows the new commits.
# Usage: sh deploy/qnap/release.sh [--check]   (--check stops after the checks)
# The NAS's ssh login (e.g. admin@192.168.0.10) comes from HLIST_NAS, or from HLIST_NAS=... in
# deploy/qnap/release.env (git-ignored).
set -eu

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
client_dir="$repo_root/../hlist-client"

step() { printf '\n== %s\n' "$*"; }
fail() { echo "release: $*" >&2; exit 1; }

nas="${HLIST_NAS:-}"
if [ -z "$nas" ] && [ -f "$repo_root/deploy/qnap/release.env" ]; then
  . "$repo_root/deploy/qnap/release.env"
  nas="${HLIST_NAS:-}"
fi
[ -n "$nas" ] || fail "set HLIST_NAS to the NAS's ssh login, e.g. HLIST_NAS=admin@192.168.0.10 in deploy/qnap/release.env"
nas_dir=/share/Container/hlist
# Container Station's docker isn't on the ssh PATH.
nas_env='export PATH=/share/CACHEDEV1_DATA/.qpkg/container-station/bin:$PATH; cd '"$nas_dir"
remote() { ssh -o BatchMode=yes -o ConnectTimeout=10 "$nas" "$nas_env; $1"; }

step "Checking that both repositories are committed and pushed"
for dir in "$repo_root" "$client_dir"; do
  name="$(basename "$dir")"
  [ -z "$(git -C "$dir" status --porcelain --untracked-files=no)" ] || fail "$name has uncommitted changes"
  git -C "$dir" fetch -q
  [ -z "$(git -C "$dir" rev-list '@{u}..HEAD')" ] || fail "$name has commits that aren't pushed"
done
server_commit="$(git -C "$repo_root" rev-parse --short HEAD)"
client_commit="$(git -C "$client_dir" rev-parse --short HEAD)"
echo "server $server_commit, client $client_commit"

step "Checking the NAS"
# "No route to host" while the NAS answers from Terminal: macOS's Local Network privacy blocks the app running
# this script (System Settings → Privacy & Security → Local Network). Run it from Terminal instead.
remote true || fail "can't reach $nas over ssh (see the message above)"
domain="$(remote 'sed -n "s/^HLIST_DOMAIN=//p" .env' | tr -d '\r')"
[ -n "$domain" ] || fail "HLIST_DOMAIN isn't set in $nas_dir/.env"
echo "$nas reachable; serves https://$domain"
if [ "${1:-}" = "--check" ]; then echo "Checks passed; nothing deployed."; exit 0; fi

step "Starting Docker"
if ! docker info >/dev/null 2>&1; then
  open -a Docker
  waited=0
  until docker info >/dev/null 2>&1; do
    [ "$waited" -lt 180 ] || fail "Docker Desktop didn't start within 3 minutes"
    sleep 3; waited=$((waited + 3))
  done
fi
echo "Docker is running"

step "Building the image"
sh "$repo_root/deploy/qnap/build-image.sh"

step "Backing up the database"
remote 'volume=$(docker volume ls --format "{{.Name}}" | grep server-data) &&
  backup=backups/server-$(date +%Y%m%d-%H%M%S).mv.db &&
  cp "$(docker volume inspect -f "{{.Mountpoint}}" "$volume")/server.mv.db" "$backup" &&
  ls -l "$backup"'

step "Copying the image and compose.yml"
scp -q -o BatchMode=yes "$repo_root/deploy/qnap/hlist-image.tar.gz" "$repo_root/deploy/qnap/compose.yml" "$nas:$nas_dir/"

step "Restarting"
remote 'docker load < hlist-image.tar.gz && docker compose up -d'

step "Waiting for the new version"
expected="\"serverCommit\":\"$server_commit\",\"clientCommit\":\"$client_commit\""
waited=0
until curl -sf "https://$domain/api/version" | grep -q "$expected"; do
  # A stopping server can take 30 s to release the database before the new one starts.
  [ "$waited" -lt 240 ] || fail "https://$domain/api/version doesn't show $expected after 4 minutes; check: docker compose logs server"
  sleep 5; waited=$((waited + 5))
done
curl -s "https://$domain/api/version"; echo
remote 'docker compose logs --since 10m server 2>&1 | grep -E "Started ServerApplication|ERROR" | tail -5'
echo "Released server $server_commit and client $client_commit to https://$domain"

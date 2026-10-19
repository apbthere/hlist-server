#!/bin/sh
# Builds the HList image for the QNAP TS-251+ (Intel, linux/amd64) and saves it as
# deploy/qnap/hlist-image.tar.gz for copying to the NAS. Expects ../hlist-client next to this repo.
set -eu

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
client_dir="$repo_root/../hlist-client"
output="$repo_root/deploy/qnap/hlist-image.tar.gz"

if [ ! -f "$client_dir/package.json" ]; then
  echo "hlist-client not found at $client_dir" >&2
  exit 1
fi

# Build watermark: short commits (with "-dirty" for uncommitted changes) and the build time in UTC.
commit_of() {
  commit="$(git -C "$1" rev-parse --short HEAD)"
  if [ -n "$(git -C "$1" status --porcelain --untracked-files=no)" ]; then commit="$commit-dirty"; fi
  echo "$commit"
}
server_commit="$(commit_of "$repo_root")"
client_commit="$(commit_of "$client_dir")"
build_time="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "Building server $server_commit, client $client_commit at $build_time"

docker buildx build \
  --platform linux/amd64 \
  --build-arg SERVER_COMMIT="$server_commit" \
  --build-arg CLIENT_COMMIT="$client_commit" \
  --build-arg BUILD_TIME="$build_time" \
  --build-context client="$client_dir" \
  --tag hlist:latest \
  --load \
  "$repo_root"

docker save hlist:latest | gzip > "$output"
echo "Saved $output ($(du -h "$output" | cut -f1)): server $server_commit, client $client_commit, built $build_time"

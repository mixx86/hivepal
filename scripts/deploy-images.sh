#!/usr/bin/env bash
# Builds the relay and daemon images on this machine and loads them on the server, so a small
# VPS never compiles anything (building there is what eats its memory and fills its disk).
#
#   scripts/deploy-images.sh <ssh host> [compose dir on the server, default ~/agentring/deploy]
#
# The server's compose files must already be up to date (git pull, or a bundle for a private fork).
# Then: docker compose --profile daemon up -d --no-build relay daemon, and old images are pruned.
set -euo pipefail

HOST=${1:?usage: deploy-images.sh <ssh host> [compose dir]}
DIR=${2:-agentring/deploy}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
RELAY=${HIVEPAL_RELAY_IMAGE:-${AGENTRING_RELAY_IMAGE:-agentring-relay:local}}
DAEMON=${HIVEPAL_DAEMON_IMAGE:-${AGENTRING_DAEMON_IMAGE:-agentring-daemon:local}}

arch=$(ssh "$HOST" uname -m)
case "$arch" in
  x86_64) platform=linux/amd64 ;;
  aarch64 | arm64) platform=linux/arm64 ;;
  *) echo "unknown server architecture: $arch" >&2; exit 1 ;;
esac

echo "building $RELAY and $DAEMON for $platform"
docker build -q --platform "$platform" -t "$RELAY" "$ROOT/relay" >/dev/null
docker build -q --platform "$platform" -t "$DAEMON" "$ROOT/daemon" >/dev/null

echo "sending images to $HOST"
docker save "$RELAY" "$DAEMON" | gzip -1 | ssh "$HOST" 'gunzip | docker load -q'

ssh "$HOST" "cd $DIR && docker compose --profile daemon up -d --no-build relay daemon && docker image prune -f >/dev/null && docker builder prune -af >/dev/null"
echo "done"

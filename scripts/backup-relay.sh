#!/bin/sh
# A consistent copy of the relay's database (messages, agents, photos, voice notes…), taken with
# SQLite's VACUUM INTO inside the running relay container. Keeps the last N copies.
#
#   scripts/backup-relay.sh [backup dir, default ~/backups] [copies to keep, default 7]
#
# Daily, e.g.:  30 4 * * *  /path/to/hivepal/scripts/backup-relay.sh
set -eu
cd "$(dirname "$0")/../deploy"
DEST=${1:-$HOME/backups}
KEEP=${2:-7}
mkdir -p "$DEST"
docker compose exec -T relay node --disable-warning=ExperimentalWarning -e \
  'new (require("node:sqlite").DatabaseSync)("/data/relay.db").exec("VACUUM INTO '"'"'/data/backup-copy.db'"'"'")'
docker compose cp relay:/data/backup-copy.db "$DEST/relay-$(date +%F).db" >/dev/null
docker compose exec -T relay rm -f /data/backup-copy.db
chmod 600 "$DEST"/relay-*.db
ls -1t "$DEST"/relay-*.db | tail -n +$((KEEP + 1)) | xargs -r rm -f
echo "saved $DEST/relay-$(date +%F).db"

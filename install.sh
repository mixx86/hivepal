#!/usr/bin/env bash
# Hivepal installer: the relay (+ calls) and the daemon, ready to pair the phone.
#
#   ./install.sh            on a server with a domain: relay, LiveKit, Caddy (HTTPS) and the daemon, in Docker
#   ./install.sh --local    on this computer, for the phone on the same Wi-Fi or Tailscale: relay and
#                           LiveKit in Docker, the daemon (the `hivepal` CLI) installed with uv
#   --images                use this release's published images (ghcr.io/mixx86/hivepal-*) and CLI
#                           instead of building them here (what scripts/get.sh, the one-line install, does)
#
# It asks only what it can't guess. Answers can come from the environment instead (then add --yes):
#   HIVEPAL_DOMAIN, HIVEPAL_HOST_IP (--local), HIVEPAL_AGENT_NAME, HIVEPAL_GEMINI_API_KEY,
#   HIVEPAL_FIREBASE_DIR (a folder with firebase-service-account.json and google-services.json),
#   HIVEPAL_SMTP_URL.
# Running it again keeps the existing configuration and just updates and restarts everything.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")" && pwd)
DEPLOY="$ROOT/deploy"
MODE=server
YES=0
IMAGES=0
for a in "$@"; do
  case "$a" in
    --local) MODE=local ;;
    --yes | -y) YES=1 ;;
    --images) IMAGES=1 ;;
    -h | --help) sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $a (see --help)" >&2; exit 2 ;;
  esac
done

bold() { printf '\033[1m%s\033[0m\n' "$*"; }
step() { printf '\n\033[1;36m▸ %s\033[0m\n' "$*"; }
die() { printf '\033[1;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

# ask VAR "question" default [secret]: keeps $VAR if already set; with --yes, takes the default.
ask() {
  local var=$1 q=$2 def=${3-} secret=${4-} cur=${!1-}
  if [[ -n "$cur" ]]; then return; fi
  if [[ $YES == 1 || ! -t 0 ]]; then printf -v "$var" '%s' "$def"; return; fi
  local hint=""; [[ -n "$def" ]] && hint=" [$def]"
  if [[ -n "$secret" ]]; then read -r -s -p "$q$hint: " cur; echo; else read -r -p "$q$hint: " cur; fi
  printf -v "$var" '%s' "${cur:-$def}"
}

need() { command -v "$1" >/dev/null 2>&1 || die "$1 is needed: $2"; }
need docker "https://docs.docker.com/engine/install/"
docker compose version >/dev/null 2>&1 || die "the Docker Compose plugin is needed (docker compose)"
need openssl "install it with your package manager"

new_key() { echo "ak_$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n')"; }

VERSION=$(sed -n 's/^version = "\(.*\)"/\1/p' "$ROOT/daemon/pyproject.toml")
REGISTRY=ghcr.io/mixx86

# The address the default route leaves from, and whether it's reachable from the internet.
host_ip() { ip -4 route get 1.1.1.1 2>/dev/null | sed -n 's/.* src \([0-9.]*\).*/\1/p' | head -n1; }
is_public() {
  case "$1" in
    "" | 10.* | 127.* | 169.254.* | 192.168.* | 172.1[6-9].* | 172.2[0-9].* | 172.3[01].* | 100.6[4-9].* | 100.[7-9][0-9].* | 100.1[01][0-9].* | 100.12[0-7].*) return 1 ;;
  esac
}

# set_env VAR VALUE: writes VAR=VALUE in the env file (replacing it); set_env VAR removes it.
set_env() {
  local tmp="$ENV_FILE.tmp"
  grep -v "^$1=" "$ENV_FILE" > "$tmp" || true
  [[ $# -gt 1 ]] && echo "$1=$2" >> "$tmp"
  cat "$tmp" > "$ENV_FILE" && rm -f "$tmp"
}

if [[ $MODE == server ]]; then
  ENV_FILE="$DEPLOY/.env"
  COMPOSE=(docker compose --project-directory "$DEPLOY" -f "$DEPLOY/docker-compose.yml" --profile daemon)
  [[ -f "$DEPLOY/docker-compose.override.yml" ]] && COMPOSE+=(-f "$DEPLOY/docker-compose.override.yml")
else
  ENV_FILE="$DEPLOY/.env.local"
  COMPOSE=(docker compose --project-directory "$DEPLOY" -f "$DEPLOY/docker-compose.local.yml" --env-file "$ENV_FILE")
fi

bold "Hivepal installer ($MODE)"

if [[ -f "$ENV_FILE" ]]; then
  step "Using the existing configuration ($ENV_FILE)"
  # shellcheck disable=SC1090
  set -a; . "$ENV_FILE"; set +a
  API_KEY=${HIVEPAL_API_KEY:-${AGENTRING_API_KEY:-}}
  [[ -n "$API_KEY" ]] || die "$ENV_FILE has no HIVEPAL_API_KEY: add one (ak_...) or move the file away to start over"
else
  step "Configuration"
  if [[ $MODE == server ]]; then
    # No domain of your own: <ip>.sslip.io resolves to this server's IP, and Caddy gets it a certificate.
    ip=$(host_ip)
    sslip=""
    is_public "$ip" && sslip="${ip//./-}.sslip.io"
    ask HIVEPAL_DOMAIN "Domain of this server (its DNS must point here; ports 80, 443, 7881/tcp and 50000-50100/udp open)${sslip:+; no domain? keep this one}" "$sslip"
    [[ -n "${HIVEPAL_DOMAIN:-}" ]] || die "a domain is needed: this server's IP ($ip) isn't public, so there's no default (or use --local)"
  else
    guess=$(host_ip)
    ask HIVEPAL_HOST_IP "This computer's LAN or Tailscale IP (the phone connects to it)" "${guess:-}"
    [[ -n "${HIVEPAL_HOST_IP:-}" ]] || die "an IP is needed"
  fi
  ask HIVEPAL_AGENT_NAME "Name of the voice switchboard that answers your calls" "Centralita"
  ask HIVEPAL_GEMINI_API_KEY "Gemini API key for voice (https://aistudio.google.com/apikey; empty = no voice AI)" "" secret
  ask HIVEPAL_FIREBASE_DIR "Folder with firebase-service-account.json and google-services.json (empty = no push: see docs/firebase.md)" ""
  API_KEY=$(new_key)

  FCM_LINES="#FCM_SERVICE_ACCOUNT_FILE=/secrets/firebase-service-account.json
#FCM_GOOGLE_SERVICES_FILE=/secrets/google-services.json"
  if [[ -n "${HIVEPAL_FIREBASE_DIR:-}" ]]; then
    for f in firebase-service-account.json google-services.json; do
      [[ -f "$HIVEPAL_FIREBASE_DIR/$f" ]] || die "missing $HIVEPAL_FIREBASE_DIR/$f"
    done
    mkdir -p "$DEPLOY/secrets"
    install -m 0644 "$HIVEPAL_FIREBASE_DIR/firebase-service-account.json" "$HIVEPAL_FIREBASE_DIR/google-services.json" "$DEPLOY/secrets/"
    FCM_LINES="FCM_SERVICE_ACCOUNT_FILE=/secrets/firebase-service-account.json
FCM_GOOGLE_SERVICES_FILE=/secrets/google-services.json"
  fi
  umask 077
  {
    echo "# Written by install.sh. See .env.example for every option."
    if [[ $MODE == server ]]; then echo "HIVEPAL_DOMAIN=$HIVEPAL_DOMAIN"; else echo "HIVEPAL_HOST_IP=$HIVEPAL_HOST_IP"; fi
    echo "HIVEPAL_AGENT_NAME=$HIVEPAL_AGENT_NAME"
    echo "HIVEPAL_API_KEY=$API_KEY"
    echo "LIVEKIT_API_KEY=hivepal"
    echo "LIVEKIT_NODE_IP="
    echo "$FCM_LINES"
    echo "HIVEPAL_GEMINI_API_KEY=${HIVEPAL_GEMINI_API_KEY:-}"
    echo "HIVEPAL_SMTP_URL=${HIVEPAL_SMTP_URL:-}"
    [[ $MODE == server && -n "${HIVEPAL_GEMINI_API_KEY:-}" ]] && echo "HIVEPAL_SERVE_FLAGS=--ai"
  } > "$ENV_FILE"
  umask 022
  echo "Saved in $ENV_FILE (keep it private: it has the relay's API key)."
fi

if [[ $IMAGES == 1 ]]; then
  # This release's images; an install from a clone builds its own (and drops these lines).
  set_env HIVEPAL_RELAY_IMAGE "$REGISTRY/hivepal-relay:$VERSION"
  set_env HIVEPAL_DAEMON_IMAGE "$REGISTRY/hivepal-daemon:$VERSION"
  export HIVEPAL_RELAY_IMAGE="$REGISTRY/hivepal-relay:$VERSION" HIVEPAL_DAEMON_IMAGE="$REGISTRY/hivepal-daemon:$VERSION"
  step "Downloading Hivepal $VERSION"
  "${COMPOSE[@]}" pull --quiet
  step "Starting the relay$([[ $MODE == server ]] && echo ", calls and the daemon" || echo " and calls")"
  "${COMPOSE[@]}" up -d --no-build
else
  if grep -q '^HIVEPAL_RELAY_IMAGE=' "$ENV_FILE"; then
    set_env HIVEPAL_RELAY_IMAGE
    set_env HIVEPAL_DAEMON_IMAGE
    unset HIVEPAL_RELAY_IMAGE HIVEPAL_DAEMON_IMAGE
  fi
  step "Building and starting the relay$([[ $MODE == server ]] && echo ", calls and the daemon" || echo " and calls")"
  "${COMPOSE[@]}" up -d --build
fi

if [[ $MODE == server ]]; then
  RELAY_INSIDE=http://relay:8080
  PUBLIC=https://${HIVEPAL_DOMAIN}
  step "Configuring the daemon"
  for _ in $(seq 1 30); do
    "${COMPOSE[@]}" exec -T daemon hivepal init --relay "$RELAY_INSIDE" --api-key "$API_KEY" >/dev/null 2>&1 && break
    sleep 2
  done || true
  "${COMPOSE[@]}" exec -T daemon hivepal init --relay "$RELAY_INSIDE" --api-key "$API_KEY"
  if [[ -n "${HIVEPAL_GEMINI_API_KEY:-}" ]]; then
    printf '%s' "$HIVEPAL_GEMINI_API_KEY" | "${COMPOSE[@]}" exec -T daemon sh -c \
      'umask 077; k=$(cat); { grep -v "^GEMINI_API_KEY=" /config/.env 2>/dev/null; echo "GEMINI_API_KEY=$k"; } > /tmp/env && cat /tmp/env > /config/.env && rm /tmp/env'
  fi
  "${COMPOSE[@]}" restart daemon >/dev/null
  HIVEPAL=("${COMPOSE[@]}" exec daemon hivepal)
else
  PUBLIC=http://${HIVEPAL_HOST_IP}:8080
  step "Installing the hivepal CLI (daemon)"
  need uv "https://docs.astral.sh/uv/getting-started/installation/"
  uv tool uninstall agentring >/dev/null 2>&1 || true
  if [[ $IMAGES == 1 ]]; then
    uv tool install --force "hivepal[voice] @ https://github.com/mixx86/hivepal/releases/download/v$VERSION/hivepal-$VERSION-py3-none-any.whl" >/dev/null
  else
    uv tool install --force "$ROOT/daemon[voice]" >/dev/null
  fi
  BIN=$(uv tool dir --bin)/hivepal
  for _ in $(seq 1 30); do curl -fsS "$PUBLIC/healthz" >/dev/null 2>&1 && break; sleep 2; done
  "$BIN" init --relay "$PUBLIC" --api-key "$API_KEY"
  if [[ -n "${HIVEPAL_GEMINI_API_KEY:-}" ]]; then
    home=${HIVEPAL_HOME:-$HOME/.agentring}
    umask 077
    { grep -v '^GEMINI_API_KEY=' "$home/.env" 2>/dev/null || true; echo "GEMINI_API_KEY=$HIVEPAL_GEMINI_API_KEY"; } > "$home/.env.new"
    mv "$home/.env.new" "$home/.env"
    umask 022
  fi
  HIVEPAL=("$BIN")
  # Shorter instructions when the CLI is already on the PATH.
  [[ "$(command -v hivepal 2>/dev/null)" == "$BIN" ]] && { BIN=hivepal; HIVEPAL=(hivepal); }
fi

step "Checking the relay at $PUBLIC"
ok=0
for _ in $(seq 1 60); do curl -fsS "$PUBLIC/healthz" >/dev/null 2>&1 && { ok=1; break; }; sleep 3; done
[[ $ok == 1 ]] && echo "The relay answers." ||
  echo "The relay doesn't answer at $PUBLIC yet (DNS or the HTTPS certificate may take a few minutes)."

bold "
Hivepal is running.

  Relay:    $PUBLIC
  Panel:    $PUBLIC/admin   (status, pair a phone, add agents, test; log in with the key below)
  API key:  $API_KEY   (also in $ENV_FILE)

Next:
  1. The app: $([[ $IMAGES == 1 ]] && echo "https://github.com/mixx86/hivepal/releases/download/v$VERSION/hivepal-$VERSION.apk" || echo "./scripts/build-app.sh --install   (or install a release APK)")
  2. Pair the phone: ${HIVEPAL[*]} pair   and scan the QR with the app
  3. Try it: ${HIVEPAL[*]} msg \"hello\"
  4. An agent with its own chat and key: ${HIVEPAL[*]} bots add nubi Nubi
$([[ $MODE == local ]] && echo "  5. Answer calls with the voice AI: $BIN serve --ai   (keep it running)")
"
if [[ $YES == 0 && -t 0 ]]; then
  read -r -p "Pair the phone now? [Y/n] " p
  [[ "${p:-y}" =~ ^[Yy] ]] && "${HIVEPAL[@]}" pair
fi

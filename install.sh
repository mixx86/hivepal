#!/usr/bin/env bash
# Hivepal installer: the relay (+ calls) and the daemon, ready to pair the phone.
#
#   ./install.sh            on a server with a domain: relay, LiveKit, Caddy (HTTPS) and the daemon, in Docker
#   ./install.sh --local    on this computer, for the phone on the same Wi-Fi or Tailscale: relay and
#                           LiveKit in Docker, the daemon (the `hivepal` CLI) installed with uv
#   --images                use this release's published images (ghcr.io/mixx86/hivepal-*) and CLI
#                           instead of building them here (what scripts/get.sh, the one-line install, does)
#   --email                 set up (or change) the mail account for agents' wake-up emails, then restart
#   --delivery              set up (or change) how agents hear from you (email, Markdown mailboxes in git)
#                           and whether bots remember their conversations (Markdown, SQLite)
#
# It asks only what it can't guess. Answers can come from the environment instead (then add --yes):
#   HIVEPAL_DOMAIN, HIVEPAL_HOST_IP (--local), HIVEPAL_AGENT_NAME,
#   HIVEPAL_VOICE_PROVIDER (gemini, openai, grok, phonic, openrouter, local or none; default gemini),
#   HIVEPAL_VOICE_API_KEY (that provider's key; for gemini, HIVEPAL_GEMINI_API_KEY), HIVEPAL_VOICE_URL
#   (local), HIVEPAL_GEMINI_API_KEY (voice-note transcripts with any provider),
#   HIVEPAL_FIREBASE_DIR (a folder with firebase-service-account.json and google-services.json),
#   HIVEPAL_WAKE_EMAIL (the account wake-up emails are sent from) with HIVEPAL_WAKE_EMAIL_PASSWORD and,
#   for an unknown provider, HIVEPAL_SMTP_SERVER (host:port), or HIVEPAL_SMTP_URL directly;
#   HIVEPAL_DELIVERY (email, mailbox, both or none), HIVEPAL_MAILBOX_REPO (empty = kept in the relay),
#   HIVEPAL_MEMORY (off, markdown, sqlite or both).
# Running it again keeps the existing configuration and just updates and restarts everything.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")" && pwd)
DEPLOY="$ROOT/deploy"
MODE=server
YES=0
IMAGES=0
EMAIL=0
DELIVERY=0
for a in "$@"; do
  case "$a" in
    --local) MODE=local ;;
    --email) EMAIL=1 ;;
    --delivery) DELIVERY=1 ;;
    --yes | -y) YES=1 ;;
    --images) IMAGES=1 ;;
    -h | --help) sed -n '2,26p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
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

# The daemon's variable for each voice provider's key (the local server's is optional).
voice_key_var() {
  case "$1" in
    gemini) echo GEMINI_API_KEY ;; openai) echo OPENAI_API_KEY ;; grok) echo XAI_API_KEY ;;
    phonic) echo PHONIC_API_KEY ;; openrouter) echo OPENROUTER_API_KEY ;; local) echo HIVEPAL_VOICE_KEY ;;
  esac
}

# The voice lines for the daemon's .env (secrets: printed for a pipe, never passed as arguments).
voice_env() {
  local p=${HIVEPAL_VOICE_PROVIDER:-gemini} key=${HIVEPAL_VOICE_API_KEY:-}
  [[ $p == none ]] && p=""
  [[ -n "${HIVEPAL_GEMINI_API_KEY:-}" ]] && echo "GEMINI_API_KEY=$HIVEPAL_GEMINI_API_KEY"
  [[ -z "$p" ]] && return 0
  if [[ $p == gemini ]]; then key=${HIVEPAL_GEMINI_API_KEY:-}; else [[ -n "$key" ]] && echo "$(voice_key_var "$p")=$key"; fi
  [[ -n "$key" || $p == local ]] || return 0
  echo "HIVEPAL_VOICE_PROVIDER=$p"
  [[ $p == local && -n "${HIVEPAL_VOICE_URL:-}" ]] && echo "HIVEPAL_VOICE_URL=$HIVEPAL_VOICE_URL"
  return 0
}

# Whether a voice AI is set up (so `serve --ai` can answer calls).
voice_ready() { voice_env | grep -q '^HIVEPAL_VOICE_PROVIDER='; }

# Merges KEY=value lines from stdin into the env file $1 (replacing those keys, keeping the rest).
merge_env() {
  local f=$1 new; new=$(cat)
  [[ -z "$new" ]] && return 0
  local pat; pat=$(printf '%s\n' "$new" | sed 's/=.*//; s/^/^/; s/$/=/')
  mkdir -p "$(dirname "$f")"
  umask 077
  { grep -v -f <(printf '%s\n' "$pat") "$f" 2>/dev/null || true; printf '%s\n' "$new"; } > "$f.new"
  mv "$f.new" "$f"
  umask 022
}

# Percent-encodes a URL part (byte by byte, so any character in a password works).
urlencode() {
  local LC_ALL=C s=$1 out="" c h i
  for ((i = 0; i < ${#s}; i++)); do
    c=${s:i:1}
    case "$c" in [A-Za-z0-9._~-]) out+=$c ;; *) printf -v h '%%%02X' "'$c"; out+=$h ;; esac
  done
  printf '%s' "$out"
}

# The SMTP server of well-known providers, as smtps://host:465 or smtp://host:587 (STARTTLS).
smtp_for() {
  case "${1##*@}" in
    gmail.com | googlemail.com) echo smtps://smtp.gmail.com:465 ;;
    icloud.com | me.com | mac.com) echo smtp://smtp.mail.me.com:587 ;;
    yahoo.com | yahoo.es | ymail.com) echo smtps://smtp.mail.yahoo.com:465 ;;
    fastmail.com | fastmail.fm) echo smtps://smtp.fastmail.com:465 ;;
    gmx.com | gmx.net | gmx.es | gmx.de) echo smtps://mail.gmx.net:465 ;;
    zoho.com | zohomail.com) echo smtps://smtp.zoho.com:465 ;;
  esac
}

# Asks for the account wake-up emails are sent from and sets HIVEPAL_SMTP_URL (empty = none).
ask_email() {
  [[ -n "${HIVEPAL_SMTP_URL:-}" ]] && return 0
  ask HIVEPAL_WAKE_EMAIL "Wake-up emails, optional: an email account the relay sends them from, to start agents that run on an email (a spare Gmail works; empty = none)" ""
  [[ -n "${HIVEPAL_WAKE_EMAIL:-}" ]] || return 0
  [[ $HIVEPAL_WAKE_EMAIL == *@*.* ]] || die "not an email address: $HIVEPAL_WAKE_EMAIL"
  local server; server=$(smtp_for "$HIVEPAL_WAKE_EMAIL")
  if [[ -z "$server" ]]; then
    ask HIVEPAL_SMTP_SERVER "Its SMTP server, host:port (465 = TLS, 587 = STARTTLS)" "smtp.${HIVEPAL_WAKE_EMAIL##*@}:465"
    local hp=$HIVEPAL_SMTP_SERVER
    [[ $hp == *:* ]] || hp="$hp:465"
    if [[ ${hp##*:} == 465 ]]; then server="smtps://$hp"; else server="smtp://$hp"; fi
  fi
  local hint="its password"
  [[ $server == *gmail* ]] && hint="an app password for it (https://myaccount.google.com/apppasswords; needs 2-step verification)"
  [[ $server == *me.com* ]] && hint="an app-specific password (https://account.apple.com)"
  [[ $server == *yahoo* ]] && hint="an app password (Yahoo account security)"
  ask HIVEPAL_WAKE_EMAIL_PASSWORD "Password for $HIVEPAL_WAKE_EMAIL: $hint" "" secret
  [[ -n "${HIVEPAL_WAKE_EMAIL_PASSWORD:-}" ]] || die "a password is needed to send email (or leave the account empty)"
  HIVEPAL_SMTP_URL="${server%%://*}://$(urlencode "$HIVEPAL_WAKE_EMAIL"):$(urlencode "$HIVEPAL_WAKE_EMAIL_PASSWORD")@${server#*://}"
}

# ask_choice VAR "question" default "a b c": asks until the answer is one of the words.
ask_choice() {
  local var=$1 q=$2 def=$3 words=" $4 "
  while :; do
    ask "$var" "$q" "$def"
    [[ "$words" == *" ${!var} "* ]] && return 0
    [[ $YES == 1 || ! -t 0 ]] && die "$var: '${!var}' isn't one of:$words"
    echo "One of:$words"; unset "$var"
  done
}

# How agents hear from you and whether bots remember (issue #41). Applied once the relay runs.
ask_delivery() {
  DELIVERY_ASKED=1
  ask_choice HIVEPAL_DELIVERY "How should agents hear from you, besides the CLI and the API? email (a wake-up email starts them), mailbox (Markdown files in git they read), both, or none" "none" "email mailbox both none"
  if [[ $HIVEPAL_DELIVERY == email || $HIVEPAL_DELIVERY == both ]]; then ask_email; fi
  if [[ $HIVEPAL_DELIVERY == mailbox || $HIVEPAL_DELIVERY == both ]]; then
    ask HIVEPAL_MAILBOX_REPO "Mailboxes: an external git repository the relay pushes to (git@github.com:you/notes.git), or empty to keep them in the relay (agents clone them from it)" ""
  fi
  ask_choice HIVEPAL_MEMORY "Should bots remember their Hivepal conversations? off (each run starts from their own instructions), markdown (each day's conversation in their mailbox), sqlite (they search it), or both" "off" "off markdown sqlite both"
}

# The host's time zone, for dates in mailboxes and memory.
host_tz() {
  local tz
  tz=$(timedatectl show -p Timezone --value 2>/dev/null || true)
  [[ -z "$tz" && -L /etc/localtime ]] && tz=$(readlink /etc/localtime | sed 's#.*/zoneinfo/##')
  [[ -z "$tz" && -f /etc/timezone ]] && tz=$(cat /etc/timezone)
  echo "${tz:-UTC}"
}

# set_env VAR VALUE: writes VAR=VALUE in the env file (replacing it); set_env VAR removes it.
set_env() {
  local tmp="$ENV_FILE.tmp" um
  um=$(umask); umask 077 # it holds secrets, even for a moment
  grep -v "^$1=" "$ENV_FILE" > "$tmp" || true
  [[ $# -gt 1 ]] && echo "$1=$2" >> "$tmp"
  cat "$tmp" > "$ENV_FILE" && rm -f "$tmp"
  umask "$um"
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
  if [[ $EMAIL == 1 ]]; then
    step "Wake-up emails"
    unset HIVEPAL_SMTP_URL
    ask_email
    if [[ -n "${HIVEPAL_SMTP_URL:-}" ]]; then set_env HIVEPAL_SMTP_URL "$HIVEPAL_SMTP_URL"; echo "Saved; the relay restarts with it."
    else set_env HIVEPAL_SMTP_URL ""; echo "No account: wake-up emails are off."; fi
  fi
  if [[ $DELIVERY == 1 ]]; then
    step "How agents hear from you"
    unset HIVEPAL_DELIVERY HIVEPAL_MAILBOX_REPO HIVEPAL_MEMORY
    [[ $EMAIL == 1 ]] || unset HIVEPAL_SMTP_URL
    ask_delivery
    [[ -n "${HIVEPAL_SMTP_URL:-}" ]] && set_env HIVEPAL_SMTP_URL "$HIVEPAL_SMTP_URL"
  fi
  # Older configurations: the host's time zone for the relay's dates.
  grep -q '^TZ=' "$ENV_FILE" || { TZ=$(host_tz); set_env TZ "$TZ"; export TZ; }
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
  # The voice AI: Gemini by default (free tier, and the relay uses it for voice-note transcripts).
  while :; do
    ask HIVEPAL_VOICE_PROVIDER "Voice AI for calls: gemini (free tier, recommended), openai, grok, phonic, openrouter, local (your own server) or none" "gemini"
    case "$HIVEPAL_VOICE_PROVIDER" in gemini | openai | grok | phonic | openrouter | local | none) break ;; esac
    [[ $YES == 1 || ! -t 0 ]] && die "unknown voice provider: $HIVEPAL_VOICE_PROVIDER"
    echo "Pick one of the list."; unset HIVEPAL_VOICE_PROVIDER
  done
  case "$HIVEPAL_VOICE_PROVIDER" in
    gemini) ask HIVEPAL_GEMINI_API_KEY "Gemini API key (free at https://aistudio.google.com/apikey; empty = no voice AI)" "" secret ;;
    openai) ask HIVEPAL_VOICE_API_KEY "OpenAI API key (https://platform.openai.com/api-keys)" "" secret ;;
    grok) ask HIVEPAL_VOICE_API_KEY "xAI API key (https://console.x.ai)" "" secret ;;
    phonic) ask HIVEPAL_VOICE_API_KEY "Phonic API key (https://phonic.co)" "" secret ;;
    openrouter) ask HIVEPAL_VOICE_API_KEY "OpenRouter API key (https://openrouter.ai/keys)" "" secret ;;
    local)
      ask HIVEPAL_VOICE_URL "Address of your OpenAI-Realtime-compatible server$([[ $MODE == server ]] && echo " (as the daemon's container sees it)")" "ws://127.0.0.1:8765/v1/realtime"
      ask HIVEPAL_VOICE_API_KEY "Its key, if it asks for one" "" secret ;;
  esac
  if [[ $HIVEPAL_VOICE_PROVIDER != gemini ]]; then
    ask HIVEPAL_GEMINI_API_KEY "Gemini API key for voice-note transcripts (optional; free at https://aistudio.google.com/apikey)" "" secret
  fi
  ask_delivery
  ask HIVEPAL_FIREBASE_DIR "Folder with firebase-service-account.json and google-services.json (optional; empty = phones use UnifiedPush through ntfy)" ""
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
    echo "HIVEPAL_VOICE_PROVIDER=$HIVEPAL_VOICE_PROVIDER"
    [[ -n "${HIVEPAL_VOICE_API_KEY:-}" ]] && echo "HIVEPAL_VOICE_API_KEY=$HIVEPAL_VOICE_API_KEY"
    [[ -n "${HIVEPAL_VOICE_URL:-}" ]] && echo "HIVEPAL_VOICE_URL=$HIVEPAL_VOICE_URL"
    echo "HIVEPAL_SMTP_URL=${HIVEPAL_SMTP_URL:-}"
    echo "TZ=$(host_tz)"
    [[ $MODE == server ]] && voice_ready && echo "HIVEPAL_SERVE_FLAGS=--ai"
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
  # The voice AI's provider and keys, into the daemon's .env (through stdin: no secret in argv).
  if [[ -n "$(voice_env)" ]]; then
    voice_env | "${COMPOSE[@]}" exec -T daemon sh -c \
      'umask 077; cat > /tmp/new; sed "s/=.*//; s/^/^/; s/\$/=/" /tmp/new > /tmp/pat
       { grep -v -f /tmp/pat /config/.env 2>/dev/null; cat /tmp/new; } > /tmp/env && cat /tmp/env > /config/.env; rm -f /tmp/env /tmp/new /tmp/pat'
  fi
  "${COMPOSE[@]}" restart daemon >/dev/null
  HIVEPAL=("${COMPOSE[@]}" exec daemon hivepal)
  HIVEPAL_RUN=("${COMPOSE[@]}" exec -T daemon hivepal)
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
  voice_env | merge_env "${HIVEPAL_HOME:-$HOME/.agentring}/.env"
  HIVEPAL=("$BIN")
  HIVEPAL_RUN=("$BIN")
  # Shorter instructions when the CLI is already on the PATH.
  [[ "$(command -v hivepal 2>/dev/null)" == "$BIN" ]] && { BIN=hivepal; HIVEPAL=(hivepal); }
fi

step "Checking the relay at $PUBLIC"
ok=0
for _ in $(seq 1 60); do curl -fsS "$PUBLIC/healthz" >/dev/null 2>&1 && { ok=1; break; }; sleep 3; done
[[ $ok == 1 ]] && echo "The relay answers." ||
  echo "The relay doesn't answer at $PUBLIC yet (DNS or the HTTPS certificate may take a few minutes)."

# The delivery choices live in the relay (the app and the web panel change them too).
# Nothing to set when it's email only (or nothing) and no memory: the defaults.
if [[ ${HIVEPAL_DELIVERY:-none} == none || ${HIVEPAL_DELIVERY:-none} == email ]] && [[ ${HIVEPAL_MEMORY:-off} == off ]]; then DELIVERY_ASKED=0; fi
if [[ ${DELIVERY_ASKED:-0} == 1 && $ok == 1 ]]; then
  step "How agents hear from you"
  for _ in $(seq 1 15); do "${HIVEPAL_RUN[@]}" delivery >/dev/null 2>&1 && break; sleep 2; done
  mailboxes=off
  [[ $HIVEPAL_DELIVERY == mailbox || $HIVEPAL_DELIVERY == both ]] && mailboxes=on
  # Markdown memory lives in the mailboxes.
  [[ $HIVEPAL_MEMORY == markdown || $HIVEPAL_MEMORY == both ]] && mailboxes=on
  "${HIVEPAL_RUN[@]}" delivery mailboxes "$mailboxes"
  "${HIVEPAL_RUN[@]}" delivery memory "$HIVEPAL_MEMORY"
  if [[ $mailboxes == on && -n "${HIVEPAL_MAILBOX_REPO:-}" ]]; then
    if "${HIVEPAL_RUN[@]}" delivery repo "$HIVEPAL_MAILBOX_REPO"; then
      if [[ $YES == 0 && -t 0 ]]; then
        read -r -p "Add that deploy key to the repository (with write access), then press Enter to check it (or type skip): " k
        [[ "$k" == skip ]] || "${HIVEPAL_RUN[@]}" delivery repo --test || echo "Check it later: ${HIVEPAL[*]} delivery repo --test"
      fi
    fi
  fi
fi

bold "
Hivepal is running.

  Relay:    $PUBLIC
  Panel:    $PUBLIC/admin   (status, pair a phone, add agents, test; log in with the key below)
  API key:  $API_KEY   (also in $ENV_FILE)

Next:
  1. App and pairing: ${HIVEPAL[*]} pair   and scan the QR with the phone's camera: the page offers the app
     (https://github.com/mixx86/hivepal/releases/latest) and then opens it to pair$([[ $IMAGES == 1 ]] || echo "
     (or build it yourself: ./scripts/build-app.sh --install, phone on USB)")
  2. Then follow the app: permissions, push, and how agents hear from you
  3. Try it: ${HIVEPAL[*]} msg \"hello\"
  4. An agent with its own chat and key: ${HIVEPAL[*]} bots add nubi Nubi
$([[ -n "${HIVEPAL_SMTP_URL:-}" ]] && echo "     Wake it by email: give it an address in the panel (Agents) and Send a test")
     How agents hear from you and remember: ${HIVEPAL[*]} delivery   (change it: ./install.sh$([[ $MODE == local ]] && echo " --local") --delivery, the app or the panel)
$([[ $MODE == local ]] && voice_ready && echo "  5. Answer calls with the voice AI ($HIVEPAL_VOICE_PROVIDER): $BIN serve --ai   (keep it running)")
"
if [[ $YES == 0 && -t 0 ]]; then
  read -r -p "Pair the phone now? [Y/n] " p
  [[ "${p:-y}" =~ ^[Yy] ]] && "${HIVEPAL[@]}" pair
fi

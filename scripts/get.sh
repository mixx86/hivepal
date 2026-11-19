#!/bin/sh
# Hivepal in one line, without cloning the repository:
#
#   curl -fsSL https://raw.githubusercontent.com/mixx86/hivepal/main/scripts/get.sh | sh
#   curl -fsSL https://raw.githubusercontent.com/mixx86/hivepal/main/scripts/get.sh | sh -s -- --local
#
# Downloads the latest release (not main) into ~/hivepal and runs its installer with the published
# images (ghcr.io/mixx86/hivepal-*), so nothing is built on the machine. Running it again updates to
# the latest release and keeps the configuration (deploy/.env, deploy/.env.local, deploy/secrets/).
#
#   HIVEPAL_DIR=/opt/hivepal    where to put it (default ~/hivepal)
#   HIVEPAL_VERSION=0.6.0       a given release instead of the latest
# Any other arguments go to install.sh (--local, --yes; see ./install.sh --help).
set -eu

REPO=mixx86/hivepal
DIR=${HIVEPAL_DIR:-$HOME/hivepal}

say() { printf '\033[1;36m▸ %s\033[0m\n' "$*"; }
die() { printf '\033[1;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }
for c in curl tar bash; do command -v "$c" >/dev/null 2>&1 || die "$c is needed"; done

if [ -n "${HIVEPAL_VERSION:-}" ]; then
  tag="v${HIVEPAL_VERSION#v}"
else
  # The latest release, from the redirect of /releases/latest (no API token or rate limit).
  tag=$(curl -fsSLI -o /dev/null -w '%{url_effective}' "https://github.com/$REPO/releases/latest" | sed 's#.*/tag/##')
  case "$tag" in v[0-9]*) ;; *) die "couldn't find the latest release of $REPO" ;; esac
fi

if [ -d "$DIR/.git" ]; then
  die "$DIR is a git clone: update it with git and run ./install.sh there (or set HIVEPAL_DIR)"
fi

say "Downloading Hivepal $tag into $DIR"
mkdir -p "$DIR"
# Over an earlier download: files from the release are replaced; the configuration isn't in it.
curl -fsSL "https://github.com/$REPO/archive/refs/tags/$tag.tar.gz" | tar -xz -C "$DIR" --strip-components=1
grep -q -- "--images" "$DIR/install.sh" || die "$tag is too old for the one-line install: use HIVEPAL_VERSION=0.6.0 or newer"

# The installer asks questions: give it the terminal even though this script came through a pipe.
if [ -t 0 ]; then
  exec bash "$DIR/install.sh" --images "$@"
elif (exec </dev/tty) 2>/dev/null; then
  exec bash "$DIR/install.sh" --images "$@" </dev/tty
else
  exec bash "$DIR/install.sh" --images --yes "$@"
fi

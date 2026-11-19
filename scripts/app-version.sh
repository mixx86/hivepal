#!/usr/bin/env bash
# Prints the Android versionName and versionCode for a build that is not a tagged release
# (android-preview, scripts/build-app.sh, local builds), as "NAME CODE":
#
#   0.4.0+2.ga6e21e1 100000216
#
# NAME is the last release plus the commits since it (git describe, in SemVer build-metadata form).
# CODE is 100000000 + the number of commits on HEAD: it grows with every commit on main, so each
# build installs over the previous one, and stays above every release code (release.yml derives
# MMMmmpp99 from the tag, at most 99999999). Release and preview APKs are signed with different keys,
# so they never install over each other anyway; what matters is that each channel only goes up.
#
# Needs the full history and tags (CI: actions/checkout with fetch-depth: 0).
set -euo pipefail
cd "$(dirname "$0")/.."

if ! git rev-parse --git-dir >/dev/null 2>&1; then
  # A source tarball: no history to count. Lower than any git build.
  version=$(sed -n 's/^version = "\(.*\)"/\1/p' daemon/pyproject.toml)
  echo "$version+nogit 100000000"
  exit 0
fi

count=$(git rev-list --count HEAD)
if desc=$(git describe --tags --match 'v[0-9]*' --long --abbrev=7 2>/dev/null); then
  # v0.4.0-2-ga6e21e1 -> 0.4.0+2.ga6e21e1
  [[ $desc =~ ^v(.+)-([0-9]+)-(g[0-9a-f]+)$ ]]
  name="${BASH_REMATCH[1]}+${BASH_REMATCH[2]}.${BASH_REMATCH[3]}"
else
  name="0.0.0+$count.g$(git rev-parse --short=7 HEAD)"
fi
git diff --quiet HEAD 2>/dev/null || name="$name.dirty"
echo "$name $((100000000 + count))"

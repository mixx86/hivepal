#!/usr/bin/env bash
# Cuts a Hivepal release from main:
#
#   scripts/release.sh 0.4.1          # or 0.5.0, 0.5.0rc1 (pre-releases: a1, b1, rc1)
#
# 1. Checks: on an up-to-date, clean main; CHANGELOG.md has entries under [Unreleased]; no such tag.
# 2. On a branch release/vX.Y.Z: writes the version everywhere it is kept (daemon/pyproject.toml +
#    uv.lock, relay/package.json + package-lock.json, the relay's VERSION constant) and turns
#    [Unreleased] into [X.Y.Z] - today. Opens a pull request that merges itself once CI is green.
# 3. Waits for the merge, tags the merged commit vX.Y.Z and pushes the tag, which starts
#    .github/workflows/release.yml (signed APK, Python packages, images, GitHub release).
#
# Needs git, gh (logged in, allowed to push tags: the v* tags are restricted to admins), uv, npm.
# --dry-run stops after preparing the branch locally (nothing is pushed).
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
die() { printf '\033[1;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }
say() { printf '\033[1;36m→ %s\033[0m\n' "$*"; }

DRY=0
args=()
for a in "$@"; do
  case "$a" in
    --dry-run) DRY=1 ;;
    -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
    *) args+=("$a") ;;
  esac
done
[[ ${#args[@]} -eq 1 ]] || die "usage: scripts/release.sh X.Y.Z [--dry-run]"
VERSION=${args[0]#v}
TAG="v$VERSION"

# Same shapes release.yml accepts: X.Y.Z or X.Y.Z{a,b,rc}N (PEP 440, fine for Android).
[[ $VERSION =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)((a|b|rc)([0-9]+))?$ ]] || die "$VERSION is not X.Y.Z or X.Y.Z{a,b,rc}N"
core="${BASH_REMATCH[1]}.${BASH_REMATCH[2]}.${BASH_REMATCH[3]}"
kind=${BASH_REMATCH[5]} n=${BASH_REMATCH[6]}
(( BASH_REMATCH[2] <= 99 && BASH_REMATCH[3] <= 99 && ${n:-0} <= 29 )) || die "minor/patch must be <= 99 and the pre-release number <= 29"
# npm wants SemVer: 0.5.0rc1 -> 0.5.0-rc.1
NPM_VERSION=$core
[[ -n $kind ]] && NPM_VERSION="$core-$kind.$n"

for c in git gh uv npm; do command -v "$c" >/dev/null || die "$c is needed"; done
# Always the repository origin points at (the fork), never the upstream remote gh might pick.
REPO=$(git remote get-url origin | sed -E 's#^(git@github\.com:|https://github\.com/)##; s#\.git$##')
[[ $REPO == */* ]] || die "origin is not a GitHub repository: $(git remote get-url origin)"

say "Checking main"
[[ $(git branch --show-current) == main ]] || die "switch to main first"
[[ -z $(git status --porcelain) ]] || die "the working tree has changes; commit or stash them"
git fetch -q origin main --tags
[[ $(git rev-parse HEAD) == $(git rev-parse origin/main) ]] || die "main is not origin/main: git pull --ff-only"
git rev-parse -q --verify "refs/tags/$TAG" >/dev/null && die "tag $TAG already exists"
git ls-remote --exit-code --tags origin "refs/tags/$TAG" >/dev/null 2>&1 && die "tag $TAG already exists on origin"

notes=$(awk '/^## \[Unreleased\]/{on=1; next} /^## \[/{on=0} on' CHANGELOG.md | sed '/^[[:space:]]*$/d')
[[ -n $notes ]] || die "CHANGELOG.md has nothing under [Unreleased]: say what this release changes first"

current=$(sed -n 's/^version = "\(.*\)"/\1/p' daemon/pyproject.toml)
say "Releasing $VERSION (main is at $current)"

branch="release/$TAG"
git switch -q -c "$branch"

sed -i.bak "s/^version = \".*\"/version = \"$VERSION\"/" daemon/pyproject.toml && rm daemon/pyproject.toml.bak
(cd daemon && uv lock -q)
# The lockfile may only change hivepal's own version, never a dependency.
changed=$(git diff --numstat daemon/uv.lock | awk '{print $1 + $2}')
[[ ${changed:-0} -le 2 ]] || die "uv lock changed more than hivepal's version in daemon/uv.lock; look at it by hand"

(cd relay && npm version "$NPM_VERSION" --no-git-tag-version --allow-same-version >/dev/null)
sed -i.bak "s/^export const VERSION = \".*\";/export const VERSION = \"$VERSION\";/" relay/src/routes/system.ts && rm relay/src/routes/system.ts.bak
grep -q "^export const VERSION = \"$VERSION\";" relay/src/routes/system.ts || die "couldn't set VERSION in relay/src/routes/system.ts"

today=$(date -u +%F)
prev=$(git describe --tags --match 'v[0-9]*' --abbrev=0 2>/dev/null || true)
awk -v v="$VERSION" -v d="$today" -v tag="$TAG" -v prev="$prev" -v repo="$REPO" '
  /^## \[Unreleased\]/ { print; print ""; print "## [" v "] - " d; next }
  /^\[Unreleased\]:/ {
    print "[Unreleased]: https://github.com/" repo "/compare/" tag "...HEAD"
    if (prev != "") print "[" v "]: https://github.com/" repo "/compare/" prev "..." tag
    else print "[" v "]: https://github.com/" repo "/releases/tag/" tag
    next
  }
  { print }
' CHANGELOG.md > CHANGELOG.md.new && mv CHANGELOG.md.new CHANGELOG.md

git add daemon/pyproject.toml daemon/uv.lock relay/package.json relay/package-lock.json relay/src/routes/system.ts CHANGELOG.md
git commit -q -m "release: $VERSION"
git --no-pager show --stat HEAD

if (( DRY )); then
  say "Dry run: branch $branch is ready locally; nothing was pushed."
  say "Undo with: git switch main && git branch -D $branch"
  exit 0
fi

say "Opening the release pull request"
git push -q -u origin "$branch"
body=$(printf 'Release %s. Merges itself when CI is green; then `scripts/release.sh` tags it.\n\n%s\n' "$VERSION" "$notes")
url=$(gh pr create -R "$REPO" --base main --head "$branch" --title "release: $VERSION" --body "$body")
echo "$url"
gh pr merge -R "$REPO" "$url" --squash --auto --delete-branch

say "Waiting for CI and the merge (Ctrl-C is safe: tag it later with the commands below)"
deadline=$((SECONDS + 3600))
while :; do
  state=$(gh pr view -R "$REPO" "$url" --json state -q .state)
  [[ $state == MERGED ]] && break
  [[ $state == CLOSED ]] && die "the pull request was closed without merging"
  if gh pr checks -R "$REPO" "$url" 2>/dev/null | grep -qE $'\tfail\t'; then
    die "a check failed: $url (fix it on $branch; the PR still merges itself when green, then tag it as below)"
  fi
  (( SECONDS < deadline )) || die "still not merged after an hour: $url"
  sleep 30
done

git switch -q main
git pull -q --ff-only origin main
git branch -q -D "$branch" 2>/dev/null || true
[[ $(sed -n 's/^version = "\(.*\)"/\1/p' daemon/pyproject.toml) == "$VERSION" ]] || die "main doesn't have version $VERSION after the merge"

say "Tagging $TAG"
git tag -a "$TAG" -m "hivepal $VERSION"
git push -q origin "$TAG"
say "Done. The release builds in Actions: https://github.com/$REPO/actions/workflows/release.yml"
cat <<EOF
If the run fails before the pypi job: fix it on a pull request, then
  git push --delete origin $TAG && git tag -d $TAG
and tag the fixed main again:  git tag -a $TAG -m "hivepal $VERSION" && git push origin $TAG
EOF

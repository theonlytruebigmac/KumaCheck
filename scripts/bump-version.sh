#!/usr/bin/env bash
#
# Bump KumaCheck's versionName + versionCode in app/build.gradle.kts.
# Optionally commit, tag, and push to trigger the release workflow.
#
# Usage:
#   ./scripts/bump-version.sh <bump>
#   ./scripts/bump-version.sh <bump> --commit
#   ./scripts/bump-version.sh <bump> --tag
#   ./scripts/bump-version.sh <bump> --push
#
# <bump> is one of:
#   patch        0.5.0 → 0.5.1
#   minor        0.5.0 → 0.6.0
#   major        0.5.0 → 1.0.0
#   <X.Y.Z>      explicit version (e.g. 0.6.2)
#
# Flags chain implicitly: --tag implies --commit; --push implies --tag.
# Default with no flag just edits the file so you can review the diff before
# anything else happens.
#
# Pass --allow-dirty to commit/tag with other unrelated changes in the working
# tree. By default the script refuses, since a release commit should only
# contain the version bump.

set -euo pipefail

cd "$(dirname "$0")/.."

BUILD_FILE="app/build.gradle.kts"
TAG_PREFIX="v"

bump_arg="${1:-}"
shift || true

do_commit=false
do_tag=false
do_push=false
allow_dirty=false

for arg in "$@"; do
    case "$arg" in
        --commit) do_commit=true ;;
        --tag) do_commit=true; do_tag=true ;;
        --push) do_commit=true; do_tag=true; do_push=true ;;
        --allow-dirty) allow_dirty=true ;;
        *) echo "Unknown flag: $arg" >&2; exit 1 ;;
    esac
done

if [[ -z "$bump_arg" ]]; then
    cat <<EOF >&2
Usage: $0 <patch|minor|major|X.Y.Z> [--commit|--tag|--push] [--allow-dirty]

Examples:
  $0 patch              # 0.5.0 → 0.5.1, edits file only
  $0 minor --commit     # 0.5.0 → 0.6.0, edits + commits
  $0 0.6.2 --tag        # explicit version, edits + commits + tags
  $0 patch --push       # full release flow: edits + commits + tags + pushes
EOF
    exit 1
fi

# --- Read current version --------------------------------------------------

current_name="$(grep -E '^[[:space:]]*versionName[[:space:]]*=' "$BUILD_FILE" | sed -E 's/.*"([^"]+)".*/\1/')"
current_code="$(grep -E '^[[:space:]]*versionCode[[:space:]]*=' "$BUILD_FILE" | sed -E 's/.*=[[:space:]]*([0-9]+).*/\1/')"

if [[ ! "$current_name" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "Could not parse current versionName from $BUILD_FILE (got '$current_name')." >&2
    exit 1
fi
if [[ ! "$current_code" =~ ^[0-9]+$ ]]; then
    echo "Could not parse current versionCode from $BUILD_FILE (got '$current_code')." >&2
    exit 1
fi

IFS=. read -r cur_major cur_minor cur_patch <<<"$current_name"

# --- Compute next version --------------------------------------------------

case "$bump_arg" in
    patch) new_name="$cur_major.$cur_minor.$((cur_patch + 1))" ;;
    minor) new_name="$cur_major.$((cur_minor + 1)).0" ;;
    major) new_name="$((cur_major + 1)).0.0" ;;
    *)
        if [[ ! "$bump_arg" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
            echo "Invalid bump '$bump_arg' — expected patch|minor|major or X.Y.Z." >&2
            exit 1
        fi
        new_name="$bump_arg"
        ;;
esac

new_code=$((current_code + 1))
new_tag="${TAG_PREFIX}${new_name}"

if [[ "$new_name" == "$current_name" ]]; then
    echo "Refusing to bump: target version $new_name matches current." >&2
    exit 1
fi

# Strict monotonicity for semver too — refuse to go backwards. Lets you catch
# typos like `0.4.9` when current is `0.5.0`.
ver_to_int() {
    IFS=. read -r a b c <<<"$1"
    printf '%d%03d%03d' "$a" "$b" "$c"
}
if [[ "$(ver_to_int "$new_name")" -lt "$(ver_to_int "$current_name")" ]]; then
    echo "Refusing to bump: $new_name is older than $current_name." >&2
    exit 1
fi

# --- Pre-flight checks for git operations ----------------------------------

if $do_tag; then
    if git rev-parse -q --verify "refs/tags/$new_tag" >/dev/null 2>&1; then
        echo "Tag $new_tag already exists locally. Choose a different version." >&2
        exit 1
    fi
fi

if $do_commit && ! $allow_dirty; then
    # Allow only $BUILD_FILE in the diff. Anything else means the working tree
    # has uncommitted changes that don't belong in a release commit.
    other_changes="$(git status --porcelain | grep -v -E "^.. ${BUILD_FILE}$" || true)"
    if [[ -n "$other_changes" ]]; then
        echo "Refusing to commit: working tree has changes outside $BUILD_FILE." >&2
        echo "Either stash/commit them first, or pass --allow-dirty." >&2
        echo "$other_changes" >&2
        exit 1
    fi
fi

# --- Apply the version bump ------------------------------------------------

# In-place sed with portable BSD/GNU compatibility (Mac vs Linux).
sed_inplace() {
    if [[ "$(uname)" == "Darwin" ]]; then
        sed -i '' "$@"
    else
        sed -i "$@"
    fi
}

sed_inplace -E "s/(^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*)[0-9]+/\1${new_code}/" "$BUILD_FILE"
sed_inplace -E "s/(^[[:space:]]*versionName[[:space:]]*=[[:space:]]*\")[^\"]+(\".*)/\1${new_name}\2/" "$BUILD_FILE"

# Verify the edit took. Defends against future build.gradle.kts reformatting
# silently breaking the regex.
post_name="$(grep -E '^[[:space:]]*versionName[[:space:]]*=' "$BUILD_FILE" | sed -E 's/.*"([^"]+)".*/\1/')"
post_code="$(grep -E '^[[:space:]]*versionCode[[:space:]]*=' "$BUILD_FILE" | sed -E 's/.*=[[:space:]]*([0-9]+).*/\1/')"
if [[ "$post_name" != "$new_name" || "$post_code" != "$new_code" ]]; then
    echo "Version edit didn't apply cleanly (got name=$post_name code=$post_code)." >&2
    echo "Restore $BUILD_FILE from git and check the regex." >&2
    exit 1
fi

echo "Bumped $current_name (code $current_code) → $new_name (code $new_code)."

# --- Optional git operations -----------------------------------------------

if $do_commit; then
    git add "$BUILD_FILE"
    git commit -m "Release ${new_name}" >/dev/null
    echo "Committed: $(git log -1 --pretty=format:'%h %s')"
fi

if $do_tag; then
    git tag -a "$new_tag" -m "Release ${new_name}"
    echo "Tagged: $new_tag"
fi

if $do_push; then
    # Push the branch first so the tag has a commit reachable from origin.
    branch="$(git symbolic-ref --short HEAD)"
    git push origin "$branch"
    git push origin "$new_tag"
    echo "Pushed $branch and tag $new_tag — release workflow will start shortly."
else
    # Tell the user exactly how to continue from where the script stopped.
    echo
    if $do_tag; then
        echo "Next: git push origin HEAD && git push origin $new_tag"
    elif $do_commit; then
        echo "Next: $0 $bump_arg --tag    (or --push to also fire the release workflow)"
    else
        echo "Next: $0 $bump_arg --commit  (or --tag / --push)"
    fi
fi

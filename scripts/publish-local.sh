#!/usr/bin/env bash
set -u

main_only=false
if [ "${1:-}" = "--main-only" ]; then main_only=true; shift; fi
if [ "${1:-}" = "--stop-hook" ]; then
    shift
    dir=$(cat | sed -n 's/.*"cwd"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | sed 's#\\\\#/#g')
    set -- "${dir:-.}"
fi

top=$(git -C "${1:-.}" rev-parse --show-toplevel 2>/dev/null) || exit 0
[ -x "$top/gradlew" ] || exit 0

[ -n "$(git -C "$top" config core.hooksPath 2>/dev/null)" ] || git -C "$top" config --local core.hooksPath .githooks

branch=$(git -C "$top" rev-parse --abbrev-ref HEAD)
[ "$main_only" = true ] && [ "$branch" != main ] && exit 0

state_dir="$HOME/.m2/repository/com/kanetik/billing"
mkdir -p "$state_dir"
stamp="$state_dir/.publish-local-stamp"
log="$state_dir/publish-local.log"
lock="$state_dir/.publish-local.lock"

inputs=(billing build.gradle.kts settings.gradle.kts gradle.properties gradle/libs.versions.toml)
untracked=$(git -C "$top" ls-files --others --exclude-standard -- "${inputs[@]}")
hash=$( {
    git -C "$top" ls-files -s -- "${inputs[@]}"
    git -C "$top" diff HEAD -- "${inputs[@]}"
    [ -n "$untracked" ] && printf '%s\n' "$untracked" | git -C "$top" hash-object --stdin-paths 2>/dev/null
} | git hash-object --stdin)
[ "$(cat "$stamp" 2>/dev/null)" = "$hash" ] && exit 0

if ! mkdir "$lock" 2>/dev/null; then
    pid=$(cat "$lock/pid" 2>/dev/null)
    if [ -n "$pid" ]; then
        kill -0 "$pid" 2>/dev/null && exit 0
    elif [ -n "$(find "$lock" -maxdepth 0 -mmin -1 2>/dev/null)" ]; then
        exit 0
    fi
    rm -rf "$lock" && mkdir "$lock" || exit 0
fi

(
    trap 'rm -rf "$lock"' EXIT
    cd "$top" || exit 1
    sha=$(git rev-parse --short HEAD)
    untracked_now=$(git ls-files --others --exclude-standard -- "${inputs[@]}")
    dirty=$( { git diff --quiet HEAD -- "${inputs[@]}" && [ -z "$untracked_now" ]; } || echo "+dirty")
    if ./gradlew -q :billing:publishToMavenLocal > "$state_dir/publish-local-last.log" 2>&1; then
        echo "$hash" > "$stamp"
        echo "$(date -u +%FT%TZ) published $branch@$sha$dirty from $top" >> "$log"
    else
        echo "$(date -u +%FT%TZ) FAILED $branch@$sha$dirty from $top (see publish-local-last.log)" >> "$log"
    fi
) < /dev/null > /dev/null 2>&1 &
echo $! > "$lock/pid"

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

branch=$(git -C "$top" rev-parse --abbrev-ref HEAD)
[ "$main_only" = true ] && [ "$branch" != main ] && exit 0

state_dir="$HOME/.m2/repository/com/kanetik/billing"
mkdir -p "$state_dir"
stamp="$state_dir/.publish-local-stamp"
log="$state_dir/publish-local.log"
lock="$state_dir/.publish-local.lock"

inputs=(billing build.gradle.kts settings.gradle.kts gradle.properties gradle/libs.versions.toml)
hash=$( { git -C "$top" ls-files -s -- "${inputs[@]}"; git -C "$top" diff HEAD -- "${inputs[@]}"; } | git hash-object --stdin)
[ "$(cat "$stamp" 2>/dev/null)" = "$hash" ] && exit 0

if ! mkdir "$lock" 2>/dev/null; then
    [ -n "$(find "$lock" -maxdepth 0 -mmin -15 2>/dev/null)" ] && exit 0
    rm -rf "$lock" && mkdir "$lock" || exit 0
fi

sha=$(git -C "$top" rev-parse --short HEAD)
dirty=$(git -C "$top" diff --quiet HEAD -- "${inputs[@]}" || echo "+dirty")

(
    trap 'rm -rf "$lock"' EXIT
    cd "$top" || exit 1
    if ./gradlew -q :billing:publishToMavenLocal > "$state_dir/publish-local-last.log" 2>&1; then
        echo "$hash" > "$stamp"
        echo "$(date -u +%FT%TZ) published $branch@$sha$dirty from $top" >> "$log"
    else
        echo "$(date -u +%FT%TZ) FAILED $branch@$sha$dirty from $top (see publish-local-last.log)" >> "$log"
    fi
) < /dev/null > /dev/null 2>&1 &

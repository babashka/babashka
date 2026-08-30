# Fetches a pinned source tarball, for the scripts that build a dependency
# from source. Sourced, not run.
#
# Three things keep a build from failing on someone else's outage. A cache,
# so a tarball is fetched once per machine. Retries, for the short timeout
# that took a release build down on 2026-08-25. And mirrors, tried in turn.
#
# Mirrors are safe here because every tarball is pinned by sha256: bytes
# that do not match are rejected, and the next mirror gets a turn. So a
# stale mirror costs a download, not a wrong build.

# the sha256sum on macOS is a different tool that takes no checksum file
if sha256sum --version > /dev/null 2>&1; then
    tarball_sha256() { sha256sum "$1" | cut -d' ' -f1; }
else
    tarball_sha256() { shasum -a 256 "$1" | cut -d' ' -f1; }
fi

# fetch_tarball <file> <sha256> <url>...
#
# Leaves the verified tarball at <file> in the working directory. Fails when
# no url serves the pinned bytes.
fetch_tarball() {
    local file=$1 sha=$2
    shift 2
    local cache_dir="${BABASHKA_TARBALL_CACHE:-$HOME/.cache/babashka-tarballs}"
    local cached="$cache_dir/$sha-$file"

    if [ -r "$cached" ] && [ "$(tarball_sha256 "$cached")" = "$sha" ]; then
        echo "fetch_tarball: $file from the cache" >&2
        cp "$cached" "$file"
        return 0
    fi

    local url actual
    for url in "$@"; do
        echo "fetch_tarball: $file from $url" >&2
        # --retry covers the transient errors, a timeout among them
        if ! curl -sL --fail --show-error --connect-timeout 20 \
             --retry 4 --retry-delay 2 --retry-connrefused \
             -o "$file" "$url"; then
            echo "fetch_tarball: $url did not answer" >&2
            continue
        fi
        actual="$(tarball_sha256 "$file")"
        if [ "$actual" != "$sha" ]; then
            echo "fetch_tarball: $url served $actual, expected $sha" >&2
            rm -f "$file"
            continue
        fi
        # The cache is an optimisation, and a build must not fail on one.
        # A root-run setup script can leave the directory owned by root,
        # and a later step then cannot write into it.
        if ! { mkdir -p "$cache_dir" && cp "$file" "$cached"; } 2> /dev/null; then
            echo "fetch_tarball: could not cache $file, continuing" >&2
        fi
        return 0
    done

    echo "fetch_tarball: no source served $file with sha256 $sha" >&2
    return 1
}

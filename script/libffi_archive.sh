# script/compile sources this file. Sets libffi_archive to the archive path
# or an empty value, and exports BABASHKA_FEATURE_LIBFFI to match: compile
# passes it to the builder, and babashka.impl.features requires the bindings
# only when the archive is there.
# Static musl builds
# do not link libffi. BABASHKA_LIBFFI=none also disables it. Another value
# selects an existing archive.
#
# A local build continues without libffi if setup fails. A CI build stops.
#
# Exports BABASHKA_FEATURE_LIBFFI for image initialization.

libffi_archive=""

if [ "${BABASHKA_MUSL:-}" = "true" ] || [ "${BABASHKA_LIBFFI:-}" = "none" ]; then
    :
elif [ -n "${BABASHKA_LIBFFI:-}" ]; then
    libffi_archive="$BABASHKA_LIBFFI"
elif libffi_archive="$(script/setup-libffi)"; then
    :
elif [ "${CI:-}" = "true" ]; then
    echo "libffi: script/setup-libffi failed, and a CI build links libffi" >&2
    exit 1
else
    echo "libffi: script/setup-libffi failed, building without libffi." >&2
    echo "libffi: set BABASHKA_LIBFFI to an archive to link, or to none to skip this attempt." >&2
    libffi_archive=""
fi

if [ -n "$libffi_archive" ]; then
    export BABASHKA_FEATURE_LIBFFI=true
else
    export BABASHKA_FEATURE_LIBFFI=false
fi

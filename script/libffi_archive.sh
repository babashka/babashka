# Sourced by script/uberjar and script/compile. Both need the same answer to
# "does this build link libffi": uberjar puts the @CFunction bindings that
# need the symbols on the classpath, compile links the archive, and a
# disagreement leaves the image with undefined symbols.
#
# Sets libffi_archive to the archive to link, or to nothing. Every build
# links libffi except the musl static one, which has no dlopen and so cannot
# call a shared library at all, and a build that sets BABASHKA_LIBFFI=none.
# BABASHKA_LIBFFI names an archive to link instead of the one
# script/setup-libffi builds, for a packager who has to link the system
# libffi or who builds without a network.
#
# A local build goes on without libffi when script/setup-libffi fails, with
# a warning, so that a missing make or network does not stop it. A CI build
# fails instead: a release binary without libffi must not appear by accident.
#
# Exports BABASHKA_FEATURE_LIBFFI, which babashka.impl.features reads at
# build time: with it, babashka requires babashka.impl.libffi, so that the
# bindings are reachable in the image.

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

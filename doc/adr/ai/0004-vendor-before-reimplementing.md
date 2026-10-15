# ADR 0004: vendor upstream code before writing bb's own

## Status

Recorded 2026-10-15 on branch `cider-doc-clojuredocs`.

## Context

CIDER's doc buffer shows ClojureDocs examples through the cider-nrepl op
`cider/clojuredocs-lookup`. bb did not answer it. The first fix was a new
namespace, `babashka.nrepl.impl.clojuredocs`, that reimplemented
`orchard.clojuredocs`: cache path, download, loading and lookup.

`orchard.clojuredocs` runs in bb almost unchanged. `orchard.misc` was
already bundled, `ReentrantLock` and `HttpsURLConnection` were already
in `classes.clj`, and `ns-resolve` and `(str var)` work on sci vars.
`script/vendor_bundled_sources.clj` had it in `dropped` under "JVM
introspection bb does not do", a wrong label that made vendoring look
unavailable.

## Decision

When bb needs an op or a namespace that exists upstream, first try the
upstream file through `script/vendor_bundled_sources.clj`, with `BB-PATCH`
regions where sci or the image needs a change. Write bb's own version only
when that fails, and record why next to the file in `stand-ins`.

The middleware layer of cider-nrepl stays bb's own, as in
`babashka.nrepl.impl.cider`. Those files depend on
`cider.nrepl.middleware.util.error-handling` and the `def-wrapper`
loading in `cider/nrepl.clj`, which bb does not ship, and each is a
few lines over the orchard call.

Before calling an upstream file unusable, load it in bb and check its
requires and interop against the image.

For ClojureDocs:

- `orchard/clojuredocs.clj` and `orchard/util/os.clj` are shipped.
- The bundled 1.6 MB `clojuredocs/export.edn` is not, to keep the image
  small. A `BB-PATCH` downloads the export to orchard's cache file
  instead, and a second one raises the HTTP timeout from 1 s to 10 s.
  Through GitHub's redirect the JVM took 0.5 to 1.5 s, and the first
  lookup now depends on that download.
- `babashka.nrepl.impl.clojuredocs` is the middleware: the two ops and
  their unprefixed names, calling orchard.
- `info` adds `see-also` only when the export is cached, so `info` never
  goes to the network.

## Consequences

- An orchard bump merges ClojureDocs changes like any other vendored
  file. The two patches are the merge cost.
- bb and a JVM CIDER setup share one cache file.

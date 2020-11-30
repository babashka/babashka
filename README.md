This fork of [babashka](https://github.com/borkdude/babashka) includes the following additional features:

- [Ring](https://github.com/ring-clojure/ring) middleware
- [Reitit](https://github.com/metosin/reitit) routing
- [Muuntaja](https://github.com/metosin/muuntaja) data encoding
- [Selmer](https://github.com/yogthos/Selmer) html templating

See the [bb-web](https://github.com/kloimhardt/bb-web#lumius-guestbook-rich-back-end) repository for an example.

A binary for MS-Windows is provided in the release artefacts. Install also via [scoop-clojure](https://github.com/littleli/scoop-clojure)

To build binaries for any platform, follow the [build instructions](https://github.com/borkdude/babashka/blob/master/doc/build.md). Make sure to have the shell environment variables `BABASHKA_FEATURE_RING`, `BABASHKA_FEATURE_REITIT`, `BABASHKA_FEATURE_SELMER` set to `true` (in bash via the `export` command).



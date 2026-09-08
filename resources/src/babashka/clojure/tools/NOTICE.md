# Bundled tools.deps sources

The namespaces under this directory are copied from these releases by
Rich Hickey and Alex Miller, licensed under the Eclipse Public License
1.0, see the header of each file:

- org.clojure/tools.deps 0.31.1638
- org.clojure/tools.deps.edn 0.9.42
- org.clojure/tools.gitlibs 2.6.217

babashka serves them from the binary and interprets them with sci, so a
script can require them, and `clojure.tools.deps.script.make-classpath2`
runs in-process for `babashka.deps/add-deps`, `-Sdeps`, bb.edn `:deps`
and `bb clojure`. `script/vendor_tools_deps.clj` makes the copies and is
the only way they change; a tools.deps bump reruns it.

Not copied, and why:

- `clojure/tools/deps/specs.clj`: built on clojure.spec, which babashka
  keeps out of the image. A built-in stub of the same namespace makes
  `valid-deps?` true and `explain-deps` nil; deps.edn validation is
  skipped.
- `clojure/tools/deps/gen/pom.clj` and
  `clojure/tools/deps/script/generate_manifest2.clj`: `-Spom` runs in a
  java, as before.

Replaced by stand-ins at the same paths, because the originals import
Maven classes when they load:

- `clojure/tools/deps/util/maven.clj`: the small surface other namespaces
  use, standard repositories, settings, the local repository, over
  `babashka.mvn`.
- `clojure/tools/deps/extensions/maven.clj`: requires
  `babashka.mvn.tools-deps`, which registers the `:mvn` and `:pom`
  extension methods. clojure.tools.deps loads this path in place of its own.
- `clojure/tools/deps/extensions/pom.clj`: `read-model` and `model-deps`
  over `babashka.mvn.pom`.
- `clojure/tools/deps/extensions/local.clj`: the original, with its `:jar`
  methods reading the POM text out of the jar instead of through Maven's
  model builder.

Each stand-in says `BB-STAND-IN` in its docstring. Two files carry patches
between `BB-PATCH` and `END-BB-PATCH` markers, the upstream form kept under
`#_` next to each: `local.clj` as above, and `clojure/tools/deps/edn.clj`,
whose `root-deps` returns the root deps.edn as data, since the binary cannot
see the jar resource; `script/vendor_tools_deps.clj` writes that one.
Everything else is verbatim.

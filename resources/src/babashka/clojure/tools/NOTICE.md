# Bundled Clojure sources

The namespaces under `clojure/` are copied from these releases, licensed
under the Eclipse Public License 1.0, see the header of each file:

- org.clojure/tools.deps 0.31.1638, by Rich Hickey and Alex Miller
- org.clojure/tools.deps.edn 0.9.42, by Rich Hickey and Alex Miller
- org.clojure/tools.gitlibs 2.6.217, by Rich Hickey and Alex Miller
- io.github.clojure/tools.build 0.10.14, by Rich Hickey and Alex Miller
- org.clojure/tools.namespace 1.5.1, by Stuart Sierra, five namespaces
- org.clojure/java.classpath 1.1.1, by Stuart Sierra

babashka serves them from the binary and interprets them with sci, so a
script can require them without a dependency. tools.deps'
`clojure.tools.deps.script.make-classpath2` runs in-process for
`babashka.deps/add-deps`, `-Sdeps`, bb.edn `:deps` and `bb clojure`, and
tools.build's `uber` task builds `bb uberjar`.
`script/vendor_bundled_sources.clj` makes the copies and is the only way
they change; a version bump reruns it.

## Which copy loads

tools.deps, tools.deps.edn and tools.gitlibs always load from the binary,
so a jar of them on the classpath cannot mix its version in or bring Maven
along. For tools.build, tools.namespace and java.classpath the classpath
comes first: a project that depends on them gets its own version, and the
bundled copy is the fallback. The two tools.build stand-ins below are the
exception and always load from the binary. A directory on the classpath
wins over the binary in every case, which is how a fix to a bundled file
can be tried without building bb.

## Not copied, and why

- `clojure/tools/deps/specs.clj`: built on clojure.spec, which babashka
  keeps out of the image. A built-in stub of the same namespace makes
  `valid-deps?` true and `explain-deps` nil; deps.edn validation is
  skipped.
- `clojure/tools/deps/gen/pom.clj` and
  `clojure/tools/deps/script/generate_manifest2.clj`: `-Spom` runs in a
  java, as before.
- tools.namespace beyond `dependency`, `file`, `find`, `parse` and
  `track`, which are what tools.build's `compile-clj` uses to find
  namespaces.

## Stand-ins

Replaced at the same paths, because the originals use what the image does
not have:

- `clojure/tools/deps/util/maven.clj`: the small surface other namespaces
  use, standard repositories, settings, the local repository, over
  `babashka.impl.mvn`.
- `clojure/tools/deps/extensions/maven.clj`: requires
  `babashka.impl.mvn.tools-deps`, which registers the `:mvn` and `:pom`
  extension methods. clojure.tools.deps loads this path in place of its own.
- `clojure/tools/deps/extensions/pom.clj`: `read-model` and `model-deps`
  over `babashka.impl.mvn.pom`.
- `clojure/tools/deps/extensions/local.clj`: the original, with its `:jar`
  methods reading the POM text out of the jar instead of through Maven's
  model builder.
- `clojure/tools/build/tasks/install.clj`: lays the jar and POM into the
  local repository the way Maven Resolver does, with `_remote.repositories`
  and `maven-metadata-local.xml`.
- `clojure/tools/build/tasks/javac.clj`: spawns javac instead of the
  in-process compiler API, which the image does not have.

Each stand-in says `BB-STAND-IN` in its docstring. Two files carry patches
between `BB-PATCH` and `END-BB-PATCH` markers, the upstream form kept under
`#_` next to each: `local.clj` as above, and `clojure/tools/deps/edn.clj`,
whose `root-deps` returns the root deps.edn as data, since the binary cannot
see the jar resource; `script/vendor_bundled_sources.clj` writes that one.
Everything else is verbatim.

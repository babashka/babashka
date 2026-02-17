# ADR: sqlite4j as optional SQLite JDBC driver

## Status

Experimental (feature branch `feature/sqlite4j`)

## Context

Babashka already supports xerial/sqlite-jdbc via `BABASHKA_FEATURE_SQLITE`, which uses JNI
and bundles platform-specific native libraries. This makes native-image builds
platform-dependent and requires a native SQLite library for each target OS/arch.

[sqlite4j](https://github.com/roastedroot/sqlite4j) is a pure-Java SQLite
implementation that compiles SQLite to JVM bytecode via
[Chicory](https://github.com/nicholasgasior/chicory) (a Wasm runtime). It
requires no JNI or native libraries.

## Decision

Add sqlite4j as an optional JDBC driver feature (`BABASHKA_FEATURE_SQLITE4J`),
following the same pattern as other JDBC drivers (postgresql, hsqldb, etc.).

Build with:
```
BABASHKA_FEATURE_JDBC=true BABASHKA_FEATURE_SQLITE4J=true script/uberjar
BABASHKA_FEATURE_JDBC=true BABASHKA_FEATURE_SQLITE4J=true script/compile
```

## Trade-offs

### Advantages over xerial/sqlite-jdbc

- **No native libraries**: pure Java, no JNI, no platform-specific `.so`/`.dylib`
- **GraalVM native-image**: works without bundling native libs per target platform
- **Portability**: runs on any JVM without OS/arch-specific dependencies

### Limitations

- **Binary size**: adds ~10MB to the native-image binary
- **Performance**: slower than xerial (Wasm interpreter vs native SQLite)
- **File persistence is not transparent**: sqlite4j uses an in-memory virtual
  filesystem (zerofs). On open, the file is copied into the VFS. All operations
  happen in-memory. On close, the VFS is discarded without writing back to disk.
  To persist data, you must explicitly issue a `backup` command via
  `Statement.execute()` (not `PreparedStatement`). The Quarkus extension works
  around this with `min-size=1` (keep a connection alive) and scheduled backup
  calls. See the [Quarkus sqlite4j docs](https://docs.quarkiverse.io/quarkus-jdbc-sqlite4j/dev/index.html).
- **In-memory databases work out of the box**: no special handling needed for
  `jdbc:sqlite::memory:`

### Native-image configuration

The following initialization flags are required:

- `--initialize-at-build-time=io.roastedroot.zerofs`
- `--initialize-at-build-time=io.roastedroot.sqlite4j`
- `--initialize-at-build-time=com.dylibso.chicory`
- `--initialize-at-run-time=io.roastedroot.sqlite4j.JDBC` (so the driver registers with `DriverManager` at runtime)

The JDBC driver class also needs reflection registration and an explicit
`Class/forName` call in user scripts:

```clojure
(Class/forName "io.roastedroot.sqlite4j.JDBC")
```

## Branch

The implementation lives on the `feature/sqlite4j` branch of babashka. Files changed:

- `deps.edn` — added `io.roastedroot/sqlite4j` dependency
- `project.clj` — added `:sqlite4j/deps` and `:feature/sqlite4j` profiles, bumped `graal-build-time` to 1.0.5
- `src/babashka/impl/features.clj` — added `sqlite4j?` feature flag
- `src/babashka/impl/classes.clj` — added `io.roastedroot.sqlite4j.JDBC` and `JDBC4Connection` to reflection classes
- `src/babashka/main.clj` — added `features/sqlite4j?` to describe output
- `script/uberjar` — added sqlite4j profile toggling
- `script/compile` — added native-image initialization flags for zerofs, sqlite4j, chicory
- `resources/META-INF/native-image/babashka/babashka/native-image.properties` — added `-EBABASHKA_FEATURE_SQLITE4J` env passthrough, added `clj_easy.graal_build_time.InitClojureClasses` feature (needed after graal-build-time bump)

- `test-sqlite4j.bb` — test script for verifying the feature

## Usage

```clojure
(require '[next.jdbc :as jdbc]
         '[next.jdbc.result-set :as rs])

(Class/forName "io.roastedroot.sqlite4j.JDBC")

(let [ds (jdbc/get-datasource "jdbc:sqlite::memory:")]
  (with-open [conn (jdbc/get-connection ds)]
    (jdbc/execute! conn ["create table t (x int)"])
    (jdbc/execute! conn ["insert into t values (42)"])
    (println (jdbc/execute! conn ["select * from t"]
                            {:builder-fn rs/as-unqualified-maps}))))
```

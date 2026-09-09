# babashka.mvn

A Maven repository procurer for tools.deps in plain Clojure, so babashka
resolves `:mvn/version` coordinates without a JVM. It plugs into tools.deps
through its extension multimethods and replaces nothing else.

It follows Maven's behaviour and, where the behaviour is an algorithm,
ports it. Those parts derive from Apache Maven and its libraries, which are
licensed under the Apache License, Version 2.0; a copy is in
`LICENSE-APACHE-2.0.txt` next to this file.

- `version.clj`: a port of `GenericVersion` from maven-resolver-util
  1.9.27, the version ordering tools.deps compares with. Its behaviour is
  checked against the cases of maven-resolver-util's `GenericVersionTest`,
  see `script/mvn_oracle/version_cases.clj`.
- `cipher.clj`: the encrypted password format of plexus-cipher 2.0 and
  plexus-sec-dispatcher 2.0, reimplemented from their behaviour and
  checked against vectors those libraries produced.
- `pom.clj`: the effective POM model after Maven's `DefaultModelBuilder`,
  `ModelMerger` and profile activators, for what dependency resolution
  needs.
- `settings.clj`: mirror and proxy selection after Maven's
  `DefaultMirrorSelector` and `DefaultProxySelector`.
- `repo.clj` and `metadata.clj`: the local repository layout, the
  `_remote.repositories` markers and `maven-metadata-<repo>.xml` files as
  Maven Resolver keeps them, so the two share one local repository.

The rest is babashka's own. The whole is checked against the JVM
tools.deps resolver over a corpus, warm and cold, by
`script/mvn_oracle/run.clj`.

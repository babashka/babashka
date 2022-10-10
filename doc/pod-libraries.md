# Pods distributed as manifest libraries

Babashka library pods are a way of distributing pods that uses the normal
Clojure library dependency system to install and load pods.
It eliminates the need for the centralized babashka pod registry because the
pod manifest is (the only thing) downloaded in the library JAR file.
This document assumes you are already familiar with Babashka pods themselves
and only covers distributing them via Clojure libraries.

Let's break down the various components of how this works:

1. The `pod-manifest.edn` file
    1. The first thing your pod needs is a `pod-manifest.edn` file. It is
       recommended to store this in your pod project's `resources` dir under a
       directory hierarchy matching your pod's top-level exposed namespace (but
       replacing hyphens with underscores as usual in Clojure → Java name
       munging). So, for example, if your pod exposes namespaces starting with
       `pod.foo`, you should put your `pod-manifest.edn` in this path in your
       pod project: `resources/pod/foo/pod-manifest.edn`.
        1. By putting your `pod-manifest.edn` file in this namespace-mirroring
           directory hierarchy inside your `resources` dir, you can consume it
           while developing your pod using tools.deps' handy `:local/root`
           feature.
        2. If you have more than one pod in the same project (or just prefer
           it), you can nest the `pod-manifest.edn` files deeper into their
           respective namespace hierarchies. As long as there are at least two
           namespace element directories, babashka will find the corresponding
           `pod-manifest.edn` at runtime. So, for example, you can have
           `foo.bar.baz.qux` → `resources/foo/bar/baz/qux/pod-manifest.edn` but
           not just `foo` → `resources/foo/pod-manifest.edn`. This is because
           a) single-element namespaces are frowned upon anyway in Clojure and
           b) the first namespace element is often just `com` or `pod` or
           similar and `pod/pod-manifest.edn` would be a very ambiguous
           resource path to look for in a typical classpath.
    2. Make sure the `resources` dir is in the `:paths` vector in your pod
       project's `deps.edn` file. E.g. `:paths ["src" "resources"]`.
    3. The format of the `pod-manifest.edn` file looks like this:

```clojure
{:pod/name pod.foo/bar
 :pod/description "Foo bar pod"
 :pod/version "0.4.0"
 :pod/license "MIT"
 :pod/artifacts
 [{:os/name "Linux.*"
   :os/arch "amd64"
   :artifact/url "https://github.com/foo/pod-bar/releases/download/v0.4.0/pod-foo-bar-linux-amd64.zip"
   :artifact/executable "pod-foo-bar"}
  {:os/name "Linux.*"
   :os/arch "aarch64"
   :artifact/url "https://github.com/foo/pod-bar/releases/download/v0.4.0/pod-foo-bar-linux-arm64.zip"
   :artifact/executable "pod-foo-bar"}
  {:os/name "Mac.*"
   :os/arch "amd64"
   :artifact/url "https://github.com/foo/pod-bar/releases/download/v0.4.0/pod-foo-bar-macos-amd64.zip"
   :artifact/executable "pod-foo-bar"}]}
```

1. The library JAR file
    1. This JAR file can be built however you want (but see below for an example
       tools.build `build.clj` snippet for generating it), but it must contain a
       `pod-manifest.edn` file in a path corresponding to your pod's top-level
       namespace as described above. For example, if your pod exposes the
       namespaces `pod.foo.bar.baz` & `pod.foo.bar.qux` then you should have this
       directory layout inside your JAR file: `pod/foo/bar/pod-manifest.edn`.
    2. You then distribute this library JAR to any Maven repo you like
       (e.g. Clojars).
2. The consuming project
    1. In the project that wants to use your `pod.foo/bar` pod, you add the
       library's Maven coordinates to your `deps.edn` or `bb.edn` `:deps`
       section. This will put the `pod/foo/bar/pod-manifest.edn` file onto your
       project's classpath. `bb` will then be able to find your pod's manifest
       and install and load the pod.
3. Example `build.clj` snippet for building the library JAR

```clojure
(ns build
  (:require
    [clojure.string :as str]
    [clojure.tools.build.api :as b]))

(def lib 'pod.foo/bar)
(def version "0.4.0")
(def jar-sources "resources")
(def class-dir "target/resources")
(def basis (b/create-basis {:project "deps.edn"}))
(def pod-manifest "pod/foo/bar/pod-manifest.edn")
(def jar-file (format "target/%s-%s.jar" (-> lib namespace (str/replace "." "-")) (name lib)))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (b/write-pom {:class-dir class-dir
                :lib       lib
                :version   version
                :basis     basis})
  (b/copy-file {:src    (str jar-sources "/" pod-manifest)
                :target (str class-dir "/" pod-manifest)})
  (b/jar {:class-dir class-dir
          :jar-file  jar-file}))

;; optional - allows you to install the JAR to your local ~/.m2/repository for testing
(defn install [_]
  (jar nil)
  (b/install {:basis     basis
              :lib       lib
              :version   version
              :jar-file  jar-file
              :class-dir class-dir}))
```

You can then build this pod library via: `clj -T:build jar` and the JAR file will be in `target/pod-foo-bar.jar`.

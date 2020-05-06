# pod-babashka-filewatcher

## Compile

```
$ cargo build --release
```

## Run

```
(babashka.pods/load-pod "examples/pods/pod-babashka-filewatcher/target/release/pod-babashka-filewatcher")
(def chan (pod.babashka.filewatcher/watch "/tmp"))
(require '[clojure.core.async :as async])
(loop [] (prn (async/<!! chan)) (recur))
;;=> ["changed" "/tmp"]
;;=> ["changed" "/tmp"]
```

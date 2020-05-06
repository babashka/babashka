# pod-babashka-filewatcher

## Compile

```
$ cargo build --release
```

## Run

``` clojure
(babashka.pods/load-pod "target/release/pod-babashka-filewatcher")
(def chan (pod.babashka.filewatcher/watch "/tmp"))
(require '[clojure.core.async :as async])
(loop [] (prn (async/<!! chan)) (recur))
;;=> ["changed" "/tmp"]
;;=> ["changed" "/tmp"]
```

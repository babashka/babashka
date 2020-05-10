# Pods

Pods are standalone programs that can expose namespaces with vars to
babashka. Pods can be created independently from babashka. Any program can be
invoked as a pod as long as it implements the _pod protocol_. This protocol is
influenced by and built upon battle-tested technologies:

- the [nREPL](https://nrepl.org/) and [LSP](https://microsoft.github.io/language-server-protocol/) protocols
- [bencode](https://en.wikipedia.org/wiki/Bencode)
- [JSON](https://www.json.org/json-en.html)
- [EDN](https://github.com/edn-format/edn)
- composition of UNIX command line tools in via good old stdin and stdout

Pods are a brand new way to extend babashka and you should consider the protocol
alpha. Breaking changes may occur at this phase. Pods were introduced in
babashka version `0.0.92`.

Currently the following pods are available:

- [clj-kondo](https://github.com/borkdude/clj-kondo/#babashka-pod): a Clojure
  linter
- [pod-babashka-filewatcher](https://github.com/babashka/pod-babashka-filewatcher): a
  filewatcher pod based on Rust notify.
- [pod-babashka-hsqldb](https://github.com/babashka/pod-babashka-hsqldb): a pod
  that allows you to create and fire queries at a
  [HSQLDB](http://www.hsqldb.org/) database.

The name pod is inspired by [boot's pod
feature](https://github.com/boot-clj/boot/wiki/Pods). It means _underneath_ or
_below_ in Polish and Russian. In Romanian it means _bridge_
([source](https://en.wiktionary.org/wiki/pod)).

## Implementing your own pod

### Examples

Beyond the already available pods mentioned above, eductional examples of pods
can be found [here](../examples/pods):

- [pod-lispyclouds-sqlite](../examples/pods/pod-lispyclouds-sqlite): a pod that
  allows you to create and fire queries at a [sqlite](https://www.sqlite.org/)
  database. Implemented in Python.

### Naming

When choosing a name for your pod, we suggest the following naming scheme:

```
pod-<user-id>-<pod-name>
```

where `<user-id>` is your Github or Gitlab handle and `<pod-name>` describes
what your pod is about.

Examples:

- [pod-lispyclouds-sqlite](../examples/pods/pod-lispyclouds-sqlite): a pod to
  communicate with [sqlite](https://www.sqlite.org/), provided by
  [@lispyclouds](https://github.com/lispyclouds).

Pods created by the babashka maintainers use the identifier `babashka`:

- [pod-babashka-hsqldb](https://github.com/borkdude/pod-babashka-hsqldb): a pod
  to communicate with [HSQLDB](http://www.hsqldb.org/)

### The protocol

#### Message and payload format

Exchange of _messages_ between babashka and the pod happens in the
[bencode](https://en.wikipedia.org/wiki/Bencode) format. Bencode is a bare-bones
format that only has four types:

- integers
- lists
- dictionaries (maps)
- byte strings

Additionally, _payloads_ like `args` (arguments) or `value` (a function return
value) are encoded in either JSON or EDN.

So remember: messages are in bencode, payloads (particular fields in the
message) are in either JSON or EDN.

Bencode is chosen as the message format because it is a light-weight format
which can be implemented in 200-300 lines of code in most languages. If pods are
implemented in Clojure, they only need to depend on the
[bencode](https://github.com/nrepl/bencode) library and use `pr-str` and
`edn/read-string` for encoding and decoding payloads.

Why isn't EDN or JSON chosen as the message format instead of bencode, you may
ask.  Assuming EDN or JSON as the message and payload format for all pods is too
constraining: other languages might already have built-in JSON support and there
might not be a good EDN library available. So we use bencode as the first
encoding and choose one of multiple richer encodings on top of this. More
payload formats might be added in the future (e.g. transit).

When calling the `babashka.pods/load-pod` function, babashka will start the pod
and leave the pod running throughout the duration of a babashka script.

#### describe

The first message that babashka will send to the pod on its stdin is:

``` clojure
{"op" "describe"}
```

Encoded in bencode this looks like:

``` clojure
(bencode/write-bencode System/out {"op" "describe"})
;;=> d2:op8:describee
```

The pod should reply to this request with a message in the vein of:

``` clojure
{"format" "json"
 "namespaces"
 [{"name" "pod.lispyclouds.sqlite"
   "vars" [{"name" "execute!"}]}]
 "ops" {"shutdown" {}}}
```

In this reply, the pod declares that payloads will be encoded and decoded using
JSON. It also declares that the pod exposes one namespace,
`pod.lispyclouds.sqlite` with one var `execute!`.

The pod encodes the above map to bencode and writes it to stdoud. Babashka reads
this message from the pod's stdout.

Upon receiving this message, babashka creates these namespaces and vars.

The optional `ops` value communicates which ops the pod supports, beyond
`describe` and `invoke`. It is a map of op names to option maps. In the above
example the pod declares that it supports the `shutdown` op. Since the
`shutdown` op does not need any additional options right now, the value is an
empty map.

As a babashka user, you can load the pod with:

``` clojure
(require '[babashka.pods :as pods])
(pods/load-pod "pod-lispyclouds-sqlite")
(some? (find-ns 'pod.lispyclouds.sqlite)) ;;=> true
;; yay, the namespace exists!

;; let's give the namespace an alias
(require '[pod.lispyclouds.sqlite :as sql])
```

#### invoke

When invoking a var that is related to the pod, let's call it a _proxy var_,
babashka reaches out to the pod with the arguments encoded in JSON or EDN. The
pod will then respond with a return value encoded in JSON or EDN. Babashka will
then decode the return value and present the user with that.

Example: the user invokes `(sql/execute! "select * from foo")`. Babashka sends
this message to the pod:

``` clojure
{"id" "1d17f8fe-4f70-48bf-b6a9-dc004e52d056"
 "var" "pod.lispyclouds.sqlite/execute!"
 "args" "[\"select * from foo\"]"
```

The `id` is unique identifier generated by babashka which correlates this
request with a response from the pod.

An example response from the pod could look like:

``` clojure
{"id" "1d17f8fe-4f70-48bf-b6a9-dc004e52d056"
 "value" "[[1] [2]]"
 "status" "[\"done\"]"}
```

Here, the `value` payload is the return value of the function invocation. The
field `status` contains `"done"`. This tells babashka that this is the last
message related to the request with `id` `1d17f8fe-4f70-48bf-b6a9-dc004e52d056`.

Now you know most there is to know about the pod protocol!

#### shutdown

When babashka is about to exit, it sends an `{"op" "shutdown"}` message, if the
pod has declared that it supports it in the `describe` response. Then it waits
for the pod process to end. This gives the pod a chance to clean up resources
before it exits. If the pod does not support the `shutdown` op, the pod process
is killed by babashka.

#### out and err

Pods may send messages with an `out` and `err` string value. Babashka prints
these messages to `*out*` and `*err*`. Stderr from the pod is redirected to
`System/err`.

``` clojure
{"id" "1d17f8fe-4f70-48bf-b6a9-dc004e52d056"
 "out" "hello"}
```

``` clojure
{"id" "1d17f8fe-4f70-48bf-b6a9-dc004e52d056"
 "err" "debug"}
```

#### Error handling

Responses may contain an `ex-message` string and `ex-data` payload string (JSON
or EDN) along with an `"error"` value in `status`. This will cause babashka to
throw an `ex-info` with the associated values.

Example:

``` clojure
{"id" "1d17f8fe-4f70-48bf-b6a9-dc004e52d056"
 "ex-message" "Illegal input"
 "ex-data" "{\"input\": 10}
 "status" "[\"done\", \"error\"]"}
```

#### async

Pods may implement async functions that return one or more values at a later
time in the future. Async functions must be declared as such as part of the
`describe` response message:

``` clojure
{"format" "json"
 "namespaces"
 [{"name" "pod.babashka.filewatcher"
   "vars" [{"name" "watch" "async" "true"}]}]}
```

When calling this function from babashka, the return value is a `core.async`
channel on which the values will be received:

``` clojure
(pods/load-pod "target/release/pod-babashka-filewatcher")
(def chan (pod.babashka.filewatcher/watch "/tmp"))
(require '[clojure.core.async :as async])
(loop [] (prn (async/<!! chan)) (recur))
;;=> ["changed" "/tmp"]
;;=> ["changed" "/tmp"]
```

#### Environment

Babashka will set the `BABASHKA_POD` environment variable to `true` when
invoking the pod. This can be used by the invoked program to determine whether
it should behave as a pod or not.

Added in v0.0.94.

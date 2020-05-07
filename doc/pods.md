# Pods

Pods are standalone programs that can expose namespaces with functions to
babashka. Pods can be created independently from babashka. Any program can be
invoked as a pod, as long as it implements a protocol, the so called _pod
protocol_. The pod protocol is influenced by and built upon battle tested
technologies: the [nREPL](https://nrepl.org/) and
[LSP](https://microsoft.github.io/language-server-protocol/) protocols,
[bencode](https://en.wikipedia.org/wiki/Bencode),
[JSON](https://www.json.org/json-en.html),
[EDN](https://github.com/edn-format/edn) and composition of UNIX command line
tools in via good old stdin and stdout.

Pods are a brand new way to extend babashka and you should consider the protocol
alpha for now. Breaking changes may occur as we discover better ways of doing
things. Pods were introduced in babashka version `0.0.92`.

Pods you can try today:

- [pod-babashka-hsqldb](https://github.com/borkdude/pod-babashka-hsqldb): a pod
  that allows you to create and fire queries at a
  [HSQLDB](http://www.hsqldb.org/) database.

## Implementing your own pod

### Examples

Eductional examples of pods can be found [here](examples/pods):

- [pod-babashka-hsqldb](examples/pods/pod-babashka-hsqldb): a pod that allows
  you to create and fire queries at a [HSQLDB](http://www.hsqldb.org/)
  database. Implemented in Clojure.

- [pod-lispyclouds-sqlite](examples/pods/pod-lispyclouds-sqlite): a pod that
  allows you to create and fire queries at a [sqlite](https://www.sqlite.org/)
  database. Implemented in Python.

- [pod-babashka-filewatcher](examples/pods/pod-babashka-filewatcher): a
  filewatcher pod. It exposes one function `pod-babashka-filewatcher/watch` and
  return a `core.async` channel to listen for change events for a file
  path. Implemented in Rust.

### Naming

When choosing a name for your pod, considering the following naming scheme:

```
pod-<user-id>-<pod-name>
```

where `<user-id>` is your Github, Gitlab, etc. handle and `<pod-name>` describes the intent of your pod.

Examples:

- [pod-lispyclouds-sqlite](examples/pods/pod-lispyclouds-sqlite): a pod to
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
`edn/read-string` for encoding and decoding payloads. Then why not use EDN as
the message format?  Assuming EDN (or JSON for that matter) as the message and
payload format for all pods is too constraining: other languages might already
have built-in JSON support and there might not be a good EDN library available.
More payload formats might be added in the future (e.g. transit).

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
   "vars" [{"name" "execute!"}]}]}
```

In this reply, the pod declares that payloads will be encoded and decoded using
JSON. It also declares that the pod exposes one namespace,
`pod.lispyclouds.sqlite` with one var `execute!`.

Upon receiving this message, babashka creates these namespaces and vars.

The user can load your pod with:

``` clojure
(require '[babashka.pods :as pods])
(pods/load-pod "pod-lispyclouds-sqlite")
(some? (find-ns 'pod.lispyclouds.sqlite)) ;;=> true
(require '[pod.lispyclouds.sqlite :as sql])
```

#### invoke

When invoking var that is related to the pod, let's call it a _proxy var_,
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
 "status" "[\"done\"]"
```

Here, the `value` payload is the return value payload. The field `status`
contains `"done"` so babashka knows that this is the last message related to the
request with `id` `1d17f8fe-4f70-48bf-b6a9-dc004e52d056`.

Now you know most there is to know about the pod protocol!

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

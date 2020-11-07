# Input and output flags

In one-liners the `*input*` value may come in handy. It contains the input read
from stdin as EDN by default. If you want to read in text, use the `-i` flag,
which binds `*input*` to a lazy seq of lines of text. If you want to read
multiple EDN values, use the `-I` flag. The `-o` option prints the result as
lines of text. The `-O` option prints the result as lines of EDN values.

> **Note:** `*input*` is only available in the `user` namespace, designed for
> one-liners. For writing scripts, see [scripts](#scripts).

The following table illustrates the combination of options for commands of the form

    echo "{{Input}}" | bb {{Input flags}} {{Output flags}} "*input*"

| Input          | Input flags | Output flag | `*input*`     | Output   |
|----------------|-------------|-------------|---------------|----------|
| `{:a 1}` <br> `{:a 2}` |             |             | `{:a 1}`      | `{:a 1}` |
| hello <br> bye | `-i`        |             | `("hello" "bye")` |  `("hello" "bye")` |
| hello <br> bye | `-i`        |  `-o`       | `("hello" "bye")` |  hello <br> bye  |
| `{:a 1}` <br> `{:a 2}` | `-I`        |        | `({:a 1} {:a 2})` |  `({:a 1} {:a 2})`   |
| `{:a 1}` <br> `{:a 2}` | `-I` |  `-O`      | `({:a 1} {:a 2})` |  `{:a 1}` <br> `{:a 2}`   |

When combined with the `--stream` option, the expression is executed for each value in the input:

``` clojure
$ echo '{:a 1} {:a 2}' | bb --stream '*input*'
{:a 1}
{:a 2}
```

## Scripts

When writing scripts instead of one-liners on the command line, it is not
recommended to use `*input*`. Here is how you can rewrite to standard Clojure
code.

### EDN input

Reading a single EDN value from stdin:

``` clojure
(ns script
 (:require [clojure.edn :as edn]))

(edn/read *in*)
```

Reading multiple EDN values from stdin (the `-I` flag):

``` clojure
(ns script
 (:require [clojure.edn :as edn]
           [clojure.java.io :as io]))

(let [reader  (java.io.PushbackReader. (io/reader *in*))]
  (take-while #(not (identical? ::eof %)) (repeatedly #(edn/read {:eof ::eof} reader))))
```

### Text input

Reading text from stdin can be done with `(slurp *in*)`. To get a lazy seq of
lines (the `-i` flag), you can use:

``` clojure
(ns script
 (:require [clojure.java.io :as io]))

(line-seq (io/reader *in*))
```

### Output

To print to stdout, use `println` for text and `prn` for EDN values.

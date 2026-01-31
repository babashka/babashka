# JLine REPL Completions

## Proposal

Babashka now has a console REPL built with jline.
Relevant code is in `/src/babashka/impl/repl.clj`
Tests are in `/test/babashka/impl/repl_test.clj`.

Leiningen and rebel-readline, two other Clojure REPLs built with jline, provide completions. We want this too.
rebel-readline's code is in `~/dev/rebel-readline`. Lein's code is in `~/dev/leiningen`

We already provide completions for the nREPL implementation in `babashka.nrepl/src/babashka/nrepl/impl/server.clj`.
We should factor out this completion function so you only need a SCI context and you get back a list of maps that represent completions.
Then we can use this completion function for the console REPL as well.
For now it's ok that this completion logic stays in the nrepl submodule, although it's a bit weird to require an nrepl namespace for the console REPL, we'll think about that later.

## Implementation Plan

### Files to Modify

1. **`babashka.nrepl/src/babashka/nrepl/impl/completions.clj`** (NEW) - Factored-out completion logic
2. **`babashka.nrepl/src/babashka/nrepl/impl/server.clj`** - Update to use new completions namespace
3. **`src/babashka/impl/classes.clj`** - Add Completer and Candidate classes
4. **`src/babashka/impl/repl.clj`** - Add JLine Completer integration

### Step 1: Create completions.clj

Create new file `babashka.nrepl/src/babashka/nrepl/impl/completions.clj`:

- Move helper functions from server.clj (lines 142-195):
  - `fully-qualified-syms`
  - `match`
  - `ns-imports->completions`
  - `import-symbols->completions`

- Create new `completions` function with signature:
  ```clojure
  (defn completions [ctx query]
    ;; Returns set of maps: {:candidate "name" :ns "ns" :type "type"}
    ...)
  ```

- Extract core logic from server.clj `complete` function (lines 206-247) without the nREPL wrapping

### Step 2: Update server.clj

- Add require: `[babashka.nrepl.impl.completions :as completions]`
- Remove the moved helper functions
- Update `complete` function to call `completions/completions` and convert keyword keys to string keys for nREPL protocol

### Step 3: Add JLine classes

In `src/babashka/impl/classes.clj` at line 661 (before `;; end jline`):

```clojure
org.jline.reader.Completer
org.jline.reader.Candidate
```

Michiel: this isn't necessary yet, this is only necessary when you want to expose these classes to script users.
So skip this step.

### Step 4: Implement Completer in repl.clj

Add to imports:
```clojure
[org.jline.reader Completer Candidate]
```

Add require:
```clojure
[babashka.nrepl.impl.completions :as completions]
```

Create completer function:
```clojure
(defn- clojure-completer [sci-ctx]
  (reify Completer
    (complete [_ _ parsed-line candidates]
      (let [word (.word parsed-line)]
        (when (and word (pos? (count word)))
          (try
            (doseq [{:keys [candidate ns type]} (completions/completions sci-ctx word)]
              (.add candidates
                    (Candidate. candidate candidate nil
                                (when (or ns type)
                                  (str (when type (str type " "))
                                       (when ns ns)))
                                nil nil false)))
            (catch Exception _ nil)))))))
```

Update `jline-parser` to handle `Parser$ParseContext/COMPLETE`:
- Return a `CompletingParsedLine` that provides word-at-cursor for completion queries

Update `jline-reader` to add completer:
```clojure
(.completer (clojure-completer sci-ctx))
```

## Testing

### Manual Testing
1. Build: `script/uberjar && script/compile`
2. Start REPL: `./bb`
3. Test completions:
   - Type `ma<TAB>` - should show `map`, `mapv`, `mapcat`, etc.
   - Type `str/<TAB>` - should show `str/join`, `str/split`, etc.
   - Type `String/<TAB>` - should show static methods like `String/valueOf`

### Automated Testing
- Add unit tests for `completions/completions` in `babashka.nrepl/test/babashka/nrepl/impl/completions_test.clj`
- Add integration test in `test/babashka/impl/repl_test.clj` verifying completer creation

## Notes

- The completion logic stays in the nrepl submodule per the proposal - the console REPL requires this namespace
- Error handling: completions should never throw - silently return empty on errors
- The `CompletingParsedLine` interface is needed to provide word context to the Completer

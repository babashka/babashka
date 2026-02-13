# JLine Console REPL

## Overview

Babashka has a console REPL built with JLine.
Relevant code is in `/src/babashka/impl/repl.clj`.
Tests are in `/test/babashka/impl/repl_test.clj`.

Reusable logic factored out from the nREPL server lives in:
- `babashka.nrepl/src/babashka/nrepl/impl/sci.clj` - Completions, lookup, namespace resolution

## Completions

### Proposal

Leiningen and rebel-readline, two other Clojure REPLs built with jline, provide completions. We want this too.
rebel-readline's code is in `~/dev/rebel-readline`. Lein's code is in `~/dev/leiningen`

We already provide completions for the nREPL implementation in `babashka.nrepl/src/babashka/nrepl/impl/server.clj`.
We should factor out this completion function so you only need a SCI context and you get back a list of maps that represent completions.
Then we can use this completion function for the console REPL as well.
For now it's ok that this completion logic stays in the nrepl submodule, although it's a bit weird to require an nrepl namespace for the console REPL, we'll think about that later.

### Implementation

#### Files Modified

1. **`babashka.nrepl/src/babashka/nrepl/impl/sci.clj`** - Shared SCI helpers (completions, lookup, namespace resolution)
2. **`babashka.nrepl/src/babashka/nrepl/impl/server.clj`** - Updated to use sci helpers namespace
3. **`src/babashka/impl/repl.clj`** - Added JLine Completer integration

#### Key Decisions

- Completion logic stays in the nrepl submodule - the console REPL requires this namespace
- Error handling: completions never throw - silently return empty on errors
- The `CompletingParsedLine` interface provides word context to the Completer
- JLine classes (Completer, Candidate) are not added to classes.clj since they're only used internally, not exposed to script users

### Testing

#### Manual
1. Build: `script/uberjar && script/compile`
2. Start REPL: `./bb`
3. Test completions:
   - Type `ma<TAB>` - should show `map`, `mapv`, `mapcat`, etc.
   - Type `str/<TAB>` - should show `str/join`, `str/split`, etc.
   - Type `String/<TAB>` - should show static methods like `String/valueOf`

#### Automated
- Unit tests for `word-at-cursor` and `complete-form?` in `test/babashka/impl/repl_test.clj`

## Docstrings (doc-at-point)

### Proposal

rebel-readline provides a doc-at-point widget that displays documentation for the symbol under the cursor when pressing Ctrl-X Ctrl-D. We want this in babashka's console REPL too.

Similar to completions, we already provide symbol lookup for the nREPL implementation (`:lookup`, `:info`, `:eldoc` ops in server.clj). We factor out the core metadata resolution into a reusable module.

### Implementation

#### Files Modified

1. **`babashka.nrepl/src/babashka/nrepl/impl/sci.clj`** - Lookup and `the-sci-ns` added to shared SCI helpers
2. **`babashka.nrepl/src/babashka/nrepl/impl/server.clj`** - Updated to use `sci-helpers/lookup` and `sci-helpers/the-sci-ns`
3. **`src/babashka/impl/repl.clj`** - Added doc-at-point widget with key binding

#### Key Design

**lookup.clj** provides:
- `(the-sci-ns ctx ns-sym)` - Resolves a SCI namespace
- `(lookup ctx sym-str :ns-str ns-str)` - Resolves var metadata (`:name`, `:ns`, `:arglists`, `:doc`, `:val`, `:file`)

**repl.clj** adds:
- `format-doc` - Formats metadata map as Clojure `(doc ...)` style output
- `doc-at-point-widget` - JLine Widget that gets word-at-cursor, looks up metadata, displays formatted doc
- `register-widgets` - Registers the widget and binds Ctrl-X Ctrl-D (emacs keymap)

**Display approach**: Uses `LineReaderImpl`'s `post` field (via reflection) to display documentation inline below the prompt, matching rebel-readline's behavior. Doc is colored: URL in faint blue, body in light yellow. Doc dismisses on the next keypress (self-insert, accept-line) but not on backspace.

#### Key Decisions

- Same factoring pattern as completions: extract core logic from nREPL server, reuse in console REPL
- Widget, Reference, LineReaderImpl imported only in repl.clj (internal use, not in classes.clj)
- Key binding matches rebel-readline: Ctrl-X Ctrl-D for doc-at-point
- For `clojure.*` vars, the clojuredocs.org URL replaces the `-------------------------` separator
- Colors match rebel-readline's dark theme: anchor (faint blue 39), doc text (yellow 222), separator (gray 243)
- REPL init message updated to mention the Ctrl-X Ctrl-D shortcut

## Newline Insertion

**Alt-Enter** inserts a literal newline into the buffer without evaluating. This is JLine's built-in `SELF_INSERT_UNMETA` widget (bound to `alt(ctrl('M'))` by default). Useful for editing multi-line expressions — e.g., positioning the cursor between `{}` and pressing Alt-Enter to insert a blank line.

Note: Shift-Enter is indistinguishable from Enter in most terminals (both send `0x0d`). Only Alt-Enter sends a distinct sequence (`ESC + CR`).

## Exit Behavior

Following Node.js REPL conventions:

- **Ctrl-D on empty line**: exits immediately (JLine only fires `EndOfFileException` when buffer is empty)
- **Ctrl-D on non-empty line**: does nothing (JLine treats it as delete-char, which is a no-op at end of buffer)
- **Ctrl-D at column 0 of continuation line**: does nothing (known difference from Node.js, which exits; would require a custom widget to override JLine's default delete-char binding)
- **Ctrl-C on empty prompt**: first press shows warning, second consecutive press exits
- **Ctrl-C with partial input**: clears input, continues
- **`:repl/exit` or `:repl/quit`**: exits

### Testing

#### Manual
1. Build: `script/uberjar && script/compile`
2. Start REPL: `./bb`
3. Test docstrings:
   - Type `map` then press Ctrl-X Ctrl-D - should show map's documentation above the prompt
   - Type `str/join` then press Ctrl-X Ctrl-D - should show clojure.string/join docs
   - Cursor on non-symbol (e.g. after `(`) - should do nothing

#### Automated
- Unit tests for `format-doc` in `test/babashka/impl/repl_test.clj`

## Eldoc (automatic argument help)

### Proposal

Eldoc automatically shows function signatures as you type. When the cursor is inside a function call like `(map |...)`, the arglists are displayed below the prompt via JLine's `post` field. Updates on every keystroke.

### Implementation

#### Enclosing function detection: `enclosing-fn`

Uses a regex approach to find the innermost unclosed `(`:
1. Take text before cursor
2. Repeatedly strip balanced paren pairs with `\([^()]*\)` regex
3. Last remaining `(` is the innermost unclosed one
4. Extract the function name after it, only if cursor is past the name

#### Display

One-line format matching rebel-readline's eldoc style:
```
clojure.core/map: ([f] [f coll] [f c1 c2] [f c1 c2 c3] [f c1 c2 c3 & colls])
```

Colors (rebel-readline dark theme): namespace faint blue 123, var name faint yellow 178, separator/arglists gray 243.

#### Trigger mechanism

Wraps JLine's `self-insert` and `backward-delete-char` widgets. After each keystroke:
- If enclosing function found with arglists → set `post` to eldoc supplier
- Otherwise → set `post` to null

Lookup results are cached by function name to avoid repeated SCI evaluation.

#### Key Decisions

- Regex-based paren matching is ~8 lines and handles nesting correctly via iterative stripping. Known limitation: parens inside string literals can confuse matching (rare in REPL usage).
- Widget wrapping (not a persistent supplier) avoids blank-line issue when JLine's `post.get()` returns null/empty (JLine prepends `"\n"` before post content).
- Only `self-insert` and `backward-delete-char` are wrapped; other editing operations leave the last eldoc in place.

### Testing

#### Manual
1. Build: `script/uberjar && script/compile`
2. Start REPL: `./bb`
3. Type `(map ` → should show arglists below prompt
4. Type `(str/join ` → should show join's arglists
5. Backspace past fn name → eldoc clears

#### Automated
- Unit tests for `enclosing-fn` in `test/babashka/impl/repl_test.clj`

## Inline Autosuggestion (ghost text)

### Proposal

Node.js-style ghost text: as you type, show the common completion prefix beyond what's typed as faint/grey text inline after the cursor. Press TAB to accept.

### Implementation

#### Tail tip computation: `compute-tail-tip`

Public, pure function. Given the typed word and list of candidate strings:
1. Filter candidates to those starting with the typed word (avoids pollution from fully-qualified names like `java.lang.String` when typing `Stri`)
2. Compute `common-prefix` of filtered candidates
3. Return the suffix beyond the typed word, or `""` if no extension

#### Display mechanism

Uses JLine's `SuggestionType/TAIL_TIP` mode with `LineReaderImpl.setTailTip(String)`. The tail tip renders as faint text inline after the cursor.

#### Trigger

Same widget wrappers as eldoc — `self-insert` and `backward-delete-char` call `update-tail-tip` after each keystroke. `EXPAND_OR_COMPLETE` (TAB) clears the tail tip *before* the widget runs (pre-clear) to prevent stale ghost text from appearing during JLine's internal `redisplay()`, then recomputes after.

#### Ctrl+C clearing

The `UserInterruptException` handler in `jline-read` clears the tail tip so ghost text doesn't leak into the next prompt.

#### Key Decisions

- `SuggestionType/TAIL_TIP` (not `COMPLETER`) — `COMPLETER` mode was too aggressive, showing the full completion list inline
- TAB is bound to `EXPAND_OR_COMPLETE` in JLine's emacs keymap (not `COMPLETE_WORD` or `MENU_COMPLETE`)
- Pre-clear is required for the completion widget because JLine calls `redisplay()` internally before our after-hook runs
- Filtering candidates by `str/starts-with?` is essential — without it, fully-qualified class names (e.g. `java.lang.String`) break the common prefix for short-name completions (e.g. `Stri` → `String`)

### Testing

#### Manual
1. Build: `script/uberjar && script/compile`
2. Start REPL: `./bb`
3. Type `get-i` → ghost text `n` appears (for `get-in`)
4. Type `Stri` → ghost text `ng` appears (for `String`)
5. Press TAB → completes, ghost text clears cleanly
6. Press Ctrl+C → ghost text clears

#### Automated
- Unit tests for `common-prefix` and `compute-tail-tip` in `test/babashka/impl/repl_test.clj`

## Startup Banner

Two plain text lines printed to stderr:
```
Babashka v1.12.215-SNAPSHOT
Type :repl/help for help
```

## Known Limitations

- **Ghost text only at end of buffer**: JLine's `TAIL_TIP` rendering requires `buf.length() == buf.cursor()` (LineReaderImpl.java:4217). When editing in the middle of a multi-line expression (e.g., cursor on line 2 of `{\n(frequ\n}`), the cursor is not at the end of the buffer and ghost text is not rendered. Fixing this would require patching JLine or implementing custom ghost text rendering.
- **No Shift-Enter support**: Most terminals send the same byte (`0x0d`) for both Enter and Shift-Enter, making them indistinguishable. The [kitty keyboard protocol](https://sw.kovidgoyal.net/kitty/keyboard-protocol/) solves this by sending distinct CSI u escape sequences for modified keys (e.g. `\033[13;2u` for Shift-Enter), but JLine doesn't support it yet ([jline/jline3#1217](https://github.com/jline/jline3/issues/1217)). Enabling the protocol without JLine support breaks arrow keys and other keys. **Alt-Enter** works as an alternative for inserting newlines on macOS/Linux (JLine's built-in `SELF_INSERT_UNMETA`), but on Windows Terminal Alt-Enter is bound to fullscreen toggle.

## TODO

- Expose the JLine interop used by the console REPL (Completer, Candidate, Widget, Parser, ParsedLine, CompletingParsedLine, EOFError, Reference, LineReaderImpl, AttributedStringBuilder, AttributedStyle, KeyMap, etc.) in `classes.clj` so bb scripts can build custom JLine-based tooling themselves.
- Investigate loading rebel-readline from source with bb. Many JLine classes rebel-readline needs (Highlighter, Completer, Candidate, Parser, DefaultParser, Widget, LineReader$Option, DumbTerminal, Attributes$LocalFlag, etc.) are on the classpath but not in bb's class map. Adding these to `classes.clj` could make it possible to run rebel-readline as a bb script/library.

rebel-readline, a clojure REPL based on jline, works as follows in multi-line expressions:

  It sets "SECONDARY_PROMPT_PATTERN" to "%P #_=> ". When you enter an incomplete expression, it looks like this:

  user=> {
    #_=>  :a 1
    #_=>  :b 2
    #_=>  }
  {:a 1, :b 2}

  The rebel-readline project is in ~/dev/rebel-readline

  We implemented a similar REPL for babashka in src/babashka/impl/repl.clj but it doesn't indent with the one space that rebel-read-line does. Where does
   this difference comes from?


Question: should we indent Clojure code the way rebel-readline does it?

In rebel-readline's line_reader.clj:894-922, the parser has an indent function that gets called when an incomplete line is detected:

  (defn indent [line-reader line cursor]
    ;; ...
    (.runMacro line-reader (str (KeyMap/ctrl \X) (KeyMap/ctrl \I))))

  And in the parser at line 911-918:

  (= context Parser$ParseContext/ACCEPT_LINE)
  (when-not (or (and *accept-fn*
                     (*accept-fn* line cursor))
                (accept-line line cursor))
    (indent *line-reader* line cursor)    ;; <-- This is the key!
    (throw (EOFError. -1 -1 "Unbalanced Expression" (str *ns*))))

  When a form is incomplete, rebel-readline:
  1. Calls indent which runs the macro Ctrl+X Ctrl+I
  2. This triggers the clojure-indent-line widget (indent-line-widget at line 457-476)
  3. That widget uses cljfmt's reformat-string to calculate proper indentation
  4. Then throws EOFError for the secondary prompt

  In babashka's implementation (repl.clj:152-160), the parser just throws EOFError without any indentation logic:

  (defn- jline-parser [sci-ctx]
    (reify Parser
      (^ParsedLine parse [_this ^String line ^int cursor ^Parser$ParseContext _context]
       (if (complete-form? sci-ctx line)
         (parsed-line line cursor)
         (throw (EOFError. -1 -1 "Incomplete Clojure form"))))))

  The single-space indent you see in rebel-readline comes from the indent-amount function (line 439-455) which uses cljfmt to format the code and calculate
  proper indentation for continuation lines.

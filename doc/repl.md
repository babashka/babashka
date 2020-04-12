# Running a REPL

Babashka supports running a REPL, a socket REPL and an nREPL server.

## REPL

To start the REPL, type:

``` shell
$ bb --repl
```

To get history with up and down arrows, use `rlwrap`:

``` shell
$ rlwrap bb --repl
```

## Socket REPL

To start the socket REPL you can do this:

``` shellsession
$ bb --socket-repl 1666
Babashka socket REPL started at localhost:1666
```

Now you can connect with your favorite socket REPL client:

``` shellsession
$ rlwrap nc 127.0.0.1 1666
Babashka v0.0.14 REPL.
Use :repl/quit or :repl/exit to quit the REPL.
Clojure rocks, Bash reaches.

bb=> (+ 1 2 3)
6
bb=> :repl/quit
$
```

Editor plugins known to work with a babashka socket REPL:

- Emacs: [inf-clojure](https://github.com/clojure-emacs/inf-clojure):

  To connect:

  `M-x inf-clojure-connect <RET> localhost <RET> 1666`

  Before evaluating from a Clojure buffer:

  `M-x inf-clojure-minor-mode`

- Atom: [Chlorine](https://github.com/mauricioszabo/atom-chlorine)
- Vim: [vim-iced](https://github.com/liquidz/vim-iced)
- IntelliJ IDEA: [Cursive](https://cursive-ide.com/)

  Note: you will have to use a workaround via
  [tubular](https://github.com/mfikes/tubular). For more info, look
  [here](https://cursive-ide.com/userguide/repl.html#repl-types).


## nREPL

To start an nREPL server:

``` shell
$ bb --nrepl-server 1667
```

Then connect with your favorite nREPL client:

``` shell
$ lein repl :connect 1667
Connecting to nREPL at 127.0.0.1:1667
user=> (+ 1 2 3)
6
user=>
```

Editor plugins known to work with the babashka nREPL server:

  - Emacs: [CIDER](https://docs.cider.mx/cider-nrepl/)
  - `lein repl :connect`
  - VSCode: [Calva](http://calva.io/)
  - Atom: [Chlorine](https://github.com/mauricioszabo/atom-chlorine)
  - (Neo)Vim: [vim-iced](https://github.com/liquidz/vim-iced), [conjure](https://github.com/Olical/conjure), [fireplace](https://github.com/tpope/vim-fireplace)

The babashka nREPL server does not write an `.nrepl-port` file at startup, but
you can easily write a script that launches the server and write the file
yourself:

 ``` clojure
 #!/usr/bin/env bb

(import [java.net ServerSocket]
        [java.io File]
        [java.lang ProcessBuilder$Redirect])

(require '[babashka.wait :as wait])

(let [nrepl-port (with-open [sock (ServerSocket. 0)] (.getLocalPort sock))
      pb (doto (ProcessBuilder. (into ["bb" "--nrepl-server" (str nrepl-port)]
                                      *command-line-args*))
           (.redirectOutput ProcessBuilder$Redirect/INHERIT))
      proc (.start pb)]
  (wait/wait-for-port "localhost" nrepl-port)
  (spit ".nrepl-port" nrepl-port)
  (.deleteOnExit (File. ".nrepl-port"))
  (.waitFor proc))
 ```

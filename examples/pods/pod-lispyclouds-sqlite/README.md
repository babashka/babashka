# pod-lispyclouds-sqlite

To run this:

- Install python3 and sqlite3
- Create a virtualenv: `python3 -m venv ~/.virtualenvs/babashka`
- Switch to it: `source ~/.virtualenvs/babashka/bin/activate`
- Run: `pip install bcoding` to install the bencode lib
- Create a new db: `sqlite3 /tmp/babashka.db "create table foo (foo int);"`

Then run as pod:

``` clojure
(babashka.pods/load-pod ["./pod-lispyclouds-sqlite.py"])
(require '[pod.lispyclouds.sqlite :as sqlite])
(sqlite/execute! "create table if not exists foo ( int foo )")
(sqlite/execute! "delete from foo")
(sqlite/execute! "insert into foo values (1), (2)")
(sqlite/execute! "select * from foo") ;;=> ([1] [2])
```

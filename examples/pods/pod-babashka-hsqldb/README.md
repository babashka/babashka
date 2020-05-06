# pod-babashka-hsqldb

## Compile

Run `./compile`

## Run

``` clojure
(babashka.pods/load-pod "pods/pod-babashka-hsqldb/pod-babashka-hsqldb")
(pod.hsqldb/execute! "jdbc:hsqldb:mem:testdb;sql.syntax_mys=true" ["create table foo ( foo int );"])'
```

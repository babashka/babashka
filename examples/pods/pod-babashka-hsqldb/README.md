# pod-babashka-hsqldb

## Compile

Run `./compile`

## Run

``` clojure
(babashka.pods/load-pod "./pod-babashka-hsqldb")
(pod.babashka.hsqldb/execute! "jdbc:hsqldb:mem:testdb;sql.syntax_mys=true" ["create table foo ( foo int );"])'
;;=> [#:next.jdbc{:update-count 0}]
```

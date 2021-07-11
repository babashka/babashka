if "%BABASHKA_TEST_ENV%" EQU "native" (set BB_CMD=.\bb) else (set BB_CMD=lein bb)

for /f %%i in ('.\bb clojure -A:lib-tests -Spath') do set BABASHKA_CLASSPATH=%%i

%BB_CMD% -cp "%BABASHKA_CLASSPATH%;test-resources/lib_tests" -f test-resources/lib_tests/babashka/run_all_libtests.clj %*

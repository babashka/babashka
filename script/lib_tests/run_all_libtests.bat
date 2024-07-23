if not defined BABASHKA_TEST_ENV set BABASHKA_TEST_ENV=jvm

if "%BABASHKA_TEST_ENV%" EQU "native" (set BB_CMD=.\bb) else (set BB_CMD=lein bb)

set EDN=lib_tests.edn

set PATH=%CD%\process\target\test\on-path;%PATH%

.\bb -f script/lib_tests/bb_edn_from_deps.clj %EDN%

%BB_CMD% --config %EDN% --deps-root . -f test-resources/lib_tests/babashka/run_all_libtests.clj %*

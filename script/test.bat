if "%GRAALVM_HOME%"=="" (
echo Please set GRAALVM_HOME
exit /b
)

echo "BABASHKA_TEST_ENV: %BABASHKA_TEST_ENV%"

set JAVA_HOME=%GRAALVM_HOME%
set PATH=%GRAALVM_HOME%\bin;%PATH%

set BABASHKA_PRELOADS=
set BABASHKA_CLASSPATH=
set BABASHKA_PRELOADS_TEST=
set BABASHKA_CLASSPATH_TEST=
set BABASHKA_POD_TEST=
set BABASHKA_SOCKET_REPL_TEST=

echo "running tests part 1"
call lein do clean, test %* || exit /B 1

echo "running flaky tests"
REM there's no "or exit" here because we don't want flaky tests to fail the script
call lein do clean, test :flaky

set BABASHKA_PRELOADS=(defn __bb__foo [] "foo") (defn __bb__bar [] "bar")
set BABASHKA_PRELOADS_TEST=true
echo "running tests part 2"
call lein test :only babashka.main-test/preloads-test || exit /B 1

set BABASHKA_PRELOADS=(defn ithrow [] (/ 1 0))
set BABASHKA_PRELOADS_TEST=true
echo "running tests part 3"
call lein test :only babashka.main-test/preloads-file-location-test || exit /B 1

set BABASHKA_PRELOADS=(require '[env-ns])
set BABASHKA_CLASSPATH_TEST=true
set BABASHKA_CLASSPATH=test-resources/babashka/src_for_classpath_test/env
echo "running tests part 4"
call lein test :only babashka.classpath-test/classpath-env-test || exit /B 1

set BABASHKA_POD_TEST=true
call lein test :only babashka.pod-test || exit /B 1

set BABASHKA_SOCKET_REPL_TEST=true
call lein test :only babashka.impl.socket-repl-test || exit /B 1

set BABASHKA_PRELOADS=
set BABASHKA_CLASSPATH=
set BABASHKA_PRELOADS_TEST=
set BABASHKA_CLASSPATH_TEST=
set BABASHKA_POD_TEST=
set BABASHKA_SOCKET_REPL_TEST=

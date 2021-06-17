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

echo "running tests part 1"
call lein do clean, test :windows

set BABASHKA_PRELOADS=(defn __bb__foo [] "foo") (defn __bb__bar [] "bar")
set BABASHKA_PRELOADS_TEST=true
echo "running tests part 2"
call lein test :only babashka.main-test/preloads-test

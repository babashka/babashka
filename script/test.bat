if "%GRAALVM_HOME%"=="" (
echo Please set GRAALVM_HOME
exit /b
)

echo "BABASHKA_TEST_ENV: %BABASHKA_TEST_ENV%"

set JAVA_HOME=%GRAALVM_HOME%
set PATH=%GRAALVM_HOME%\bin;%PATH%

call lein do clean, test

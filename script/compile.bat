@echo off

Rem set GRAALVM_HOME=C:\Users\IEUser\Downloads\graalvm-ce-java8-19.3.1
Rem set PATH=%PATH%;C:\Users\IEUser\bin

if "%GRAALVM_HOME%"=="" (
    echo Please set GRAALVM_HOME
    exit /b
)

set JAVA_HOME=%GRAALVM_HOME%
set PATH=%GRAALVM_HOME%\bin;%PATH%

set /P BABASHKA_VERSION=< resources\BABASHKA_VERSION
echo Building Babashka %BABASHKA_VERSION%

Rem the --no-server option is not supported in GraalVM Windows.
Rem -H:EnableURLProtocols=jar,http,https is also not supported.

call %GRAALVM_HOME%\bin\gu.cmd install native-image

if "%BABASHKA_SHA%"=="" (
    for /f %%i in ('git rev-parse HEAD') do set sha=%%i
    if not errorlevel 1 (
        set BABASHKA_SHA=%sha%
    )
)

Rem babashka.ffi calls struct-by-value functions through libffi. Build the
Rem archive with script\setup-libffi.bat. script\uberjar.bat must see the
Rem same variable: it puts the @CFunction bindings that need these symbols
Rem on the classpath.
set "LIBFFI_ARG="
if not "%BABASHKA_LIBFFI%"=="" set "LIBFFI_ARG=-H:NativeLinkerOption=/WHOLEARCHIVE:%BABASHKA_LIBFFI%"

call %GRAALVM_HOME%\bin\native-image.cmd ^
  "-jar" "target/babashka-%BABASHKA_VERSION%-standalone.jar" ^
  "-H:Name=bb" ^
  "-H:+ReportExceptionStackTraces" ^
  "--verbose" ^
  "--no-fallback" ^
  "--install-exit-handlers" ^
  %LIBFFI_ARG% ^
  %*

if %errorlevel% neq 0 exit /b %errorlevel%

call bb "(+ 1 2 3)"
call bb describe

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

Rem Select the libffi archive. script\uberjar.bat must use the same setting.
set "LIBFFI_ARG="
if "%BABASHKA_LIBFFI%"=="none" goto :libffi_done
set "LIBFFI_LIB=%BABASHKA_LIBFFI%"
if "%LIBFFI_LIB%"=="" (
  for /f "usebackq delims=" %%i in (`call script\setup-libffi.bat`) do set "LIBFFI_LIB=%%i"
)
if "%LIBFFI_LIB%"=="" (
  if defined CI (
    echo compile.bat: script\setup-libffi.bat produced no archive, and a CI build links libffi 1>&2
    exit /b 1
  )
  echo compile.bat: script\setup-libffi.bat produced no archive, building without libffi 1>&2
  echo compile.bat: set BABASHKA_LIBFFI to a library to link, or to none to skip this attempt 1>&2
  goto :libffi_done
)
Rem quoted as a whole: a vcpkg under Program Files has a space in its path
set LIBFFI_ARG="-H:NativeLinkerOption=/WHOLEARCHIVE:%LIBFFI_LIB%"
:libffi_done
Rem Pass the feature setting to image initialization.
Rem "if defined", not a string compare: the value carries its own quotes
if defined LIBFFI_ARG (set BABASHKA_FEATURE_LIBFFI=true) else (set BABASHKA_FEATURE_LIBFFI=false)

call %GRAALVM_HOME%\bin\native-image.cmd ^
  "-jar" "target/babashka-%BABASHKA_VERSION%-standalone.jar" ^
  "-H:Name=bb" ^
  "-H:+ReportExceptionStackTraces" ^
  "--verbose" ^
  "--no-fallback" ^
  "--install-exit-handlers" ^
  %LIBFFI_ARG% ^
  -EBABASHKA_FEATURE_LIBFFI ^
  %*

if %errorlevel% neq 0 exit /b %errorlevel%

call bb "(+ 1 2 3)"
call bb describe

@echo on

if "%GRAALVM_HOME%"=="" (
  echo Please set GRAALVM_HOME
  exit /b
)

set JAVA_HOME=%GRAALVM_HOME%
set PATH=%PATH%;%GRAALVM_HOME%\bin

set BABASHKA_LEIN_PROFILES=+uberjar

if "%BABASHKA_FEATURE_JDBC%"=="true" (
  set BABASHKA_LEIN_PROFILES=,+feature/jdbc
) else (
  set BABASHKA_LEIN_PROFILES=,-feature/jdbc
)

if "%BABASHKA_FEATURE_POSTGRESQL%"=="true"
  set BABASHKA_LEIN_PROFILES=,+feature/postgresql
) else (
  set BABASHKA_LEIN_PROFILES=,-feature/postgresql
)

if "%BABASHKA_FEATURE_HSQLDB%"=="true" (
  set BABASHKA_LEIN_PROFILES=,+feature/hsqldb
) else (
  set BABASHKA_LEIN_PROFILES=,-feature/hsqldb
)

if not "%BABASHKA_FEATURE_XML%"=="false" (
  set BABASHKA_LEIN_PROFILES=%BABASHKA_LEIN_PROFILES%,+feature/xml
) else (
  set BABASHKA_LEIN_PROFILES=%BABASHKA_LEIN_PROFILES%,-feature/xml
)

if not "%BABASHKA_FEATURE_YAML%"=="false" (
set BABASHKA_LEIN_PROFILES=%BABASHKA_LEIN_PROFILES%,+feature/yaml
) else (
set BABASHKA_LEIN_PROFILES=%BABASHKA_LEIN_PROFILES%,-feature/yaml
)

call lein with-profiles %BABASHKA_LEIN_PROFILES% bb "(+ 1 2 3)"

call lein with-profiles %BABASHKA_LEIN_PROFILES%,+reflection,-uberjar do run
if %errorlevel% neq 0 exit /b %errorlevel%

call lein with-profiles "%BABASHKA_LEIN_PROFILES%" do clean, uberjar
if %errorlevel% neq 0 exit /b %errorlevel%

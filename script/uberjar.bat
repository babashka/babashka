@echo on

if "%GRAALVM_HOME%"=="" (
  echo Please set GRAALVM_HOME
  exit /b
)

set JAVA_HOME=%GRAALVM_HOME%
set PATH=%GRAALVM_HOME%\bin;%PATH%

set BABASHKA_LEIN_PROFILES=+uberjar

if "%BABASHKA_FEATURE_JDBC%"=="true" (
  set BABASHKA_LEIN_PROFILES=%BABASHKA_LEIN_PROFILES%,+feature/jdbc
) else (
  set BABASHKA_LEIN_PROFILES=%BABASHKA_LEIN_PROFILES%,-feature/jdbc
)

if "%BABASHKA_FEATURE_POSTGRESQL%"=="true" (
  set BABASHKA_LEIN_PROFILES=%BABASHKA_LEIN_PROFILES%,+feature/postgresql
) else (
  set BABASHKA_LEIN_PROFILES=%BABASHKA_LEIN_PROFILES%,-feature/postgresql
)

if "%BABASHKA_FEATURE_HSQLDB%"=="true" (
  set BABASHKA_LEIN_PROFILES=%BABASHKA_LEIN_PROFILES%,+feature/hsqldb
) else (
  set BABASHKA_LEIN_PROFILES=%BABASHKA_LEIN_PROFILES%,-feature/hsqldb
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

if not "%BABASHKA_FEATURE_CORE_ASYNC%"=="false" (
  set BABASHKA_LEIN_PROFILES=%BABASHKA_LEIN_PROFILES%,+feature/core-async
) else (
  set BABASHKA_LEIN_PROFILES=%BABASHKA_LEIN_PROFILES%,-feature/core-async
)

if not "%BABASHKA_FEATURE_CSV%"=="false" (
  set BABASHKA_LEIN_PROFILES=%BABASHKA_LEIN_PROFILES%,+feature/csv
) else (
  set BABASHKA_LEIN_PROFILES=%BABASHKA_LEIN_PROFILES%,-feature/csv
)

if not "%BABASHKA_FEATURE_TRANSIT%"=="false" (
  set BABASHKA_LEIN_PROFILES=%BABASHKA_LEIN_PROFILES%,+feature/transit
) else (
  set BABASHKA_LEIN_PROFILES=%BABASHKA_LEIN_PROFILES%,-feature/transit
)

if "%BABASHKA_FEATURE_DATASCRIPT%"=="true" (
  set BABASHKA_LEIN_PROFILES=%BABASHKA_LEIN_PROFILES%,+feature/datascript
) else (
  set BABASHKA_LEIN_PROFILES=%BABASHKA_LEIN_PROFILES%,-feature/datascript
)

call lein with-profiles %BABASHKA_LEIN_PROFILES% bb "(+ 1 2 3)"

call lein with-profiles %BABASHKA_LEIN_PROFILES%,+reflection,-uberjar do run
if %errorlevel% neq 0 exit /b %errorlevel%

call lein with-profiles "%BABASHKA_LEIN_PROFILES%,+native-image" do clean, uberjar
if %errorlevel% neq 0 exit /b %errorlevel%

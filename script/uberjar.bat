@echo on

if not "%GRAALVM_HOME%"=="" (
  set "JAVA_HOME=%GRAALVM_HOME%"
  set "PATH=%GRAALVM_HOME%\bin;%PATH%"
)

set BABASHKA_LEIN_PROFILES=+uberjar

if "%BABASHKA_FEATURE_JDBC%"=="true" (
  set BABASHKA_LEIN_PROFILES=%BABASHKA_LEIN_PROFILES%,+feature/jdbc
) else (
  set BABASHKA_LEIN_PROFILES=%BABASHKA_LEIN_PROFILES%,-feature/jdbc
)

if "%BABASHKA_FEATURE_SQLITE%"=="true" (
  set BABASHKA_LEIN_PROFILES=%BABASHKA_LEIN_PROFILES%,+feature/sqlite
) else (
  set BABASHKA_LEIN_PROFILES=%BABASHKA_LEIN_PROFILES%,-feature/sqlite
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

if "%BABASHKA_FEATURE_ORACLEDB%"=="true" (
  set BABASHKA_LEIN_PROFILES=%BABASHKA_LEIN_PROFILES%,+feature/oracledb
) else (
  set BABASHKA_LEIN_PROFILES=%BABASHKA_LEIN_PROFILES%,-feature/oracledb
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

if not "%BABASHKA_FEATURE_HTTPKIT_CLIENT%"=="false" (
set BABASHKA_LEIN_PROFILES=%BABASHKA_LEIN_PROFILES%,+feature/httpkit-client
) else (
set BABASHKA_LEIN_PROFILES=%BABASHKA_LEIN_PROFILES%,-feature/httpkit-client
)

if not "%BABASHKA_FEATURE_HTTPKIT_SERVER%"=="false" (
set BABASHKA_LEIN_PROFILES=%BABASHKA_LEIN_PROFILES%,+feature/httpkit-server
) else (
set BABASHKA_LEIN_PROFILES=%BABASHKA_LEIN_PROFILES%,-feature/httpkit-server
)

if "%BABASHKA_FEATURE_LANTERNA%"=="true" (
  set BABASHKA_LEIN_PROFILES=%BABASHKA_LEIN_PROFILES%,+feature/lanterna
) else (
  set BABASHKA_LEIN_PROFILES=%BABASHKA_LEIN_PROFILES%,-feature/lanterna
)

if not "%BABASHKA_FEATURE_CORE_MATCH%"=="false" (
set BABASHKA_LEIN_PROFILES=%BABASHKA_LEIN_PROFILES%,+feature/core-match
) else (
set BABASHKA_LEIN_PROFILES=%BABASHKA_LEIN_PROFILES%,-feature/core-match
)

if not "%BABASHKA_FEATURE_HICCUP%"=="false" (
set BABASHKA_LEIN_PROFILES=%BABASHKA_LEIN_PROFILES%,+feature/hiccup
) else (
set BABASHKA_LEIN_PROFILES=%BABASHKA_LEIN_PROFILES%,-feature/hiccup
)

if not "%BABASHKA_FEATURE_TEST_CHECK%"=="false" (
set BABASHKA_LEIN_PROFILES=%BABASHKA_LEIN_PROFILES%,+feature/test-check
) else (
set BABASHKA_LEIN_PROFILES=%BABASHKA_LEIN_PROFILES%,-feature/test-check
)

if "%BABASHKA_FEATURE_SPEC_ALPHA%"=="true" (
set BABASHKA_LEIN_PROFILES=%BABASHKA_LEIN_PROFILES%,+feature/spec-alpha
) else (
set BABASHKA_LEIN_PROFILES=%BABASHKA_LEIN_PROFILES%,-feature/spec-alpha
)

if not "%BABASHKA_FEATURE_SELMER%"=="false" (
set BABASHKA_LEIN_PROFILES=%BABASHKA_LEIN_PROFILES%,+feature/selmer
) else (
set BABASHKA_LEIN_PROFILES=%BABASHKA_LEIN_PROFILES%,-feature/selmer
)

if not "%BABASHKA_FEATURE_LOGGING%"=="false" (
set BABASHKA_LEIN_PROFILES=%BABASHKA_LEIN_PROFILES%,+feature/logging
) else (
set BABASHKA_LEIN_PROFILES=%BABASHKA_LEIN_PROFILES%,-feature/logging
)

if not "%BABASHKA_FEATURE_PRIORITY_MAP%"=="false" (
set BABASHKA_LEIN_PROFILES=%BABASHKA_LEIN_PROFILES%,+feature/priority-map
) else (
set BABASHKA_LEIN_PROFILES=%BABASHKA_LEIN_PROFILES%,-feature/priority-map
)

if not "%BABASHKA_FEATURE_RRB_VECTOR%"=="false" (
set BABASHKA_LEIN_PROFILES=%BABASHKA_LEIN_PROFILES%,+feature/rrb-vector
) else (
set BABASHKA_LEIN_PROFILES=%BABASHKA_LEIN_PROFILES%,-feature/rrb-vector
)

call lein with-profiles %BABASHKA_LEIN_PROFILES% bb "(+ 1 2 3)"

call lein with-profiles %BABASHKA_LEIN_PROFILES%,+reflection,-uberjar do run
if %errorlevel% neq 0 exit /b %errorlevel%

call lein with-profiles "%BABASHKA_LEIN_PROFILES%,+native-image" do clean, uberjar
if %errorlevel% neq 0 exit /b %errorlevel%

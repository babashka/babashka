@echo on

if "%GRAALVM_HOME%"=="" (
echo Please set GRAALVM_HOME
exit /b
)

set JAVA_HOME="%GRAALVM_HOME%"
set PATH="PATH%;%GRAALVM_HOME%\bin"

if "%BABASHKA_FEATURE_HSQLDB%"=="true"
(set BABASHKA_LEIN_PROFILES="+feature/hsqldb")
else (set BABASHKA_LEIN_PROFILES="-feature/hsqldb")

if not "%BABASHKA_FEATURE_XML%"=="false"
(set BABASHKA_LEIN_PROFILES="%BABASHKA_LEIN_PROFILES%,+feature/xml")
else (set BABASHKA_LEIN_PROFILES="%BABASHKA_LEIN_PROFILES%,-feature/xml")

call lein with-profiles "%BABASHKA_LEIN_PROFILES%" bb "(+ 1 2 3)"

call lein with-profiles "+reflection,%BABASHKA_LEIN_PROFILES%" do run
if %errorlevel% neq 0 exit /b %errorlevel%

call lein with-profiles "%BABASHKA_LEIN_PROFILES%" do clean, uberjar
if %errorlevel% neq 0 exit /b %errorlevel%

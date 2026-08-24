@echo off

Rem Installs a static libffi through vcpkg and prints the path of the
Rem library. babashka.ffi calls struct-by-value functions through it, so
Rem builds that want those link it in with BABASHKA_LIBFFI. See
Rem script\uberjar.bat and script\compile.bat.
Rem
Rem The GitHub Actions windows runners ship vcpkg and set
Rem VCPKG_INSTALLATION_ROOT. Everything vcpkg prints goes to stderr, so
Rem stdout carries the path and nothing else.

setlocal

if "%VCPKG_ROOT%"=="" set "VCPKG_ROOT=%VCPKG_INSTALLATION_ROOT%"

if "%VCPKG_ROOT%"=="" (
  echo setup-libffi: set VCPKG_ROOT or VCPKG_INSTALLATION_ROOT to a vcpkg checkout 1>&2
  exit /b 1
)

Rem static-md is a static libffi against the dynamic C runtime, which is the
Rem runtime native-image links the image with. babashka.ffi assumes an
Rem MSVC-built libffi for its ABI constant, see default-abi in
Rem src\babashka\ffi.clj.
if "%LIBFFI_TRIPLET%"=="" set "LIBFFI_TRIPLET=x64-windows-static-md"

call "%VCPKG_ROOT%\vcpkg.exe" install libffi:%LIBFFI_TRIPLET% 1>&2
if errorlevel 1 exit /b 1

set "LIBDIR=%VCPKG_ROOT%\installed\%LIBFFI_TRIPLET%\lib"

for %%f in ("%LIBDIR%\*ffi*.lib") do (
  echo %%~ff
  exit /b 0
)

echo setup-libffi: no libffi .lib under %LIBDIR% 1>&2
exit /b 1

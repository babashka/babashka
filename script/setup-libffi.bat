@echo off

Rem Installs a static libffi through vcpkg and prints its path.
Rem
Rem GitHub Actions sets VCPKG_INSTALLATION_ROOT. vcpkg writes its output to
Rem stderr. Thus, stdout contains only the archive path.

setlocal

if "%VCPKG_ROOT%"=="" set "VCPKG_ROOT=%VCPKG_INSTALLATION_ROOT%"

if "%VCPKG_ROOT%"=="" (
  echo setup-libffi: set VCPKG_ROOT or VCPKG_INSTALLATION_ROOT to a vcpkg checkout 1>&2
  exit /b 1
)

Rem Use a static libffi with the dynamic C runtime that native-image uses.
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

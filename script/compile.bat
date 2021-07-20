@echo off

Rem set GRAALVM_HOME=C:\Users\IEUser\Downloads\graalvm-ce-java8-19.3.1
Rem set PATH=%PATH%;C:\Users\IEUser\bin

if "%GRAALVM_HOME%"=="" (
    echo Please set GRAALVM_HOME
    exit /b
)

if "%BABASHKA_XMX%"=="" (
    set BABASHKA_XMX="-J-Xmx4500m"
)

set JAVA_HOME=%GRAALVM_HOME%
set PATH=%GRAALVM_HOME%\bin;%PATH%

set /P BABASHKA_VERSION=< resources\BABASHKA_VERSION
echo Building Babashka %BABASHKA_VERSION%

Rem the --no-server option is not supported in GraalVM Windows.
Rem -H:EnableURLProtocols=jar,http,https is also not supported.

call %GRAALVM_HOME%\bin\gu.cmd install native-image

call %GRAALVM_HOME%\bin\native-image.cmd ^
  "-jar" "target/babashka-%BABASHKA_VERSION%-standalone.jar" ^
  "-H:Name=bb" ^
  "-H:+ReportExceptionStackTraces" ^
  "-J-Dclojure.spec.skip-macros=true" ^
  "-J-Dclojure.compiler.direct-linking=true" ^
  "-H:IncludeResources=BABASHKA_VERSION" ^
  "-H:IncludeResources=SCI_VERSION" ^
  "-H:ReflectionConfigurationFiles=reflection.json" ^
  "--initialize-at-build-time"  ^
  "--initialize-at-run-time=org.postgresql.sspi.SSPIClient" ^
  "--initialize-at-run-time=org.httpkit.client.ClientSslEngineFactory$SSLHolder" ^
  "-H:EnableURLProtocols=http,https,jar" ^
  "--enable-all-security-services" ^
  "-H:+JNI" ^
  "-H:Log=registerResource:" ^
  "--no-fallback" ^
  "--verbose" ^
  "-H:ServiceLoaderFeatureExcludeServices=javax.sound.sampled.spi.AudioFileReader" ^
  "-H:ServiceLoaderFeatureExcludeServices=javax.sound.midi.spi.MidiFileReader" ^
  "-H:ServiceLoaderFeatureExcludeServices=javax.sound.sampled.spi.MixerProvider" ^
  "-H:ServiceLoaderFeatureExcludeServices=javax.sound.sampled.spi.FormatConversionProvider" ^
  "-H:ServiceLoaderFeatureExcludeServices=javax.sound.sampled.spi.AudioFileWriter" ^
  "-H:ServiceLoaderFeatureExcludeServices=javax.sound.midi.spi.MidiDeviceProvider" ^
  "-H:ServiceLoaderFeatureExcludeServices=javax.sound.midi.spi.SoundbankReader" ^
  "-H:ServiceLoaderFeatureExcludeServices=javax.sound.midi.spi.MidiFileWriter" ^
  "-H:ServiceLoaderFeatureExcludeServices=java.awt.Toolkit" ^
  "-H:+AddAllCharsets" ^
  "%BABASHKA_XMX%"

if %errorlevel% neq 0 exit /b %errorlevel%

call bb "(+ 1 2 3)"

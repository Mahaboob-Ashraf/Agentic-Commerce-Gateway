@echo off
setlocal
set "BASE_DIR=%~dp0"
set "MAVEN_VERSION=3.9.11"
set "MAVEN_HOME=%BASE_DIR%.mvn\wrapper\dists\apache-maven-%MAVEN_VERSION%"
set "ARCHIVE=%BASE_DIR%.mvn\wrapper\dists\apache-maven-%MAVEN_VERSION%-bin.zip"
set "URL=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/%MAVEN_VERSION%/apache-maven-%MAVEN_VERSION%-bin.zip"

if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
  if not exist "%BASE_DIR%.mvn\wrapper\dists" mkdir "%BASE_DIR%.mvn\wrapper\dists"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; Invoke-WebRequest -UseBasicParsing -Uri '%URL%' -OutFile '%ARCHIVE%'"
  if errorlevel 1 exit /b 1
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; Expand-Archive -LiteralPath '%ARCHIVE%' -DestinationPath '%BASE_DIR%.mvn\wrapper\dists' -Force"
  if errorlevel 1 exit /b 1
)

call "%MAVEN_HOME%\bin\mvn.cmd" -f "%BASE_DIR%pom.xml" %*
exit /b %errorlevel%

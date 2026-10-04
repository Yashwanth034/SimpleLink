@echo off
setlocal
set VERSION=9.4.1
if "%GRADLE_USER_HOME%"=="" set GRADLE_USER_HOME=%USERPROFILE%\.gradle
set CACHE=%GRADLE_USER_HOME%\simplelink-bootstrap
set DIST=%CACHE%\gradle-%VERSION%
set ZIP=%CACHE%\gradle-%VERSION%-bin.zip
set URL=https://services.gradle.org/distributions/gradle-%VERSION%-bin.zip
if exist "%DIST%\bin\gradle.bat" goto run
if not exist "%CACHE%" mkdir "%CACHE%"
powershell -NoProfile -Command "Invoke-WebRequest -UseBasicParsing '%URL%' -OutFile '%ZIP%'"
powershell -NoProfile -Command "$expected=(Invoke-WebRequest -UseBasicParsing '%URL%.sha256').Content.Trim(); $actual=(Get-FileHash '%ZIP%' -Algorithm SHA256).Hash.ToLower(); if($expected -ne $actual){ throw 'Gradle checksum mismatch' }"
powershell -NoProfile -Command "Expand-Archive -Force '%ZIP%' '%CACHE%'"
:run
call "%DIST%\bin\gradle.bat" %*

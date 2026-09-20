@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "PACKAGE_TYPE=%~1"
if "%PACKAGE_TYPE%"=="" set "PACKAGE_TYPE=app-image"

if /I not "%PACKAGE_TYPE%"=="app-image" if /I not "%PACKAGE_TYPE%"=="exe" if /I not "%PACKAGE_TYPE%"=="msi" (
    echo Unsupported Windows package type: %PACKAGE_TYPE% 1>&2
    exit /b 2
)

if exist target\jpackage rmdir /s /q target\jpackage
call mvnw.cmd --batch-mode --no-transfer-progress -Pdesktop-package -DskipTests clean package
if errorlevel 1 exit /b %errorlevel%

set "APP_JAR="
for %%F in (target\tetris-*.jar) do (
    set "APP_JAR=%%F"
    goto :jar_found
)

:jar_found
if not defined APP_JAR (
    echo Tetris application JAR was not produced. 1>&2
    exit /b 1
)

if not exist target\jpackage\modules mkdir target\jpackage\modules
copy /y "%APP_JAR%" target\jpackage\modules\ >nul
if errorlevel 1 exit /b %errorlevel%

set /p APP_VERSION=<src\main\resources\VERSION
if /I "!APP_VERSION:~0,1!"=="v" set "APP_VERSION=!APP_VERSION:~1!"
if not exist target\jpackage\dist mkdir target\jpackage\dist

jpackage ^
  --type "%PACKAGE_TYPE%" ^
  --name Tetris ^
  --dest target\jpackage\dist ^
  --module-path target\jpackage\modules ^
  --module xyz.xuminghai.tetris/xyz.xuminghai.tetris.TetrisApplication ^
  --app-version "!APP_VERSION!"
if errorlevel 1 exit /b %errorlevel%

echo Created %PACKAGE_TYPE% under target\jpackage\dist

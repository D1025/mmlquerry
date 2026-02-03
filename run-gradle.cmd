@echo off
setlocal
cd /d "%~dp0mizar-stack" || exit /b 1
call gradlew.bat -q dependencyInsight --dependency jaxen --configuration runtimeClasspath
if errorlevel 1 exit /b %errorlevel%
call gradlew.bat -q dependencyInsight --dependency dom4j --configuration runtimeClasspath
if errorlevel 1 exit /b %errorlevel%
call gradlew.bat clean test
exit /b %errorlevel%

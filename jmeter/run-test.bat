@echo off
REM ===================== CONFIG (edit if needed) =====================
set "JMETER_HOME=D:\jmeter\apache-jmeter-5.6.3"
set THREADS=100
set RAMP=10
set DURATION=60
REM ==================================================================

set "DIR=%~dp0"
set "JTL=%DIR%result.jtl"
set "RPT=%DIR%report"

if not exist "%JMETER_HOME%\bin\jmeter.bat" (
  echo [ERROR] Cannot find: %JMETER_HOME%\bin\jmeter.bat
  echo Please edit JMETER_HOME in this .bat to your JMeter folder.
  pause
  exit /b 1
)

if exist "%RPT%" rmdir /s /q "%RPT%"
if exist "%JTL%" del /q "%JTL%"

echo.
echo Running: %THREADS% threads / ramp %RAMP%s / duration %DURATION%s
echo Target : GET http://localhost:8080/api/ai/shop/dish/list
echo.

call "%JMETER_HOME%\bin\jmeter.bat" -n -t "%DIR%dish-list.jmx" -l "%JTL%" -e -o "%RPT%" -Jthreads=%THREADS% -Jramp=%RAMP% -Jduration=%DURATION%

echo.
echo Done. Opening report: %RPT%\index.html
start "" "%RPT%\index.html"
pause

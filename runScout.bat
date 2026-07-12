@echo off
REM Any args passed to this bat are forwarded to ZenohJavaScoutAnt:
REM   [mode] [interval-ms] [roles-csv] [timeoutSeconds]
REM Examples:
REM   runScout.bat
REM   runScout.bat passive
REM   runScout.bat active 1000 router 30
java -cp "%~dp0dist\ZenohJavaAnt.jar" sample.nb.ant.zenoh.ZenohJavaScoutAnt %*

@echo off
REM Scout sample runner. Args forwarded to ZenohJavaScoutAnt:
REM   [mode] [interval-ms] [roles-csv] [timeoutSeconds]
REM Examples:
REM   runScout.bat
REM   runScout.bat passive
REM   runScout.bat active 1000 router 30
REM
REM Build first:  mvn -q package
REM (or plain compile is enough:  mvn -q compile)
java -cp "%~dp0target\classes;%~dp0target\*" sample.zenoh.ZenohJavaScoutAnt %*

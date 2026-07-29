@echo off
REM Subscriber sample runner. Args forwarded to ZenohJavaSub:
REM   [endpoint] [keyExpr] [timeoutSeconds]
REM Examples:
REM   runSub.bat
REM   runSub.bat tcp/[::1]:7447
REM   runSub.bat tcp/[::1]:7447 demo/** 30
REM
REM Build first:  mvn -q package
REM (or plain compile is enough:  mvn -q compile)
java -cp "%~dp0target\classes;%~dp0target\*" sample.zenoh.ZenohJavaSub %*

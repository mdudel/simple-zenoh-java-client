@echo off
REM Any args passed to this bat are forwarded to ZenohJavaSubAnt:
REM   [endpoint] [keyExpr] [timeoutSeconds]
REM Examples:
REM   runSub.bat
REM   runSub.bat tcp/[::1]:7447
REM   runSub.bat tcp/[::1]:7447 demo/** 30
java -cp "%~dp0dist\ZenohJavaAnt.jar" sample.nb.ant.zenoh.ZenohJavaSubAnt %*
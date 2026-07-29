@echo off
REM Publisher sample runner. Args forwarded to ZenohJavaPubAnt:
REM   [endpoint] [key] [message] [count] [interval-ms]
REM Examples:
REM   runPub.bat
REM   runPub.bat tcp/[::1]:7447 demo/hello "hi from pub" 5
REM
REM Build first:  mvn -q package
REM (or plain compile is enough:  mvn -q compile)
java -cp "%~dp0target\classes;%~dp0target\*" sample.zenoh.ZenohJavaPubAnt %*

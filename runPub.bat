@echo off
REM Args forwarded to ZenohJavaPubAnt.
REM Examples:
REM   runPub.bat
REM   runPub.bat tcp/[::1]:7447 demo/hello "hi from pub" 5
java -cp "%~dp0dist\ZenohJavaAnt.jar" sample.nb.ant.zenoh.ZenohJavaPubAnt %*

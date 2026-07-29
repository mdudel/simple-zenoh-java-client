@echo off
REM mTLS Subscriber sample runner - hard-wired to the GOAT NET onboarding
REM certs + router. Discovers every key expression seen on the bus during
REM the run and prints a summary on Ctrl-C or after the timeout.
REM
REM Args (all optional, in order):
REM   %1  keyExpr        default: **       (subscribe to everything)
REM   %2  timeoutSeconds default: 0        (0 = run until Ctrl-C)
REM
REM Examples:
REM   runTlsSub.bat
REM   runTlsSub.bat **                    (equivalent to no args)
REM   runTlsSub.bat "efdi/**"             (only efdi/* keys)
REM   runTlsSub.bat ** 60                 (auto-shutdown after 60 s)
REM
REM Build first:  mvn -q package    (or plain mvn -q compile)

setlocal
set ROUTER=tls/100.64.165.203:7447
set CERTDIR=d:\DEV\GOAT NET ONBOARDING\efdi-onboarding
set CA=%CERTDIR%\efdi-ca-root.pem
set CERT=%CERTDIR%\0472940bda1695cb078cd8e927b7afed-cert.pem
set KEY=%CERTDIR%\0472940bda1695cb078cd8e927b7afed-key.pem

REM Positional overrides: keyExpr = %~1 (default **), timeout = %~2 (default 0)
set KEY_EXPR=%~1
if "%KEY_EXPR%"=="" set KEY_EXPR=**
set TIMEOUT=%~2
if "%TIMEOUT%"=="" set TIMEOUT=0

java -cp "%~dp0target\classes;%~dp0target\*" sample.zenoh.ZenohJavaTlsSub ^
  "%ROUTER%" ^
  "%CA%" ^
  "%CERT%" ^
  "%KEY%" ^
  "%KEY_EXPR%" ^
  "%TIMEOUT%"

endlocal

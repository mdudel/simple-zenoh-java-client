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
REM   runTlsSub.bat "efdi/**"             (only efdi/* keys)
REM   runTlsSub.bat "**" 60               (auto-shutdown after 60 s)
REM
REM Build first:  mvn -q package    (or plain mvn -q compile)
REM
REM Windows-argv note: DO NOT pass a BARE ** on the command line.
REM cmd.exe leaves it alone, but the Windows Java launcher (java.exe)
REM applies MSVCRT-style glob expansion to unquoted * / ** args BEFORE
REM main() sees them, which will silently expand ** to a directory
REM listing and shift every downstream positional arg by N-1 places.
REM Either quote it as "**" or omit the arg entirely (this batch does
REM the omit for you when %1 is missing so the java class uses its
REM built-in default of **).

setlocal
set ROUTER=tls/100.64.165.203:7447
set CERTDIR=d:\DEV\GOAT NET ONBOARDING\efdi-onboarding
set CA=%CERTDIR%\efdi-ca-root.pem
set CERT=%CERTDIR%\0472940bda1695cb078cd8e927b7afed-cert.pem
set KEY=%CERTDIR%\0472940bda1695cb078cd8e927b7afed-key.pem

REM Positional pass-through. We deliberately DO NOT invent defaults for
REM %1 / %2 in the batch -- the Java class already defaults keyExpr to
REM ** and timeoutSeconds to 0 when the corresponding argv slot is
REM missing. Injecting a batch-level default would force us to put a
REM bare/quoted ** on the java command line, which is exactly the
REM Windows-glob trap described above.
if "%~1"=="" goto :no_args
if "%~2"=="" goto :one_arg
goto :two_args

:no_args
java -cp "%~dp0target\classes;%~dp0target\*" sample.zenoh.ZenohJavaTlsSub ^
  "%ROUTER%" "%CA%" "%CERT%" "%KEY%"
goto :done

:one_arg
java -cp "%~dp0target\classes;%~dp0target\*" sample.zenoh.ZenohJavaTlsSub ^
  "%ROUTER%" "%CA%" "%CERT%" "%KEY%" "%~1"
goto :done

:two_args
java -cp "%~dp0target\classes;%~dp0target\*" sample.zenoh.ZenohJavaTlsSub ^
  "%ROUTER%" "%CA%" "%CERT%" "%KEY%" "%~1" "%~2"
goto :done

:done
endlocal

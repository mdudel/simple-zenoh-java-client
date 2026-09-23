@echo off
REM mTLS Publisher sample runner - hard-wired to the GOAT NET onboarding
REM certs + router. Publishes a short UTF-8 payload N times to a key
REM expression over mTLS, then closes cleanly.
REM
REM Positional args (all optional, in order):
REM   %1  keyExpr        default: demo/greeting
REM   %2  count          default: 5
REM   %3  interval-ms    default: 1000
REM
REM Optional named flags (accepted anywhere in args, order-independent):
REM   --priority=<name>       control | real_time | interactive_high |
REM                           interactive_low | data_high | data (default) |
REM                           data_low | background  (also accepts dashes)
REM   --congestion=<drop|block>   default: drop
REM   --express                   sets the Express bit
REM   --auto-timestamp            stamp every message with now()
REM                               (blocked by upstream #7 for real dates)
REM   --node-id=<u32>             origin routing id; default 0 (suppressed)
REM
REM Examples:
REM   runTlsPub.bat
REM   runTlsPub.bat demo/hello 10 500
REM   runTlsPub.bat skylord/tracks 20 250 --priority=real_time
REM   runTlsPub.bat skylord/cmd 1 0 --priority=control --express
REM   runTlsPub.bat bulk/dump 1 0 --priority=background --congestion=block
REM
REM Pairs with runTlsSub.bat for a quick end-to-end mTLS smoke test.
REM
REM Build first:  mvn -q package    (or plain mvn -q compile)
REM
REM Windows-argv note: DO NOT pass a BARE ** on the command line.
REM cmd.exe leaves it alone, but the Windows Java launcher (java.exe)
REM applies MSVCRT-style glob expansion to unquoted * / ** args BEFORE
REM main() sees them, which will silently expand ** to a directory
REM listing and shift every downstream positional arg by N-1 places.
REM Either quote wildcard key expressions as "sensor/**" or avoid
REM leading-** patterns in publishers entirely (they usually make no
REM sense for a publisher anyway -- publishers name a concrete key).

setlocal
set ROUTER=tls/100.64.165.203:7447
set CERTDIR=D:\GOAT
set CERT=%CERTDIR%\996dfb6c880346559dff117458d27b66-cert.pem
set KEY=%CERTDIR%\996dfb6c880346559dff117458d27b66-key.pem

REM Positional pass-through. Same defensive pattern as runTlsSub.bat:
REM we deliberately DO NOT inject batch-level defaults for %1..%3 --
REM the Java class already defaults keyExpr / count / interval when
REM the corresponding argv slot is missing. Any named --flag= args
REM the user tacks on after the positional trio get forwarded as-is
REM via the extra slots.
if "%~1"=="" goto :no_args
if "%~2"=="" goto :one_arg
if "%~3"=="" goto :two_args
goto :three_or_more_args

:no_args
java -cp "%~dp0target\classes;%~dp0target\*" sample.zenoh.ZenohJavaTlsPub ^
  "%ROUTER%" "%CA%" "%CERT%" "%KEY%"
goto :done

:one_arg
java -cp "%~dp0target\classes;%~dp0target\*" sample.zenoh.ZenohJavaTlsPub ^
  "%ROUTER%" "%CA%" "%CERT%" "%KEY%" "%~1"
goto :done

:two_args
java -cp "%~dp0target\classes;%~dp0target\*" sample.zenoh.ZenohJavaTlsPub ^
  "%ROUTER%" "%CA%" "%CERT%" "%KEY%" "%~1" "%~2"
goto :done

:three_or_more_args
REM All trailing args (positional %3 = interval, plus any --flag= args
REM the user tacked on the end) get forwarded via %3 and %4..%9. If you
REM need more than 6 extra slots, use SHIFT to consume %2 first.
java -cp "%~dp0target\classes;%~dp0target\*" sample.zenoh.ZenohJavaTlsPub ^
  "%ROUTER%" "%CA%" "%CERT%" "%KEY%" "%~1" "%~2" "%~3" %4 %5 %6 %7 %8 %9
goto :done

:done
endlocal

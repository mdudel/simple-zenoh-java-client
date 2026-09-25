@echo off
REM mTLS Publisher sample runner - hard-wired to the GOAT NET onboarding
REM certs + router. Publishes a short UTF-8 payload N times to a key
REM expression over mTLS, then closes cleanly.
REM
REM Positional args (all optional, in order):
REM   %1  keyExpr        default: %TOPIC% (see below), unless --topic is given
REM   %2  count          default: 5
REM   %3  interval-ms    default: 1000
REM
REM Optional named flags (accepted anywhere in args, order-independent):
REM   --topic=<keyExpr>       overrides the key expression to publish to.
REM                           Default: 996dfb6c880346559dff117458d27b66/zenoh-client/test-topic
REM                           When --topic is given, positional %1/%2 are
REM                           read as count/interval instead of keyExpr/count.
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
REM   runTlsPub.bat --topic=skylord/tracks 20 250 --priority=real_time
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

REM Scan all args for -h / --help / /? (case-insensitive). If any is
REM present, print help and exit WITHOUT running the Java sample.
REM Done up-front so -h works regardless of where the user put it.
for %%A in (%*) do (
    if /I "%%~A"=="-h"     goto :help
    if /I "%%~A"=="--help" goto :help
    if /I "%%~A"=="/?"     goto :help
)

set ROUTER=tls/100.64.165.203:7447
set CERTDIR=D:\GOAT
set CA=%CERTDIR%\efdi-ca-root.pem
set CERT=%CERTDIR%\996dfb6c880346559dff117458d27b66-cert.pem
set KEY=%CERTDIR%\996dfb6c880346559dff117458d27b66-key.pem
set TOPIC=996dfb6c880346559dff117458d27b66/zenoh-client/test-topic

REM ----- Pass 1: pull --topic=<value> out of the args into TOPICOVERRIDE.
REM Done as its own non-destructive scan (like the -h scan above) so
REM --topic can appear in any position without disturbing the shift-loop
REM below. If --topic is present, it always wins over a positional
REM keyExpr.
set "TOPICOVERRIDE="
for %%A in (%*) do call :checktopic "%%~A"

REM ----- Pass 2: walk the args again with SHIFT. Non "--" tokens fill
REM keyExpr/count/interval in that order -- or just count/interval when
REM --topic already supplied the key expression. "--" tokens other than
REM --topic=... are forwarded as-is to the Java sample's own parser
REM (--priority, --congestion, --express, --auto-timestamp, --node-id).
REM --topic=<value> itself is consumed here and never forwarded, since
REM the Java sample has no idea what --topic means.
set "KEYEXPR="
set "COUNT="
set "INTERVAL="
set "EXTRA="

:argloop
if "%~1"=="" goto :argsdone
set "ARG=%~1"

if /I "%ARG:~0,8%"=="--topic=" (
    shift
    goto :argloop
)

if "%ARG:~0,2%"=="--" (
    set "EXTRA=%EXTRA% %ARG%"
    shift
    goto :argloop
)

if defined TOPICOVERRIDE (
    if not defined COUNT (
        set "COUNT=%ARG%"
    ) else (
        if not defined INTERVAL set "INTERVAL=%ARG%"
    )
) else (
    if not defined KEYEXPR (
        set "KEYEXPR=%ARG%"
    ) else (
        if not defined COUNT (
            set "COUNT=%ARG%"
        ) else (
            if not defined INTERVAL set "INTERVAL=%ARG%"
        )
    )
)

shift
goto :argloop
:argsdone

if defined TOPICOVERRIDE (
    set "KEYEXPR=%TOPICOVERRIDE%"
) else (
    if not defined KEYEXPR set "KEYEXPR=%TOPIC%"
)
if not defined COUNT set "COUNT=5"
if not defined INTERVAL set "INTERVAL=1000"

java -cp "%~dp0target\classes;%~dp0target\*" sample.zenoh.ZenohJavaTlsPub ^
  "%ROUTER%" "%CA%" "%CERT%" "%KEY%" "%KEYEXPR%" "%COUNT%" "%INTERVAL%" %EXTRA%
goto :done

:done
endlocal
goto :eof

:checktopic
set "CARG=%~1"
if /I "%CARG:~0,8%"=="--topic=" (
    set "TOPICOVERRIDE=%CARG:~8%"
)
goto :eof

:help
rem Print usage and exit without invoking Java. Kept in sync with the
rem REM header block at the top of this file; edit both together.
echo.
echo runTlsPub.bat - mTLS Zenoh publisher sample runner
echo.
echo Hard-wired to the GOAT NET onboarding certs + router:
echo   ROUTER  = tls/100.64.165.203:7447
echo   CERTDIR = D:\GOAT
echo   CA      = %%CERTDIR%%\efdi-ca-root.pem
echo   CERT    = %%CERTDIR%%\996dfb6c880346559dff117458d27b66-cert.pem
echo   KEY     = %%CERTDIR%%\996dfb6c880346559dff117458d27b66-key.pem
echo   TOPIC   = 996dfb6c880346559dff117458d27b66/zenoh-client/test-topic
echo.
echo USAGE:
echo   runTlsPub.bat [-h ^| --help ^| /?]
echo   runTlsPub.bat [keyExpr] [count] [interval-ms] [--flag=value ...]
echo.
echo POSITIONAL ARGS (all optional, in order):
echo   %%1  keyExpr        default: TOPIC above, unless --topic is given
echo   %%2  count          default: 5
echo   %%3  interval-ms    default: 1000
echo.
echo OPTIONAL NAMED FLAGS (accepted anywhere in args, order-independent):
echo   --topic=^<keyExpr^>       overrides the key expression to publish to.
echo                           When given, positional %%1/%%2 are read as
echo                           count/interval instead of keyExpr/count.
echo   --priority=^<name^>       control ^| real_time ^| interactive_high ^|
echo                           interactive_low ^| data_high ^| data (default) ^|
echo                           data_low ^| background  (also accepts dashes)
echo   --congestion=^<drop^|block^>   default: drop
echo   --express                   sets the Express bit
echo   --auto-timestamp            stamp every message with now()
echo                               (blocked by upstream #7 for real dates)
echo   --node-id=^<u32^>             origin routing id; default 0 (suppressed)
echo.
echo EXAMPLES:
echo   runTlsPub.bat --help
echo   runTlsPub.bat
echo   runTlsPub.bat demo/hello 10 500
echo   runTlsPub.bat --topic=skylord/tracks 20 250 --priority=real_time
echo   runTlsPub.bat skylord/cmd 1 0 --priority=control --express
echo   runTlsPub.bat bulk/dump 1 0 --priority=background --congestion=block
echo.
echo Pairs with runTlsSub.bat for a quick end-to-end mTLS smoke test.
echo Build first: mvn -q package  (or plain mvn -q compile)
echo.
echo NOTE (Windows-argv): do NOT pass a BARE ** on the command line.
echo The Java launcher glob-expands unquoted * / ** args BEFORE main()
echo sees them. Quote wildcard key expressions as "sensor/**" or avoid
echo leading-** patterns in publishers entirely (usually meaningless for
echo a publisher anyway -- publishers name a concrete key).
endlocal

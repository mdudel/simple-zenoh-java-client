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
REM   --qos=<name>            sets the publisher-scope priority. Alias for
REM                           --priority (same valid names below); if both
REM                           are given, --qos wins. Default when neither
REM                           is given: the QOS preset below ("data" ->
REM                           no QoS extension emitted, byte-identical to
REM                           the pre-QoS client).
REM   --priority=<name>       control | real_time | interactive_high |
REM                           interactive_low | data_high | data (default) |
REM                           data_low | background  (also accepts dashes)
REM                           Valid --qos / --priority names, in full:
REM                             control            - router control-plane
REM                             real_time          - hard real-time streams
REM                             interactive_high   - must-not-stall user traffic
REM                             interactive_low    - latency-tolerant user traffic
REM                             data_high          - important non-interactive data
REM                             data               - general-purpose (default)
REM                             data_low           - low-importance data
REM                             background         - lowest priority, best-effort
REM   --congestion=<drop|block>   default: drop
REM   --express                   sets the Express bit
REM   --auto-timestamp            stamp every message with now()
REM                               (blocked by upstream #7 for real dates)
REM   --node-id=<u32>             origin routing id; default 0 (suppressed)
REM
REM Examples:
REM   runTlsPub.bat
REM   runTlsPub.bat demo/hello 10 500
REM   runTlsPub.bat --topic=skylord/tracks 20 250 --qos=real_time
REM   runTlsPub.bat skylord/cmd 1 0 --qos=control --express
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
REM
REM Windows-cmd.exe note: an unquoted "--flag=value" on the command
REM line is split by cmd.exe itself into two parameters, "--flag" and
REM "value" (cmd.exe treats space, comma, semicolon, AND equals sign as
REM parameter delimiters). This script handles both forms transparently
REM for --topic / --qos / --priority / --congestion / --node-id, so
REM `--qos=real_time` and `--qos real_time` behave identically -- you
REM never need to quote these flags.

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
set QOS=data

REM ----- Windows-cmd.exe gotcha: "=" is a parameter delimiter -----------
REM cmd.exe splits UNQUOTED batch parameters on space, comma, semicolon,
REM AND equals sign. So `runTlsPub.bat --qos=control` does NOT arrive
REM here as one token "--qos=control" -- cmd.exe has already split it
REM into two: "--qos" and "control", dropping the "=" entirely. Every
REM value-flag below (topic/qos/priority/congestion/node-id) therefore
REM has to accept BOTH forms:
REM   - one token  "--flag=value"   (only actually reachable if the
REM                                  CALLER quoted it, e.g. "--qos=x")
REM   - two tokens "--flag" "value" (what cmd.exe hands us for the
REM                                  normal unquoted "--flag=value" usage)
REM Both are treated identically below.

REM ----- Pass 1: pull --topic/--qos/--priority (either form) out of the
REM args into TOPICOVERRIDE / QOSOVERRIDE / PRIORITYOVERRIDE. Done as
REM its own non-destructive scan (like the -h scan above) so these flags
REM can appear in any position without disturbing the shift-loop below.
REM --topic always wins over a positional keyExpr; --qos always wins
REM over --priority.
set "TOPICOVERRIDE="
set "QOSOVERRIDE="
set "PRIORITYOVERRIDE="
set "PENDINGFLAG="
for %%A in (%*) do call :scanflags "%%~A"

REM ----- Pass 2: walk the args again with SHIFT. Non "--" tokens fill
REM keyExpr/count/interval in that order -- or just count/interval when
REM --topic already supplied the key expression. "--" tokens other than
REM the ones below are forwarded as-is to the Java sample's own parser
REM (--express, --auto-timestamp). --topic/--qos/--priority are consumed
REM here and never forwarded raw; the resolved priority is re-added as a
REM single --priority=... flag below, after Pass 2, so the Java sample
REM never sees --qos (it has no idea what that means) or two conflicting
REM --priority flags. --congestion/--node-id are normalised back into a
REM single "--flag=value" token here (see the cmd.exe gotcha above) and
REM appended to EXTRA.
set "KEYEXPR="
set "COUNT="
set "INTERVAL="
set "EXTRA="

:argloop
if "%~1"=="" goto :argsdone
set "ARG=%~1"

if /I "%ARG%"=="--topic" (
    shift
    shift
    goto :argloop
)
if /I "%ARG:~0,8%"=="--topic=" (
    shift
    goto :argloop
)

if /I "%ARG%"=="--qos" (
    shift
    shift
    goto :argloop
)
if /I "%ARG:~0,6%"=="--qos=" (
    shift
    goto :argloop
)

if /I "%ARG%"=="--priority" (
    shift
    shift
    goto :argloop
)
if /I "%ARG:~0,11%"=="--priority=" (
    shift
    goto :argloop
)

if /I "%ARG%"=="--congestion" (
    set "EXTRA=%EXTRA% --congestion=%~2"
    shift
    shift
    goto :argloop
)

if /I "%ARG%"=="--node-id" (
    set "EXTRA=%EXTRA% --node-id=%~2"
    shift
    shift
    goto :argloop
)

REM --express / --auto-timestamp are normally bare (no value; empty
REM value means "true" to the Java sample). Only re-pair with the next
REM token if it looks like an explicit boolean value, so a BARE
REM --express immediately followed by a positional arg (the far more
REM common case) does not wrongly swallow that positional arg.
if /I "%ARG%"=="--express" (
    if /I "%~2"=="true"  (set "EXTRA=%EXTRA% --express=true"  & shift & shift & goto :argloop)
    if /I "%~2"=="false" (set "EXTRA=%EXTRA% --express=false" & shift & shift & goto :argloop)
    if "%~2"=="1"        (set "EXTRA=%EXTRA% --express=1"     & shift & shift & goto :argloop)
    if "%~2"=="0"        (set "EXTRA=%EXTRA% --express=0"     & shift & shift & goto :argloop)
)

if /I "%ARG%"=="--auto-timestamp" (
    if /I "%~2"=="true"  (set "EXTRA=%EXTRA% --auto-timestamp=true"  & shift & shift & goto :argloop)
    if /I "%~2"=="false" (set "EXTRA=%EXTRA% --auto-timestamp=false" & shift & shift & goto :argloop)
    if "%~2"=="1"        (set "EXTRA=%EXTRA% --auto-timestamp=1"     & shift & shift & goto :argloop)
    if "%~2"=="0"        (set "EXTRA=%EXTRA% --auto-timestamp=0"     & shift & shift & goto :argloop)
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

REM Resolve the priority to forward: --qos wins, then --priority, then
REM the QOS preset. Always emitted as a single explicit --priority=...
REM flag, since a "data" priority with drop congestion and no express
REM is value-equal to Qos.DEFAULT -- the wire-level QoS extension is
REM still suppressed (see Qos.isDefault()), so this is byte-identical
REM to leaving --priority off entirely when QOS is left at "data".
if defined QOSOVERRIDE (
    set "RESOLVEDPRIORITY=%QOSOVERRIDE%"
) else (
    if defined PRIORITYOVERRIDE (
        set "RESOLVEDPRIORITY=%PRIORITYOVERRIDE%"
    ) else (
        set "RESOLVEDPRIORITY=%QOS%"
    )
)
set "EXTRA=%EXTRA% --priority=%RESOLVEDPRIORITY%"

java -cp "%~dp0target\classes;%~dp0target\*" sample.zenoh.ZenohJavaTlsPub ^
  "%ROUTER%" "%CA%" "%CERT%" "%KEY%" "%KEYEXPR%" "%COUNT%" "%INTERVAL%" %EXTRA%
goto :done

:done
endlocal
goto :eof

:scanflags
set "CARG=%~1"

REM A pending bare flag from the previous call means THIS token is its
REM value (the cmd.exe-split form of "--flag=value"; see the gotcha
REM note above).
if defined PENDINGFLAG (
    if "%PENDINGFLAG%"=="TOPIC"    set "TOPICOVERRIDE=%CARG%"
    if "%PENDINGFLAG%"=="QOS"      set "QOSOVERRIDE=%CARG%"
    if "%PENDINGFLAG%"=="PRIORITY" set "PRIORITYOVERRIDE=%CARG%"
    set "PENDINGFLAG="
    goto :eof
)

if /I "%CARG%"=="--topic" (
    set "PENDINGFLAG=TOPIC"
    goto :eof
)
if /I "%CARG:~0,8%"=="--topic=" (
    set "TOPICOVERRIDE=%CARG:~8%"
    goto :eof
)

if /I "%CARG%"=="--qos" (
    set "PENDINGFLAG=QOS"
    goto :eof
)
if /I "%CARG:~0,6%"=="--qos=" (
    set "QOSOVERRIDE=%CARG:~6%"
    goto :eof
)

if /I "%CARG%"=="--priority" (
    set "PENDINGFLAG=PRIORITY"
    goto :eof
)
if /I "%CARG:~0,11%"=="--priority=" (
    set "PRIORITYOVERRIDE=%CARG:~11%"
    goto :eof
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
echo   QOS     = data  (no QoS extension emitted; see --qos below)
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
echo   --qos=^<name^>            sets the publisher-scope priority. Alias
echo                           for --priority; if both given, --qos wins.
echo                           Default: the QOS preset above ("data").
echo   --priority=^<name^>       same valid names as --qos, listed below.
echo                           (also accepts dashes, e.g. real-time)
echo.
echo   Valid --qos / --priority names:
echo     control            - router control-plane traffic
echo     real_time          - hard real-time streams
echo     interactive_high   - user traffic that must not stall
echo     interactive_low    - user traffic that tolerates small latency
echo     data_high          - important non-interactive data streams
echo     data               - general-purpose data traffic (default)
echo     data_low           - low-importance data streams
echo     background         - lowest priority, best-effort only
echo.
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
echo   runTlsPub.bat --topic=skylord/tracks 20 250 --qos=real_time
echo   runTlsPub.bat skylord/cmd 1 0 --qos=control --express
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
echo.
echo NOTE (Windows-cmd.exe): --topic/--qos/--priority/--congestion/
echo --node-id work equally well written as --flag=value or as
echo --flag value (space-separated) -- cmd.exe itself splits an
echo unquoted "=" the same way it splits a space, so both forms are
echo handled here.
endlocal

@echo off
REM Publisher sample runner. Args forwarded to ZenohJavaPub:
REM   Positional: [endpoint] [key] [count] [interval-ms]
REM   Optional flags (anywhere in args, order-independent):
REM     --priority=<name>       control | real_time | interactive_high |
REM                             interactive_low | data_high | data (default) |
REM                             data_low | background  (also accepts dashes)
REM     --congestion=<drop|block>   default: drop
REM     --express                   sets the Express bit
REM     --auto-timestamp            stamp every message with now()
REM                                 (blocked by upstream #7 for real dates)
REM     --node-id=<u32>             origin routing id; default 0 (suppressed)
REM Examples:
REM   runPub.bat
REM   runPub.bat tcp/[::1]:7447 demo/hello 5
REM   runPub.bat --priority=real_time tcp/[::1]:7447 skylord/tracks 10
REM   runPub.bat --priority=control --express tcp/[::1]:7447 skylord/cmd 1
REM   runPub.bat --priority=background --congestion=block tcp/[::1]:7447 bulk/dump 1
REM
REM Build first:  mvn -q package
REM (or plain compile is enough:  mvn -q compile)
REM
REM Windows-cmd.exe note: an unquoted "--flag=value" on the command line
REM is split by cmd.exe itself into two parameters, "--flag" and "value"
REM (cmd.exe treats space, comma, semicolon, AND equals sign as parameter
REM delimiters, dropping the "=" entirely). This script re-pairs
REM --priority/--congestion/--node-id with their value regardless of
REM which form you use, so `--priority=real_time` and
REM `--priority real_time` behave identically -- you never need to quote
REM these flags.

setlocal
set "OUTARGS="

:argloop
if "%~1"=="" goto :argsdone
set "ARG=%~1"

if /I "%ARG%"=="--priority" (
    set "OUTARGS=%OUTARGS% --priority=%~2"
    shift
    shift
    goto :argloop
)

if /I "%ARG%"=="--congestion" (
    set "OUTARGS=%OUTARGS% --congestion=%~2"
    shift
    shift
    goto :argloop
)

if /I "%ARG%"=="--node-id" (
    set "OUTARGS=%OUTARGS% --node-id=%~2"
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
    if /I "%~2"=="true"  (set "OUTARGS=%OUTARGS% --express=true"  & shift & shift & goto :argloop)
    if /I "%~2"=="false" (set "OUTARGS=%OUTARGS% --express=false" & shift & shift & goto :argloop)
    if "%~2"=="1"        (set "OUTARGS=%OUTARGS% --express=1"     & shift & shift & goto :argloop)
    if "%~2"=="0"        (set "OUTARGS=%OUTARGS% --express=0"     & shift & shift & goto :argloop)
)

if /I "%ARG%"=="--auto-timestamp" (
    if /I "%~2"=="true"  (set "OUTARGS=%OUTARGS% --auto-timestamp=true"  & shift & shift & goto :argloop)
    if /I "%~2"=="false" (set "OUTARGS=%OUTARGS% --auto-timestamp=false" & shift & shift & goto :argloop)
    if "%~2"=="1"        (set "OUTARGS=%OUTARGS% --auto-timestamp=1"     & shift & shift & goto :argloop)
    if "%~2"=="0"        (set "OUTARGS=%OUTARGS% --auto-timestamp=0"     & shift & shift & goto :argloop)
)

REM Anything else -- positional args, or a caller-quoted single-token
REM "--flag=value" -- is forwarded exactly as received.
set "OUTARGS=%OUTARGS% %ARG%"
shift
goto :argloop
:argsdone

java -cp "%~dp0target\classes;%~dp0target\*" sample.zenoh.ZenohJavaPub %OUTARGS%
endlocal

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
java -cp "%~dp0target\classes;%~dp0target\*" sample.zenoh.ZenohJavaPub %*

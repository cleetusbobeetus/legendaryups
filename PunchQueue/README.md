# PunchQueue 1.0.0

Queues every `/punch1`, `/punch2`, `/punch3` (LegendaryPunch) and delivers them at a fixed rate on the main thread,
so bursts of paid TikFinity punches all land instead of being dropped.

Rate: `punches-per-tick` (punches per delivery) and `drain-every-ticks` (ticks between deliveries) in
`plugins/PunchQueue/config.yml`. Default `drain-every-ticks: 4` with `punches-per-tick: 1` = 5 punches/sec.
`drain-every-ticks: 1` = 20/sec.

## Build
Compile against the Paper 1.21.10 API (`paper-api-1.21.10-R0.1-SNAPSHOT.jar`, plus `adventure-key`, `adventure-api`
and `org.jetbrains:annotations` from the server's `libraries/`), then package:

    javac --release 21 -cp <classpath> -d classes src/gg/legendaryups/punchqueue/PunchQueuePlugin.java
    copy plugin.yml and config.yml into classes/
    jar cfm PunchQueue-1.0.0.jar manifest.txt -C classes .   (manifest: Main-Class: gg.legendaryups.punchqueue.PunchQueuePlugin)

Drop the jar into `plugins/` and restart. Requires LegendaryPunch.

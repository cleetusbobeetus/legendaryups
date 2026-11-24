# PunchQueue 2.0.0

Durable, unlimited queue for `/punch1`, `/punch2`, `/punch3` (LegendaryPunch).

- Every accepted punch is written to `plugins/PunchQueue/queue-journal.log` and fsynced **before** the command reports success.
- No size limit. 20,000 punches from 20,000 commands, or one `punch1 <player> 20000`, become 20,000 individual punches.
- Each physical punch = one call of LegendaryPunch's own executor with only the target name. Horizontal effects are unchanged.
- Applied at `punches-per-tick` per tick (default 1). Offline targets keep their batch; other targets keep flowing.
- Survives restart, `/reload`, and crashes. Disable/enable never clears the queue.

## Commands
- `/punch1|2|3 <player> [count]` (TikFinity/console, no leading slash). `count` defaults to 1.
- `/punchqueue status` shows received, executed, pending, failed attempts, paused, journal state, oldest age.
- `/punchqueue inspect` shows pending per target.
- `/punchqueue pause` / `resume` stops or restarts applying punches. Nothing is deleted and intake continues.
- `/punchqueue add <1|2|3> <viewer> <count> <target>` adds a batch by hand (op only).

## Guarantees and limits
- Crash: punches applied since the last tick flush can repeat on restart (at-least-once). Exact-once is not possible without transactional coordination with the game.
- Journal write failure: intake and draining stop, and an alarm is logged. Nothing is acknowledged that wasn't written. Fix the disk and restart.
- Viewer attribution is the command sender (console for TikFinity). TikFinity does not send the gifter name through `/punch`, so per-viewer attribution needs `/punchqueue add`.
- Each physical punch runs LegendaryPunch's executor from the console sender, so any console output it prints appears once per punch.
- Enqueue cost: about 0.6 ms per accepted command because each one is fsynced. A burst of 20,000 commands in one tick would take about 12 seconds of main-thread time.

## Build and test (no server needed for the tests)
Run `build.bat` in this folder. It compiles the ledger, runs `PunchLedgerTest`, compiles the plugin against the
Paper 1.21.10 API in `..\libraries`, and writes `build\PunchQueue-2.0.0.jar`. Deploying the jar to a server is a separate step.

Requires LegendaryPunch.

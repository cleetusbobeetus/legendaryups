package gg.legendaryups.punchqueue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Executable acceptance tests for PunchLedger, mapped to docs/ACCEPTANCE_TEST_PLAN.md (P01-P16 where testable
 * without a live server). Run with run-tests.bat or: java -cp classes gg.legendaryups.punchqueue.PunchLedgerTest <workdir>
 */
public final class PunchLedgerTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        Path work = Path.of(args.length > 0 ? args[0] : System.getProperty("java.io.tmpdir"), "punchq-tests-" + System.nanoTime());
        Files.createDirectories(work);
        System.out.println("Work dir: " + work);

        run("P01 20,000 individual commands -> 20,000 physical punches, 1/tick", () -> p01(work));
        run("P02 one repeatcount=20000 batch -> 20,000 physical punches, each count 1", () -> p02(work));
        run("P03 mixed levels/targets keep per-target FIFO and correct intensity", () -> p03(work));
        run("P04 20,000 pending, process 5,000, restart -> resume 15,000, drain to 20,000", () -> p04(work));
        run("P05 offline target consumes nothing; resumes without loss", () -> p05(work));
        run("P07 40 concurrent senders: zero drops, correct totals", () -> p07(work));
        run("P08 never more than budget punches per tick", () -> p08(work));
        run("P09 journal cannot be created -> open fails loudly, nothing acked", () -> p09(work));
        run("P10 reopen (plugin disable/enable) keeps the persisted count", () -> p10(work));
        run("P11 same viewer + same gift without ID is NOT deduplicated", () -> p11(work));
        run("P12 invalid zero/negative/overflow/bad-name input refused, totals unchanged", () -> p12(work));
        run("P16 failed punch is not counted, other targets keep flowing", () -> p16(work));
        run("Torn final journal record (crash mid-write) is discarded, earlier records kept", () -> torn(work));
        run("Pacing: 30,000 punches at 30,000/hour take 3600 s, never more than the rate allows", () -> pacing());
        run("Pacing: idle gaps do not bank into a burst", () -> pacingNoBurst());
        run("Corrupt journal is refused, never overwritten", () -> corrupt(work));

        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------------ tests

    static void p01(Path work) throws IOException {
        Path f = work.resolve("p01.log");
        try (PunchLedger l = new PunchLedger(f, alarm())) {
            long t0 = System.nanoTime();
            for (int i = 0; i < 20_000; i++) l.enqueue("Target", "Viewer" + (i % 40), 1 + (i % 3), 1);
            long enqueueMs = (System.nanoTime() - t0) / 1_000_000;
            check(l.status().received() == 20_000, "accepted=20000 (was " + l.status().received() + ")");

            Counter c = new Counter();
            int ticks = 0;
            while (l.pending() > 0) {
                int n = l.drain(1, c.action(), online("Target"));
                check(n <= 1, "at most 1 punch in a tick (got " + n + ")");
                ticks++;
            }
            check(c.calls == 20_000, "physical punch handler calls=20000 (was " + c.calls + ")");
            check(c.okCalls == 20_000, "successful handler calls=20000");
            check(l.status().executed() == 20_000 && l.pending() == 0, "executed=20000 pending=0");
            System.out.println("    enqueue of 20,000 durable commands took " + enqueueMs + " ms; drain ticks=" + ticks);
        }
    }

    static void p02(Path work) throws IOException {
        Path f = work.resolve("p02.log");
        try (PunchLedger l = new PunchLedger(f, alarm())) {
            l.enqueue("Target", "Viewer", 3, 20_000);
            Counter c = new Counter();
            while (l.pending() > 0) l.drain(1, c.action(), online("Target"));
            check(c.calls == 20_000, "handler calls=20000, not one heavy hit (was " + c.calls + ")");
            check(l.status().executed() == 20_000, "executed=20000");
        }
    }

    static void p03(Path work) throws IOException {
        Path f = work.resolve("p03.log");
        try (PunchLedger l = new PunchLedger(f, alarm())) {
            l.enqueue("Alice", "v1", 1, 3);
            l.enqueue("Bob", "v2", 2, 2);
            l.enqueue("Alice", "v3", 3, 1);
            List<String> seen = new ArrayList<>();
            while (l.pending() > 0) {
                l.drain(1, (t, v, lv) -> { seen.add(t + ":L" + lv + ":" + v); return true; }, online("Alice", "Bob"));
            }
            List<String> aliceOrder = seen.stream().filter(s -> s.startsWith("Alice")).toList();
            check(aliceOrder.equals(List.of("Alice:L1:v1", "Alice:L1:v1", "Alice:L1:v1", "Alice:L3:v3")),
                    "Alice keeps FIFO and intensity: " + aliceOrder);
            List<String> bobOrder = seen.stream().filter(s -> s.startsWith("Bob")).toList();
            check(bobOrder.equals(List.of("Bob:L2:v2", "Bob:L2:v2")), "Bob keeps FIFO and intensity: " + bobOrder);
            check(seen.size() == 6, "6 total punches (was " + seen.size() + ")");
        }
    }

    static void p04(Path work) throws IOException {
        Path f = work.resolve("p04.log");
        try (PunchLedger l = new PunchLedger(f, alarm())) {
            l.enqueue("Target", "Viewer", 2, 20_000);
            Counter c = new Counter();
            for (int i = 0; i < 5_000; i++) l.drain(1, c.action(), online("Target"));
            check(c.calls == 5_000, "5000 processed before restart");
        }
        // restart: new instance reads the journal from disk
        try (PunchLedger l = new PunchLedger(f, alarm())) {
            PunchLedger.Status s = l.status();
            check(s.executed() == 5_000 && s.pending() == 15_000,
                    "after restart: executed=5000 pending=15000 (was executed=" + s.executed() + " pending=" + s.pending() + ")");
            Counter c = new Counter();
            while (l.pending() > 0) l.drain(1, c.action(), online("Target"));
            check(c.calls == 15_000, "15000 more punches after restart (was " + c.calls + ")");
            check(l.status().executed() == 20_000 && l.pending() == 0, "after drain: executed=20000 pending=0");
        }
    }

    static void p05(Path work) throws IOException {
        Path f = work.resolve("p05.log");
        try (PunchLedger l = new PunchLedger(f, alarm())) {
            l.enqueue("Offline", "v", 1, 100);
            l.enqueue("Online", "v", 1, 100);
            Counter c = new Counter();
            for (int i = 0; i < 500; i++) l.drain(1, c.action(), online("Online"));
            check(c.calls == 100, "only online target consumed punches (calls=" + c.calls + ")");
            check(l.pendingByTarget().get("Offline") == 100, "offline target untouched");
            Counter c2 = new Counter();
            while (l.pending() > 0) l.drain(1, c2.action(), online("Online", "Offline"));
            check(c2.calls == 100, "offline batch drains after reconnect");
            check(l.status().executed() == 200, "executed=200");
        }
    }

    static void p07(Path work) throws Exception {
        Path f = work.resolve("p07.log");
        try (PunchLedger l = new PunchLedger(f, alarm())) {
            List<Thread> threads = new ArrayList<>();
            AtomicLong errors = new AtomicLong();
            for (int t = 0; t < 40; t++) {
                final int id = t;
                Thread th = new Thread(() -> {
                    try {
                        for (int i = 0; i < 500; i++) l.enqueue("Target", "Sender" + id, 1 + (i % 3), 1);
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                });
                threads.add(th);
                th.start();
            }
            for (Thread th : threads) th.join();
            check(errors.get() == 0, "no enqueue errors");
            check(l.status().received() == 20_000, "received=20000 (was " + l.status().received() + ")");
            Counter c = new Counter();
            while (l.pending() > 0) l.drain(1, c.action(), online("Target"));
            check(c.calls == 20_000 && l.status().executed() == 20_000, "all 20000 applied, zero dropped");
        }
    }

    static void p08(Path work) throws IOException {
        Path f = work.resolve("p08.log");
        try (PunchLedger l = new PunchLedger(f, alarm())) {
            for (int i = 0; i < 1_000; i++) l.enqueue("Target", "v", 1, 1);
            for (int tick = 0; tick < 50; tick++) {
                int n = l.drain(2, (t, v, lv) -> true, online("Target"));
                check(n <= 2, "budget 2 respected (got " + n + ")");
            }
            check(l.status().executed() == 100, "100 punches after 50 ticks at budget 2");
        }
    }

    static void p09(Path work) throws IOException {
        Path blocked = work.resolve("p09-is-a-dir");
        Files.createDirectories(blocked);   // a directory cannot be opened as the journal file
        boolean threw = false;
        try {
            new PunchLedger(blocked, alarm()).close();
        } catch (IOException | RuntimeException e) {
            threw = true;
        }
        check(threw, "constructor refuses an unusable journal path");
    }

    static void p10(Path work) throws IOException {
        Path f = work.resolve("p10.log");
        try (PunchLedger l = new PunchLedger(f, alarm())) {
            l.enqueue("Target", "v", 1, 7);
            Counter c = new Counter();
            l.drain(3, c.action(), online("Target"));
        }
        try (PunchLedger l = new PunchLedger(f, alarm())) {
            check(l.status().executed() == 3 && l.status().pending() == 4, "reopen keeps executed=3 pending=4");
            check(l.status().received() == 7, "received stays 7");
        }
    }

    static void p11(Path work) throws IOException {
        Path f = work.resolve("p11.log");
        try (PunchLedger l = new PunchLedger(f, alarm())) {
            l.enqueue("Target", "Viewer", 1, 1);
            l.enqueue("Target", "Viewer", 1, 1);   // same viewer and gift, no event ID: must both count
            check(l.status().received() == 2, "two legitimate identical gifts both accepted");
        }
    }

    static void p12(Path work) throws IOException {
        Path f = work.resolve("p12.log");
        try (PunchLedger l = new PunchLedger(f, alarm())) {
            expectRefused(() -> l.enqueue("Target", "v", 1, 0), "zero");
            expectRefused(() -> l.enqueue("Target", "v", 1, -5), "negative");
            expectRefused(() -> l.enqueue("bad name", "v", 1, 1), "space in name");
            expectRefused(() -> l.enqueue("", "v", 1, 1), "empty name");
            expectRefused(() -> l.enqueue("ThisNameIsWayTooLong", "v", 1, 1), "too-long name");
            expectRefused(() -> l.enqueue("Target", "v", 4, 1), "level 4");
            l.enqueue("Target", "v", 1, Long.MAX_VALUE);
            expectRefused(() -> l.enqueue("Target", "v", 1, 1), "overflow");
            check(l.status().received() == Long.MAX_VALUE, "totals unchanged by refusals");
        }
        try (PunchLedger l = new PunchLedger(f, alarm())) {
            check(l.status().received() == Long.MAX_VALUE, "refusals were not persisted");
        }
    }

    static void p16(Path work) throws IOException {
        Path f = work.resolve("p16.log");
        try (PunchLedger l = new PunchLedger(f, alarm())) {
            l.enqueue("Broken", "v", 1, 5);
            l.enqueue("Fine", "v", 1, 5);
            Counter c = new Counter();
            for (int i = 0; i < 20; i++) {
                l.drain(1, (t, v, lv) -> {
                    c.calls++;
                    if (t.equals("Broken")) return false;
                    c.okCalls++;
                    return true;
                }, online("Broken", "Fine"));
            }
            check(c.okCalls == 5, "Fine target fully applied despite Broken failing (ok=" + c.okCalls + ")");
            check(l.pendingByTarget().get("Broken") == 5, "failed punches not counted as executed");
            check(l.status().failedAttempts() > 0, "failures counted for the operator");
        }
    }

    static void torn(Path work) throws IOException {
        Path f = work.resolve("torn.log");
        Files.writeString(f, PunchLedger.HEADER + "\nE 1 1 5 1000 v Target\nX 1 2\nE 2 1 3 10", StandardCharsets.UTF_8);
        try (PunchLedger l = new PunchLedger(f, alarm())) {
            check(l.status().received() == 5 && l.status().executed() == 2, "torn last record ignored (received=5 executed=2)");
            check(l.pendingByTarget().get("Target") == 3, "pending 3 for Target");
        }
        try (PunchLedger l = new PunchLedger(f, alarm())) {
            check(l.status().received() == 5, "still consistent after reopen");
        }
    }

    static void corrupt(Path work) throws IOException {
        Path f = work.resolve("corrupt.log");
        String bad = "not a journal at all\nE 1 1 5 1000 v Target\n";
        Files.writeString(f, bad, StandardCharsets.UTF_8);
        boolean threw = false;
        try {
            new PunchLedger(f, alarm()).close();
        } catch (IOException e) {
            threw = true;
        }
        check(threw, "refuses a file that is not a journal");
        check(Files.readString(f, StandardCharsets.UTF_8).equals(bad), "refused file left byte-for-byte unchanged");
    }

    static void pacing() {
        Pacer p = new Pacer(30_000 / 3600.0);
        long total = 0;
        int maxPerTick = 0;
        long finishedAtTick = -1;
        for (long tick = 1; tick <= 72_000 * 2; tick++) {
            int b = p.budget();
            int used = (int) Math.min(b, 30_000 - total);
            if (used > 0) {
                total += used;
                p.spent(used);
            }
            maxPerTick = Math.max(maxPerTick, used);
            if (total == 30_000 && finishedAtTick < 0) finishedAtTick = tick;
        }
        check(total == 30_000, "all 30000 applied (was " + total + ")");
        check(maxPerTick <= 1, "never more than 1 punch in a tick (max " + maxPerTick + ")");
        check(finishedAtTick >= 71_990 && finishedAtTick <= 72_010,
                "finished at about 3600 s = tick 72000 (was tick " + finishedAtTick + ")");
        System.out.println("    30,000 punches finished at tick " + finishedAtTick + " (" + (finishedAtTick / 20) + " s)");
    }

    static void pacingNoBurst() {
        Pacer p = new Pacer(8.333333);
        for (int i = 0; i < 1_000; i++) {      // target offline: nothing applied for 50 s
            if (p.budget() > 0) p.spent(0);
        }
        int first = p.budget();
        check(first <= 1, "first tick after an idle gap is at most 1 punch (was " + first + ")");
    }

    // ------------------------------------------------------------ helpers

    interface Body { void run() throws Exception; }

    static void run(String name, Body body) {
        try {
            body.run();
            passed++;
            System.out.println("[PASS] " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println("[FAIL] " + name + " -> " + t.getMessage());
        }
    }

    static void check(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
    }

    static void expectRefused(Body b, String what) {
        try {
            b.run();
        } catch (Exception e) {
            return;
        }
        throw new AssertionError("accepted invalid input: " + what);
    }

    static java.util.function.Consumer<String> alarm() {
        return msg -> System.out.println("    [alarm] " + msg);
    }

    static java.util.function.Predicate<String> online(String... names) {
        java.util.Set<String> set = java.util.Set.of(names);
        return set::contains;
    }

    static final class Counter {
        long calls;
        long okCalls;

        PunchLedger.PunchAction action() {
            return (t, v, lv) -> {
                calls++;
                okCalls++;
                return true;
            };
        }
    }
}

package gg.legendaryups.punchqueue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Durable, unlimited FIFO of physical punches. Pure Java with no Bukkit dependency, so it can be tested without a server.
 *
 * <p>Journal (UTF-8 text, one record per line, append-only):
 * <pre>
 *   PUNCHQ-JOURNAL 1
 *   E &lt;seq&gt; &lt;level&gt; &lt;count&gt; &lt;epochMs&gt; &lt;viewer&gt; &lt;target&gt;   batch accepted (fsynced BEFORE the command reports success)
 *   X &lt;seq&gt; &lt;n&gt;                                   n punches of batch seq were physically applied
 * </pre>
 * remaining(batch) = count - sum(X). Replay on startup rebuilds the exact state.
 *
 * <p>Guarantees: no size cap; one batch can hold any count; accepted = executed + pending always;
 * a punch is only counted after its action returned true; a failed write stops intake/draining and raises an alarm.
 * Crash semantics: X records are flushed at the end of each drain tick, so a crash can repeat at most the punches
 * applied since the last flush (at-least-once). Exact-once physical side effects are not possible without
 * transactional coordination with the game.
 */
public final class PunchLedger implements AutoCloseable {

    /** Performs ONE physical punch. Return true only if the punch actually happened. */
    @FunctionalInterface
    public interface PunchAction {
        boolean punch(String target, String viewer, int level) throws Exception;
    }

    public static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    static final String HEADER = "PUNCHQ-JOURNAL 1";

    /** Snapshot for status display. */
    public record Status(long received, long executed, long pending, long failedAttempts,
                         boolean paused, boolean journalBroken, String lastError, long oldestAgeMs) {}

    static final class Batch {
        final long seq;
        final String viewer;
        final String target;
        final int level;
        final long enqueuedAt;
        long remaining;

        Batch(long seq, String viewer, String target, int level, long enqueuedAt, long remaining) {
            this.seq = seq;
            this.viewer = viewer;
            this.target = target;
            this.level = level;
            this.enqueuedAt = enqueuedAt;
            this.remaining = remaining;
        }
    }

    private final Path path;
    private final Consumer<String> alarm;
    private final FileChannel channel;
    private final Map<String, ArrayDeque<Batch>> queues = new HashMap<>();
    private final ArrayDeque<String> targetOrder = new ArrayDeque<>();   // round-robin across targets
    private final Map<Long, Batch> batches = new HashMap<>();
    private final LinkedHashMap<Long, Long> appliedNotFlushed = new LinkedHashMap<>();
    private long nextSeq = 1;
    private long received;
    private long executed;
    private long failedAttempts;
    private boolean paused;
    private boolean journalBroken;
    private String lastError = "";

    public PunchLedger(Path path, Consumer<String> alarm) throws IOException {
        this.path = path;
        this.alarm = alarm;
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        String existing = Files.exists(path) ? new String(Files.readAllBytes(path), StandardCharsets.UTF_8) : "";
        this.channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE);
        try {
            if (existing.isEmpty()) {
                channel.position(0);
                appendDurable(HEADER + "\n");
            } else {
                replay(existing);
            }
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    // ---------------------------------------------------------------- intake

    /** Accepts a batch. Returns its sequence number only after the record is durably written. */
    public synchronized long enqueue(String target, String viewer, int level, long count) throws IOException {
        if (target == null || !NAME.matcher(target).matches()) throw new IllegalArgumentException("invalid target name");
        if (viewer == null || !NAME.matcher(viewer).matches()) throw new IllegalArgumentException("invalid viewer name");
        if (level < 1 || level > 3) throw new IllegalArgumentException("level must be 1, 2 or 3");
        if (count < 1) throw new IllegalArgumentException("count must be at least 1");
        if (journalBroken) throw new IOException("punch journal unavailable: " + lastError);
        long newReceived = Math.addExact(received, count);   // throws ArithmeticException on overflow

        long now = System.currentTimeMillis();
        long seq = nextSeq;
        String line = "E " + seq + " " + level + " " + count + " " + now + " " + viewer + " " + target + "\n";
        try {
            appendDurable(line);
        } catch (IOException e) {
            breakJournal(e);
            throw e;
        }
        nextSeq++;
        received = newReceived;
        Batch b = new Batch(seq, viewer, target, level, now, count);
        batches.put(seq, b);
        ArrayDeque<Batch> q = queues.get(target);
        if (q == null) {
            q = new ArrayDeque<>();
            queues.put(target, q);
            targetOrder.addLast(target);
        }
        q.addLast(b);
        return seq;
    }

    // ---------------------------------------------------------------- drain

    /**
     * Applies up to {@code budget} physical punches, FIFO per target, round-robin across online targets.
     * Batches whose target is offline are left untouched. A failed punch is not counted and is retried later.
     */
    public synchronized int drain(int budget, PunchAction action, Predicate<String> online) {
        if (paused || journalBroken) return 0;
        int done = 0;
        Set<String> skipped = new HashSet<>();
        while (done < budget) {
            String t = nextReady(online, skipped);
            if (t == null) break;
            Batch b = queues.get(t).peekFirst();
            boolean ok;
            try {
                ok = action.punch(b.target, b.viewer, b.level);
            } catch (Throwable e) {
                ok = false;
                lastError = e.toString();
            }
            if (!ok) {
                failedAttempts++;
                skipped.add(t);     // leave it in the queue, retry next tick
                continue;
            }
            done++;
            b.remaining--;
            executed++;
            appliedNotFlushed.merge(b.seq, 1L, Long::sum);
            if (b.remaining == 0) removeBatch(b);
        }
        flushApplied();
        return done;
    }

    private String nextReady(Predicate<String> online, Set<String> skipped) {
        int n = targetOrder.size();
        for (int i = 0; i < n; i++) {
            String t = targetOrder.pollFirst();
            targetOrder.addLast(t);     // rotate for fairness
            ArrayDeque<Batch> q = queues.get(t);
            if (q != null && !q.isEmpty() && !skipped.contains(t) && online.test(t)) return t;
        }
        return null;
    }

    private void removeBatch(Batch b) {
        batches.remove(b.seq);
        ArrayDeque<Batch> q = queues.get(b.target);
        if (q != null) {
            q.remove(b);
            if (q.isEmpty()) {
                queues.remove(b.target);
                targetOrder.remove(b.target);
            }
        }
    }

    private void flushApplied() {
        if (appliedNotFlushed.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Long, Long> e : appliedNotFlushed.entrySet()) {
            sb.append("X ").append(e.getKey()).append(' ').append(e.getValue()).append('\n');
        }
        try {
            appendDurable(sb.toString());
            appliedNotFlushed.clear();
        } catch (IOException e) {
            breakJournal(e);
        }
    }

    // ---------------------------------------------------------------- control

    public synchronized void setPaused(boolean value) { paused = value; }

    public synchronized boolean isPaused() { return paused; }

    public synchronized long pending() { return received - executed; }

    public synchronized Status status() {
        long oldest = Long.MAX_VALUE;
        for (ArrayDeque<Batch> q : queues.values()) {
            Batch head = q.peekFirst();
            if (head != null) oldest = Math.min(oldest, head.enqueuedAt);
        }
        long age = oldest == Long.MAX_VALUE ? 0 : Math.max(0, System.currentTimeMillis() - oldest);
        return new Status(received, executed, received - executed, failedAttempts, paused, journalBroken, lastError, age);
    }

    /** Pending punches per target, for inspection. */
    public synchronized Map<String, Long> pendingByTarget() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map.Entry<String, ArrayDeque<Batch>> e : queues.entrySet()) {
            long sum = 0;
            for (Batch b : e.getValue()) sum += b.remaining;
            out.put(e.getKey(), sum);
        }
        return out;
    }

    @Override
    public synchronized void close() throws IOException {
        flushApplied();
        channel.close();
    }

    // ---------------------------------------------------------------- journal io

    private void appendDurable(String text) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
        while (buf.hasRemaining()) channel.write(buf);
        channel.force(false);
    }

    private void breakJournal(IOException e) {
        if (!journalBroken) {
            journalBroken = true;
            lastError = e.toString();
            alarm.accept("PUNCH JOURNAL WRITE FAILED - intake and draining stopped. Nothing new is being accepted. "
                    + "Fix the disk, then restart the server. Cause: " + e);
        }
    }

    private void replay(String text) throws IOException {
        int lastNl = text.lastIndexOf('\n');
        if (lastNl < 0) throw new IOException("punch journal header damaged: " + path);
        String body = text.substring(0, lastNl + 1);
        String[] lines = body.split("\n");
        if (lines.length == 0 || !lines[0].equals(HEADER)) {
            throw new IOException("not a PunchQueue journal (refusing to touch it): " + path);
        }
        for (int i = 1; i < lines.length; i++) applyRecord(lines[i], i + 1);

        // Drop a torn final record (crash mid-write). Anything after the last newline was never acknowledged.
        long bodyBytes = body.getBytes(StandardCharsets.UTF_8).length;
        if (bodyBytes < channel.size()) channel.truncate(bodyBytes);
        channel.position(bodyBytes);

        // Fully drained journal: compact back to the header so it doesn't grow forever.
        if (received == executed && lines.length > 1) {
            channel.truncate(0);
            channel.position(0);
            appendDurable(HEADER + "\n");
        }
        // A batch whose X records exceed its count means the file is corrupt.
        for (Batch b : batches.values()) {
            if (b.remaining < 0) throw new IOException("punch journal corrupt (batch " + b.seq + ")");
        }
    }

    private void applyRecord(String line, int lineNo) throws IOException {
        String[] p = line.split(" ");
        try {
            switch (p[0]) {
                case "E" -> {
                    if (p.length != 7) throw new IOException();
                    long seq = Long.parseLong(p[1]);
                    int level = Integer.parseInt(p[2]);
                    long count = Long.parseLong(p[3]);
                    long at = Long.parseLong(p[4]);
                    if (count < 1 || level < 1 || level > 3) throw new IOException();
                    Batch b = new Batch(seq, p[5], p[6], level, at, count);
                    batches.put(seq, b);
                    received = Math.addExact(received, count);
                    nextSeq = Math.max(nextSeq, seq + 1);
                    ArrayDeque<Batch> q = queues.get(b.target);
                    if (q == null) {
                        q = new ArrayDeque<>();
                        queues.put(b.target, q);
                        targetOrder.addLast(b.target);
                    }
                    q.addLast(b);
                }
                case "X" -> {
                    if (p.length != 3) throw new IOException();
                    long seq = Long.parseLong(p[1]);
                    long n = Long.parseLong(p[2]);
                    Batch b = batches.get(seq);
                    if (b == null || n < 1 || n > b.remaining) throw new IOException();
                    b.remaining -= n;
                    executed += n;
                    if (b.remaining == 0) removeBatch(b);
                }
                default -> throw new IOException();
            }
        } catch (IOException | RuntimeException e) {
            throw new IOException("punch journal corrupt at line " + lineNo + ": " + line, e);
        }
    }
}

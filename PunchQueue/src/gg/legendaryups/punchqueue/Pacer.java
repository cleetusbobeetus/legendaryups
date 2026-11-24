package gg.legendaryups.punchqueue;

/**
 * Turns a punches-per-second rate into whole punches per server tick, carrying fractional credit between ticks,
 * so e.g. 30,000 punches per hour (8.33/sec) lands evenly over 3600 seconds.
 *
 * <p>Idle credit is capped at 1 punch, so a gap (offline target, empty queue, paused) can't bank into a burst.
 */
public final class Pacer {

    private final double perTick;
    private double credit;

    public Pacer(double punchesPerSecond) {
        if (!(punchesPerSecond > 0) || Double.isInfinite(punchesPerSecond)) {
            throw new IllegalArgumentException("punches-per-second must be a positive number");
        }
        this.perTick = punchesPerSecond / 20.0;
    }

    /** Call once per tick. Returns how many punches may be applied this tick. */
    public int budget() {
        credit += perTick;
        return (int) credit;
    }

    /** Call after a tick with a non-zero budget, with how many punches were actually applied. */
    public void spent(int used) {
        credit = Math.min(credit - used, 1.0);
    }

    public double perTick() {
        return perTick;
    }
}

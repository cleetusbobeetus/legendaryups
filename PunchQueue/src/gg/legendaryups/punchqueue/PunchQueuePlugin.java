package gg.legendaryups.punchqueue;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Wraps LegendaryPunch's /punch1../punch3 with a durable, unlimited queue (see {@link PunchLedger}).
 *
 * <p>Each accepted command = one or more physical punches, stored on disk before the command reports success.
 * The main thread applies at most {@code punches-per-tick} punches per tick by calling LegendaryPunch's own
 * executor once per punch with the target name only. This plugin does not register /punch1-3 itself
 * (LegendaryPunch owns them), so there is no recursion.
 */
public final class PunchQueuePlugin extends JavaPlugin {

    private PunchLedger ledger;
    private Pacer pacer;
    private double perSecond;
    private BukkitTask task;
    private final Map<Integer, PluginCommand> levelCommands = new HashMap<>();
    private final Map<Integer, CommandExecutor> originals = new HashMap<>();
    private boolean lastPendingZero = true;

    /** Scoreboard objective and entry the datapack HUD reads: 1 = punches on, 0 = off. */
    private static final String PUNCH_OBJECTIVE = "dp_punch";
    private static final String PUNCH_ENTRY = "#punch";

    /** OFF refuses new punches. Punches already accepted still finish. */
    private boolean punchesOn = true;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // punches-per-second wins; punches-per-tick (x20) is accepted for older configs.
        perSecond = getConfig().contains("punches-per-second")
                ? getConfig().getDouble("punches-per-second")
                : getConfig().getInt("punches-per-tick", 1) * 20.0;
        try {
            pacer = new Pacer(perSecond);
        } catch (IllegalArgumentException e) {
            getLogger().severe("Bad punches-per-second (" + perSecond + "): " + e.getMessage() + " - using 20/sec.");
            perSecond = 20.0;
            pacer = new Pacer(perSecond);
        }

        try {
            ledger = new PunchLedger(getDataFolder().toPath().resolve("queue-journal.log"),
                    msg -> getLogger().severe("[ALARM] " + msg));
        } catch (IOException e) {
            getLogger().severe("Could not open the punch journal (" + e + "). Queue NOT active; /punch commands are unchanged.");
            return;
        }

        for (int level = 1; level <= 3; level++) {
            String name = "punch" + level;
            PluginCommand pc = Bukkit.getPluginCommand(name);
            if (pc == null || pc.getExecutor() == null) {
                getLogger().severe("Command /" + name + " not found - is LegendaryPunch loaded? Queue NOT active for it.");
                continue;
            }
            final int lv = level;
            CommandExecutor original = pc.getExecutor();
            originals.put(level, original);
            levelCommands.put(level, pc);
            pc.setExecutor((sender, command, label, args) -> onGift(lv, sender, args));
        }

        PluginCommand admin = getCommand("punchqueue");
        if (admin != null) admin.setExecutor(this::onAdmin);

        PluginCommand toggle = getCommand("punchtoggle");
        if (toggle != null) {
            toggle.setExecutor(this::onToggle);
        } else {
            getLogger().severe("/punchtoggle is not defined in plugin.yml");
        }
        restorePunchState();

        task = Bukkit.getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
        PunchLedger.Status s = ledger.status();
        getLogger().info("PunchQueue ready: " + perSecond + " punches/sec (about " + Math.round(perSecond * 3600)
                + " per hour). Recovered " + s.pending() + " pending punch(es) from journal.");
    }

    @Override
    public void onDisable() {
        if (task != null) task.cancel();
        // Restore LegendaryPunch's own executors so a /reload does not stack wrappers.
        for (Map.Entry<Integer, CommandExecutor> e : originals.entrySet()) {
            PluginCommand pc = levelCommands.get(e.getKey());
            if (pc != null) pc.setExecutor(e.getValue());
        }
        if (ledger != null) {
            try {
                ledger.close();
            } catch (IOException e) {
                getLogger().severe("Error closing punch journal: " + e);
            }
            // Deliberately NOT clearing: pending punches stay in the journal for the next start.
            getLogger().info("Shutting down with " + ledger.pending() + " punch(es) still pending (kept on disk).");
        }
    }

    // ------------------------------------------------------------------ intake

    private boolean onGift(int level, CommandSender sender, String[] args) {
        if (!punchesOn) {
            sender.sendMessage("Punches are OFF - punch NOT accepted.");
            return false;
        }
        if (ledger == null) {
            sender.sendMessage("PunchQueue is not running - punch NOT accepted.");
            return false;
        }
        if (args.length < 1 || args.length > 2) {
            sender.sendMessage("Usage: /punch" + level + " <player> [count]");
            return false;
        }
        long count = 1;
        if (args.length == 2) {
            try {
                count = Long.parseLong(args[1]);
            } catch (NumberFormatException e) {
                sender.sendMessage("count must be a whole number");
                return false;
            }
        }
        String viewer = sender.getName();
        if (!PunchLedger.NAME.matcher(viewer).matches()) viewer = "unknown";
        try {
            ledger.enqueue(args[0], viewer, level, count);
            return true;   // silent on success: no console spam for 20k punches
        } catch (IllegalArgumentException e) {
            sender.sendMessage("Punch refused: " + e.getMessage());
            return false;
        } catch (ArithmeticException e) {
            sender.sendMessage("Punch refused: total would overflow");
            return false;
        } catch (IOException e) {
            sender.sendMessage("Punch NOT accepted: journal write failed. Tell the operator.");
            getLogger().severe("[ALARM] Rejected /punch" + level + " for " + args[0] + ": " + e);
            return false;
        }
    }

    // ------------------------------------------------------------------ draining

    private void tick() {
        if (ledger == null) return;
        int budget = pacer.budget();
        if (budget > 0) {
            int used = ledger.drain(budget, this::punchOnce, name -> {
                Player p = Bukkit.getPlayerExact(name);
                return p != null && p.isOnline();
            });
            pacer.spent(used);
        }
        boolean zero = ledger.pending() == 0;
        if (zero && !lastPendingZero) getLogger().info("PunchQueue drained: all accepted punches applied.");
        lastPendingZero = zero;
    }

    /** One physical punch = one call of LegendaryPunch's own executor. */
    private boolean punchOnce(String target, String viewer, int level) throws Exception {
        CommandExecutor original = originals.get(level);
        PluginCommand pc = levelCommands.get(level);
        if (original == null || pc == null) return false;
        return original.onCommand(Bukkit.getConsoleSender(), (Command) pc, "punch" + level, new String[]{target});
    }

    // ------------------------------------------------------------------ punch on/off

    /**
     * /punchtoggle [on|off|toggle]. OFF refuses new punches, and punches already queued still finish.
     * The state is written to the dp_punch scoreboard so the datapack bar shows it.
     */
    private boolean onToggle(CommandSender sender, Command command, String label, String[] args) {
        if (ledger == null) {
            sender.sendMessage("PunchQueue is not running.");
            return true;
        }
        String arg = args.length == 0 ? "toggle" : args[0].toLowerCase();
        boolean on;
        switch (arg) {
            case "on" -> on = true;
            case "off" -> on = false;
            case "toggle" -> on = !punchesOn;
            default -> {
                sender.sendMessage("Usage: /punchtoggle [on|off|toggle]");
                return false;
            }
        }
        punchesOn = on;
        publishPunchState(on);
        sender.sendMessage("Punches: " + (on ? "ON" : "OFF"));
        getLogger().info("Punches set " + (on ? "ON" : "OFF") + " by " + sender.getName());
        return true;
    }

    /** On startup, keep the last ON/OFF state from the scoreboard. A missing score means ON. */
    private void restorePunchState() {
        boolean on = true;
        Objective obj = Bukkit.getScoreboardManager().getMainScoreboard().getObjective(PUNCH_OBJECTIVE);
        if (obj != null && obj.getScore(PUNCH_ENTRY).isScoreSet() && obj.getScore(PUNCH_ENTRY).getScore() == 0) {
            on = false;
        }
        punchesOn = on;
        publishPunchState(on);
        getLogger().info("Punches " + (on ? "ON" : "OFF") + " (restored from scoreboard)");
    }

    private void publishPunchState(boolean on) {
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        Objective obj = board.getObjective(PUNCH_OBJECTIVE);
        if (obj == null) {
            obj = board.registerNewObjective(PUNCH_OBJECTIVE, Criteria.DUMMY,
                    net.kyori.adventure.text.Component.text("PUNCH"));
        }
        obj.getScore(PUNCH_ENTRY).setScore(on ? 1 : 0);
    }

    // ------------------------------------------------------------------ admin

    private boolean onAdmin(CommandSender sender, Command command, String label, String[] args) {
        if (ledger == null) {
            sender.sendMessage("PunchQueue is not running.");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            PunchLedger.Status s = ledger.status();
            sender.sendMessage("[PunchQueue] received=" + s.received() + " executed=" + s.executed()
                    + " pending=" + s.pending() + " failedAttempts=" + s.failedAttempts()
                    + " paused=" + s.paused() + " journalBroken=" + s.journalBroken()
                    + " oldestAgeSec=" + (s.oldestAgeMs() / 1000));
            if (!s.lastError().isEmpty()) sender.sendMessage("[PunchQueue] lastError: " + s.lastError());
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "inspect" -> ledger.pendingByTarget().forEach((t, n) -> sender.sendMessage("  " + t + ": " + n + " pending"));
            case "pause" -> {
                ledger.setPaused(true);
                sender.sendMessage("[PunchQueue] paused (nothing is deleted; intake continues).");
            }
            case "resume" -> {
                ledger.setPaused(false);
                sender.sendMessage("[PunchQueue] resumed.");
            }
            case "add" -> {
                // punchqueue add <level> <viewer> <count> <target>
                if (args.length != 5) {
                    sender.sendMessage("Usage: /punchqueue add <1|2|3> <viewer> <count> <target>");
                    return false;
                }
                try {
                    long seq = ledger.enqueue(args[4], args[2], Integer.parseInt(args[1]), Long.parseLong(args[3]));
                    sender.sendMessage("[PunchQueue] accepted batch #" + seq + " (" + args[3] + " punches for " + args[4] + ")");
                } catch (IOException e) {
                    sender.sendMessage("NOT accepted: journal write failed - " + e.getMessage());
                } catch (RuntimeException e) {
                    sender.sendMessage("NOT accepted: " + e.getMessage());
                }
            }
            default -> sender.sendMessage("Usage: /punchqueue status|inspect|pause|resume|add");
        }
        return true;
    }
}

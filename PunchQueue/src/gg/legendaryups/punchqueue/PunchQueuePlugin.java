package gg.legendaryups.punchqueue;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Takes over the LegendaryPunch commands (punch1/punch2/punch3). Every call is queued, and the queue is
 * drained at a fixed rate on the main thread, so a burst of paid punches all land instead of being dropped.
 *
 * /punchtoggle [on|off|toggle] turns punches on or off. The state is written to the scoreboard objective
 * "dp_punch" (score of "#punch": 1 = on, 0 = off) so the datapack HUD can show it.
 */
public final class PunchQueuePlugin extends JavaPlugin {

    private static final String OBJECTIVE = "dp_punch";
    private static final String ENTRY = "#punch";

    private record Queued(CommandExecutor original, CommandSender sender, Command command, String label, String[] args) {}

    private final Deque<Queued> queue = new ArrayDeque<>();
    private BukkitTask task;
    private int perTick = 1;
    private int drainEveryTicks = 4;
    private double perSecond;
    private double credit;
    private long received;
    private long delivered;
    private boolean punchesOn = true;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        perTick = Math.max(1, getConfig().getInt("punches-per-tick", 1));
        drainEveryTicks = Math.max(1, getConfig().getInt("drain-every-ticks", 4));
        // punches-per-second (e.g. 6) spreads punches evenly. If unset, the old punches-per-tick / drain-every-ticks rate is used.
        double configuredPerSecond = getConfig().getDouble("punches-per-second", 0);
        perSecond = configuredPerSecond > 0 ? configuredPerSecond : perTick * 20.0 / drainEveryTicks;

        for (String name : List.of("punch1", "punch2", "punch3")) {
            PluginCommand pc = Bukkit.getPluginCommand(name);
            if (pc == null || pc.getExecutor() == null) {
                getLogger().severe("Command /" + name + " not found - is LegendaryPunch loaded? Queue NOT active for it.");
                continue;
            }
            CommandExecutor original = pc.getExecutor();
            pc.setExecutor((sender, command, label, args) -> {
                if (!punchesOn) return true; // punches are off: drop silently
                queue.add(new Queued(original, sender, command, label, args.clone()));
                received++;
                return true;
            });
            getLogger().info("Queueing /" + name);
        }

        PluginCommand toggle = Bukkit.getPluginCommand("punchtoggle");
        if (toggle != null) {
            toggle.setExecutor((sender, command, label, args) -> {
                String arg = args.length == 0 ? "toggle" : args[0].toLowerCase();
                switch (arg) {
                    case "on" -> punchesOn = true;
                    case "off" -> punchesOn = false;
                    case "toggle" -> punchesOn = !punchesOn;
                    default -> {
                        sender.sendMessage(net.kyori.adventure.text.Component.text("Usage: /punchtoggle [on|off|toggle]"));
                        return true;
                    }
                }
                publishState();
                sender.sendMessage(net.kyori.adventure.text.Component.text("Punches: " + (punchesOn ? "ON" : "OFF")));
                getLogger().info("Punches set " + (punchesOn ? "ON" : "OFF") + " by " + sender.getName());
                return true;
            });
        } else {
            getLogger().severe("/punchtoggle is not defined in plugin.yml");
        }

        publishState();
        task = Bukkit.getScheduler().runTaskTimer(this, this::drain, 1L, 1L);
        getLogger().info("PunchQueue ready: " + perSecond + " punches/sec evenly spaced, punches " + (punchesOn ? "ON" : "OFF"));
    }

    private void publishState() {
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        Objective obj = board.getObjective(OBJECTIVE);
        if (obj == null) {
            obj = board.registerNewObjective(OBJECTIVE, Criteria.DUMMY, net.kyori.adventure.text.Component.text("PUNCH"));
        }
        obj.getScore(ENTRY).setScore(punchesOn ? 1 : 0);
    }

    // Runs every tick. Earns perSecond/20 punches per tick and delivers whole punches as credit allows,
    // so punches land evenly (6/sec = one every 3 or 4 ticks) instead of in batches.
    private void drain() {
        if (queue.isEmpty()) {
            credit = 0;
            return;
        }
        credit += perSecond / 20.0;
        while (credit >= 1.0 && !queue.isEmpty()) {
            credit -= 1.0;
            Queued q = queue.poll();
            delivered++;
            try {
                q.original().onCommand(q.sender(), q.command(), q.label(), q.args());
            } catch (Throwable t) {
                getLogger().warning("Punch /" + q.label() + " failed: " + t);
            }
        }
        if (queue.isEmpty()) {
            getLogger().info("PunchQueue drained: " + delivered + " of " + received + " punches delivered");
            received = 0;
            delivered = 0;
            credit = 0;
        }
    }

    @Override
    public void onDisable() {
        if (task != null) task.cancel();
        if (!queue.isEmpty()) getLogger().warning("Shutting down with " + queue.size() + " punches still queued (lost)");
        queue.clear();
    }
}

package gg.legendaryups.punchqueue;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Takes over the LegendaryPunch commands (punch1/punch2/punch3). Every call is queued, and the queue is
 * drained at a fixed rate on the main thread, so a burst of paid punches all land instead of being dropped.
 */
public final class PunchQueuePlugin extends JavaPlugin {

    private record Queued(CommandExecutor original, CommandSender sender, Command command, String label, String[] args) {}

    private final Deque<Queued> queue = new ArrayDeque<>();
    private BukkitTask task;
    private int perTick = 1;
    private long received;
    private long delivered;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        perTick = Math.max(1, getConfig().getInt("punches-per-tick", 1));

        for (String name : List.of("punch1", "punch2", "punch3")) {
            PluginCommand pc = Bukkit.getPluginCommand(name);
            if (pc == null || pc.getExecutor() == null) {
                getLogger().severe("Command /" + name + " not found - is LegendaryPunch loaded? Queue NOT active for it.");
                continue;
            }
            CommandExecutor original = pc.getExecutor();
            pc.setExecutor((sender, command, label, args) -> {
                queue.add(new Queued(original, sender, command, label, args.clone()));
                received++;
                return true;
            });
            getLogger().info("Queueing /" + name);
        }

        task = Bukkit.getScheduler().runTaskTimer(this, this::drain, 1L, 1L);
        getLogger().info("PunchQueue ready: " + perTick + " punch(es) per tick (" + (perTick * 20) + "/sec)");
    }

    private void drain() {
        if (queue.isEmpty()) return;
        for (int i = 0; i < perTick && !queue.isEmpty(); i++) {
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
        }
    }

    @Override
    public void onDisable() {
        if (task != null) task.cancel();
        if (!queue.isEmpty()) getLogger().warning("Shutting down with " + queue.size() + " punches still queued (lost)");
        queue.clear();
    }
}

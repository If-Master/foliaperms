package me.IfMasterPluginsPerms.foliaPerms.util;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

public class SchedulerUtil {

    private static boolean isFolia = false;

    static {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            isFolia = true;
        } catch (ClassNotFoundException e) {
            isFolia = false;
        }
    }

    public static boolean isFolia() {
        return isFolia;
    }

    public static void run(JavaPlugin plugin, Location location, Runnable task) {
        if (isFolia) {
            if (location != null) {
                Bukkit.getRegionScheduler().run(plugin, location, (scheduledTask) -> task.run());
            } else {
                Bukkit.getGlobalRegionScheduler().run(plugin, (scheduledTask) -> task.run());
            }
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    public static void runDelayed(JavaPlugin plugin, Location location, Runnable task, long delay) {
        if (isFolia) {
            if (location != null) {
                Bukkit.getRegionScheduler().runDelayed(plugin, location, (scheduledTask) -> task.run(), delay);
            } else {
                Bukkit.getGlobalRegionScheduler().runDelayed(plugin, (scheduledTask) -> task.run(), delay);
            }
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, task, delay);
        }
    }

    public static void runAtFixedRate(JavaPlugin plugin, Runnable task, long delay, long period) {
        if (isFolia) {
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, (scheduledTask) -> task.run(), delay, period);
        } else {
            Bukkit.getScheduler().runTaskTimer(plugin, task, delay, period);
        }
    }

    public static void runAsync(JavaPlugin plugin, Runnable task) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    public static ScheduledTask runGlobalTask(Plugin plugin, Runnable task) {
        if (isFolia) {
            return Bukkit.getGlobalRegionScheduler().run(plugin, scheduledTask -> task.run());
        } else {
            BukkitTask bukkitTask = Bukkit.getScheduler().runTask(plugin, task);
            return new ScheduledTaskWrapper(bukkitTask);
        }
    }
    private static class ScheduledTaskWrapper implements ScheduledTask {
        private final BukkitTask bukkitTask;

        public ScheduledTaskWrapper(BukkitTask bukkitTask) {
            this.bukkitTask = bukkitTask;
        }

        @Override
        public Plugin getOwningPlugin() {
            return bukkitTask.getOwner();
        }

        @Override
        public boolean isRepeatingTask() {
            return false;
        }

        @Override
        public @NotNull CancelledState cancel() {
            bukkitTask.cancel();
            return null;
        }

        @Override
        public boolean isCancelled() {
            return bukkitTask.isCancelled();
        }

        @Override
        public ExecutionState getExecutionState() {
            if (bukkitTask.isCancelled()) {
                return ExecutionState.CANCELLED;
            }
            return ExecutionState.RUNNING;
        }
    }

}
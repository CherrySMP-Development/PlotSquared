/*
 * PlotSquared, a land and world management plugin for Minecraft.
 * Copyright (C) IntellectualSites <https://intellectualsites.com>
 * Copyright (C) IntellectualSites team and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.plotsquared.bukkit.util;

import io.papermc.lib.PaperLib;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Consumer;

/**
 * Reflection-backed Folia compatibility helper that keeps the Bukkit module
 * compiling against non-Folia APIs while using native schedulers when available.
 */
public final class FoliaCompat {

    private static boolean checked;
    private static boolean folia;

    private static Method bukkit_isGlobalTickThread;
    private static Method server_getGlobalRegionScheduler;
    private static Method server_getAsyncScheduler;
    private static Method server_getRegionScheduler;
    private static Method global_run;
    private static Method global_runDelayed;
    private static Method global_runAtFixedRate;
    private static Method async_runNow;
    private static Method async_runDelayed;
    private static Method async_runAtFixedRate;
    private static Method region_run;
    private static Method entity_getScheduler;
    private static Method entityScheduler_run;
    private static Method cancellable_cancel;

    private FoliaCompat() {
    }

    public static boolean isFolia() {
        check();
        return folia;
    }

    public static boolean isGlobalTickThread() {
        check();
        if (!folia) {
            return Bukkit.isPrimaryThread();
        }
        if (bukkit_isGlobalTickThread == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(bukkit_isGlobalTickThread.invoke(null));
        } catch (final ReflectiveOperationException ignored) {
            return false;
        }
    }

    public static void runGlobal(final @NonNull JavaPlugin plugin, final @NonNull Runnable task) {
        if (!isFolia()) {
            Bukkit.getScheduler().runTask(plugin, task);
            return;
        }
        try {
            final Object scheduler = server_getGlobalRegionScheduler.invoke(Bukkit.getServer());
            global_run.invoke(scheduler, plugin, consumer(task));
        } catch (final Throwable ignored) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    public static void runGlobalLater(
            final @NonNull JavaPlugin plugin,
            final @NonNull Runnable task,
            final long delayTicks
    ) {
        if (!isFolia()) {
            Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
            return;
        }
        try {
            final Object scheduler = server_getGlobalRegionScheduler.invoke(Bukkit.getServer());
            global_runDelayed.invoke(scheduler, plugin, consumer(task), delayTicks);
        } catch (final Throwable ignored) {
            Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
        }
    }

    public static @Nullable Object runGlobalRepeating(
            final @NonNull JavaPlugin plugin,
            final @NonNull Runnable task,
            final long initialDelayTicks,
            final long periodTicks
    ) {
        if (!isFolia()) {
            return Bukkit.getScheduler().runTaskTimer(plugin, task, initialDelayTicks, periodTicks);
        }
        try {
            final Object scheduler = server_getGlobalRegionScheduler.invoke(Bukkit.getServer());
            return global_runAtFixedRate.invoke(scheduler, plugin, consumer(task), initialDelayTicks, periodTicks);
        } catch (final Throwable ignored) {
            return Bukkit.getScheduler().runTaskTimer(plugin, task, initialDelayTicks, periodTicks);
        }
    }

    public static void runAsync(final @NonNull JavaPlugin plugin, final @NonNull Runnable task) {
        if (!isFolia()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
            return;
        }
        try {
            final Object scheduler = server_getAsyncScheduler.invoke(Bukkit.getServer());
            async_runNow.invoke(scheduler, plugin, consumer(task));
        } catch (final Throwable ignored) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        }
    }

    public static void runAsyncLater(
            final @NonNull JavaPlugin plugin,
            final @NonNull Runnable task,
            final long delayTicks
    ) {
        if (!isFolia()) {
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, delayTicks);
            return;
        }
        try {
            final Object scheduler = server_getAsyncScheduler.invoke(Bukkit.getServer());
            async_runDelayed.invoke(scheduler, plugin, consumer(task), ticksToMillis(delayTicks), TimeUnit.MILLISECONDS);
        } catch (final Throwable ignored) {
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, delayTicks);
        }
    }

    public static @Nullable Object runAsyncRepeating(
            final @NonNull JavaPlugin plugin,
            final @NonNull Runnable task,
            final long initialDelayTicks,
            final long periodTicks
    ) {
        if (!isFolia()) {
            return Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, initialDelayTicks, periodTicks);
        }
        try {
            final Object scheduler = server_getAsyncScheduler.invoke(Bukkit.getServer());
            return async_runAtFixedRate.invoke(
                    scheduler,
                    plugin,
                    consumer(task),
                    ticksToMillis(initialDelayTicks),
                    ticksToMillis(periodTicks),
                    TimeUnit.MILLISECONDS
            );
        } catch (final Throwable ignored) {
            return Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, initialDelayTicks, periodTicks);
        }
    }

    public static void runAtLocation(
            final @NonNull Plugin plugin,
            final @NonNull Location location,
            final @NonNull Runnable task
    ) {
        if (!isFolia()) {
            Bukkit.getScheduler().runTask((JavaPlugin) plugin, task);
            return;
        }
        try {
            final Object scheduler = server_getRegionScheduler.invoke(Bukkit.getServer());
            region_run.invoke(scheduler, plugin, location, consumer(task));
        } catch (final Throwable ignored) {
            Bukkit.getScheduler().runTask((JavaPlugin) plugin, task);
        }
    }

    public static void teleportEntity(
            final @NonNull Plugin plugin,
            final @NonNull Entity entity,
            final @NonNull Location location
    ) {
        if (entity instanceof final Player player) {
            teleportPlayer(plugin, player, location, PlayerTeleportEvent.TeleportCause.PLUGIN);
            return;
        }
        if (!isFolia()) {
            PaperLib.teleportAsync(entity, location);
            return;
        }
        try {
            final Object scheduler = entity_getScheduler.invoke(entity);
            entityScheduler_run.invoke(scheduler, plugin, consumer(() -> entity.teleport(location)), null);
        } catch (final Throwable ignored) {
            PaperLib.teleportAsync(entity, location);
        }
    }

    public static void runAtEntity(
            final @NonNull Plugin plugin,
            final @NonNull Entity entity,
            final @NonNull Runnable task
    ) {
        if (!isFolia()) {
            Bukkit.getScheduler().runTask((JavaPlugin) plugin, task);
            return;
        }
        try {
            final Object scheduler = entity_getScheduler.invoke(entity);
            entityScheduler_run.invoke(scheduler, plugin, consumer(task), null);
        } catch (final Throwable ignored) {
            Bukkit.getScheduler().runTask((JavaPlugin) plugin, task);
        }
    }

    public static <T> @NonNull T callAtEntity(
            final @NonNull Plugin plugin,
            final @NonNull Entity entity,
            final @NonNull Function<Entity, T> task
    ) throws ExecutionException, InterruptedException {
        if (!isFolia()) {
            return task.apply(entity);
        }
        final CompletableFuture<T> future = new CompletableFuture<>();
        runAtEntity(plugin, entity, () -> {
            try {
                future.complete(task.apply(entity));
            } catch (final Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future.get();
    }

    public static void teleportPlayer(
            final @NonNull Plugin plugin,
            final @NonNull Player player,
            final @NonNull Location location,
            final PlayerTeleportEvent.TeleportCause cause
    ) {
        if (!isFolia()) {
            PaperLib.teleportAsync(player, location, cause);
            return;
        }
        try {
            final Object scheduler = entity_getScheduler.invoke(player);
            entityScheduler_run.invoke(scheduler, plugin, consumer(() -> player.teleport(location, cause)), null);
        } catch (final Throwable ignored) {
            PaperLib.teleportAsync(player, location, cause);
        }
    }

    public static void cancelTask(final @Nullable Object task) {
        if (task == null) {
            return;
        }
        try {
            if (task instanceof final BukkitTask bukkitTask) {
                bukkitTask.cancel();
                return;
            }
            final Method cancel = cancellable_cancel != null ? cancellable_cancel : task.getClass().getMethod("cancel");
            cancel.invoke(task);
        } catch (final Throwable ignored) {
        }
    }

    private static void check() {
        if (checked) {
            return;
        }
        checked = true;
        try {
            server_getGlobalRegionScheduler = Bukkit.getServer().getClass().getMethod("getGlobalRegionScheduler");
            server_getAsyncScheduler = Bukkit.getServer().getClass().getMethod("getAsyncScheduler");
            server_getRegionScheduler = Bukkit.getServer().getClass().getMethod("getRegionScheduler");
            bukkit_isGlobalTickThread = findStaticNoArgsMethod(Bukkit.class, "isGlobalTickThread");

            final Object globalScheduler = server_getGlobalRegionScheduler.invoke(Bukkit.getServer());
            final Object asyncScheduler = server_getAsyncScheduler.invoke(Bukkit.getServer());
            final Object regionScheduler = server_getRegionScheduler.invoke(Bukkit.getServer());

            global_run = findMethod(globalScheduler.getClass(), "run", Plugin.class, Consumer.class);
            global_runDelayed = findMethod(globalScheduler.getClass(), "runDelayed", Plugin.class, Consumer.class, long.class);
            global_runAtFixedRate = findMethod(
                    globalScheduler.getClass(),
                    "runAtFixedRate",
                    Plugin.class,
                    Consumer.class,
                    long.class,
                    long.class
            );
            async_runNow = findMethod(asyncScheduler.getClass(), "runNow", Plugin.class, Consumer.class);
            async_runDelayed = findMethod(asyncScheduler.getClass(), "runDelayed", Plugin.class, Consumer.class, long.class, TimeUnit.class);
            async_runAtFixedRate = findMethod(
                    asyncScheduler.getClass(),
                    "runAtFixedRate",
                    Plugin.class,
                    Consumer.class,
                    long.class,
                    long.class,
                    TimeUnit.class
            );
            region_run = findMethod(regionScheduler.getClass(), "run", Plugin.class, Location.class, Consumer.class);
            entity_getScheduler = Entity.class.getMethod("getScheduler");
            folia = global_run != null
                    && global_runDelayed != null
                    && global_runAtFixedRate != null
                    && async_runNow != null
                    && async_runDelayed != null
                    && async_runAtFixedRate != null
                    && region_run != null;
        } catch (final Throwable ignored) {
            folia = false;
        }
        if (entityScheduler_run == null) {
            try {
                for (final Method method : Class.forName("io.papermc.paper.threadedregions.scheduler.EntityScheduler").getMethods()) {
                    if (!"run".equals(method.getName()) || method.getParameterCount() != 3) {
                        continue;
                    }
                    entityScheduler_run = method;
                    break;
                }
            } catch (final Throwable ignored) {
                folia = false;
            }
        }
    }

    private static @Nullable Method findStaticNoArgsMethod(final @NonNull Class<?> type, final @NonNull String name) {
        try {
            return type.getMethod(name);
        } catch (final NoSuchMethodException ignored) {
            return null;
        }
    }

    private static @Nullable Method findMethod(
            final @NonNull Class<?> owner,
            final @NonNull String name,
            final @NonNull Class<?>... parameters
    ) {
        for (final Method method : owner.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != parameters.length) {
                continue;
            }
            final Class<?>[] actualParameters = method.getParameterTypes();
            boolean match = true;
            for (int i = 0; i < actualParameters.length; i++) {
                if (!actualParameters[i].isAssignableFrom(parameters[i])) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return method;
            }
        }
        return null;
    }

    private static @NonNull Consumer<Object> consumer(final @NonNull Runnable runnable) {
        return ignored -> runnable.run();
    }

    private static long ticksToMillis(final long ticks) {
        return ticks * 50L;
    }

}

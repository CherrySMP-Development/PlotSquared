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
package com.plotsquared.bukkit.player;

import com.google.common.base.Charsets;
import com.plotsquared.bukkit.BukkitPlatform;
import com.plotsquared.bukkit.util.FoliaCompat;
import com.plotsquared.bukkit.util.BukkitUtil;
import com.plotsquared.core.PlotSquared;
import com.plotsquared.core.configuration.Settings;
import com.plotsquared.core.events.TeleportCause;
import com.plotsquared.core.location.Location;
import com.plotsquared.core.permissions.Permission;
import com.plotsquared.core.permissions.PermissionHandler;
import com.plotsquared.core.player.ConsolePlayer;
import com.plotsquared.core.player.MetaDataAccess;
import com.plotsquared.core.player.PlayerMetaDataKeys;
import com.plotsquared.core.player.PlotPlayer;
import com.plotsquared.core.plot.PlotWeather;
import com.plotsquared.core.plot.world.PlotAreaManager;
import com.plotsquared.core.util.EventDispatcher;
import com.plotsquared.core.util.MathMan;
import com.plotsquared.core.util.MinecraftVersion;
import com.plotsquared.core.util.WorldUtil;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.world.item.ItemType;
import com.sk89q.worldedit.world.item.ItemTypes;
import net.kyori.adventure.audience.Audience;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.WeatherType;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.potion.PotionEffectType;
import org.checkerframework.checker.index.qual.NonNegative;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.concurrent.ExecutionException;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.sk89q.worldedit.world.gamemode.GameModes.ADVENTURE;
import static com.sk89q.worldedit.world.gamemode.GameModes.CREATIVE;
import static com.sk89q.worldedit.world.gamemode.GameModes.SPECTATOR;
import static com.sk89q.worldedit.world.gamemode.GameModes.SURVIVAL;

public class BukkitPlayer extends PlotPlayer<Player> {

    private static boolean CHECK_EFFECTIVE = true;
    private static final String ADMIN_PERMISSION = Permission.PERMISSION_ADMIN.toString();
    private static final String PERMISSION_WILDCARD = Permission.PERMISSION_STAR.toString();
    private static final Sound[] MUSIC_DISC_SOUNDS = Arrays.stream(Sound.values())
            .filter(sound -> sound.name().startsWith("MUSIC_DISC"))
            .toArray(Sound[]::new);
    public final Player player;
    private String name;

    /**
     * @param plotAreaManager   PlotAreaManager instance
     * @param eventDispatcher   EventDispatcher instance
     * @param player            Bukkit player instance
     * @param permissionHandler PermissionHandler instance
     */
    BukkitPlayer(
            final @NonNull PlotAreaManager plotAreaManager,
            final @NonNull EventDispatcher eventDispatcher,
            final @NonNull Player player,
            final boolean realPlayer,
            final @NonNull PermissionHandler permissionHandler
    ) {
        super(plotAreaManager, eventDispatcher, permissionHandler);
        this.player = player;
        this.setupPermissionProfile();
        if (realPlayer) {
            super.populatePersistentMetaMap();
        }
    }

    @Override
    public Actor toActor() {
        return BukkitAdapter.adapt(player);
    }

    @Override
    public Player getPlatformPlayer() {
        return this.player;
    }

    @NonNull
    @Override
    public UUID getUUID() {
        if (Settings.UUID.OFFLINE) {
            if (Settings.UUID.FORCE_LOWERCASE) {
                return UUID.nameUUIDFromBytes(("OfflinePlayer:" +
                        getName().toLowerCase()).getBytes(Charsets.UTF_8));
            } else {
                return UUID.nameUUIDFromBytes(("OfflinePlayer:" +
                        getName()).getBytes(Charsets.UTF_8));
            }
        }
        return player.getUniqueId();
    }

    @Override
    @NonNegative
    public long getLastPlayed() {
        return this.player.getLastSeen();
    }

    @Override
    public boolean canTeleport(final @NonNull Location location) {
        if (!WorldUtil.isValidLocation(location)) {
            return false;
        }
        return this.callOnPlayer(player -> {
            final org.bukkit.Location to = BukkitUtil.adapt(location);
            final org.bukkit.Location from = player.getLocation();
            PlayerTeleportEvent event = new PlayerTeleportEvent(player, from, to);
            callEvent(event);
            if (event.isCancelled() || !event.getTo().equals(to)) {
                return false;
            }
            event = new PlayerTeleportEvent(player, to, from);
            callEvent(event);
            return true;
        });
    }

    private void callEvent(final @NonNull Event event) {
        final String platformPluginName = PlotSquared.platform().pluginName();
        final RegisteredListener[] listeners = event.getHandlers().getRegisteredListeners();
        for (final RegisteredListener listener : listeners) {
            if (listener.getPlugin().getName().equals(platformPluginName)) {
                continue;
            }
            try {
                listener.callEvent(event);
            } catch (final EventException e) {
                e.printStackTrace();
            }
        }
    }

    @SuppressWarnings("StringSplitter")
    @Override
    @NonNegative
    public int hasPermissionRange(
            final @NonNull String stub,
            @NonNegative final int range
    ) {
        if (hasPermission(ADMIN_PERMISSION)) {
            return Integer.MAX_VALUE;
        }
        final String wildcardPermission = stub + "." + PERMISSION_WILDCARD;
        final StringBuilder prefixBuilder = new StringBuilder(stub.length() + PERMISSION_WILDCARD.length() + 1);
        // Wildcard check from less specific permission to more specific permission without regex splitting.
        int searchStart = 0;
        int dotIndex;
        while ((dotIndex = stub.indexOf('.', searchStart)) != -1) {
            prefixBuilder.append(stub, searchStart, dotIndex + 1);
            final String candidate = prefixBuilder + PERMISSION_WILDCARD;
            if (!wildcardPermission.equals(candidate) && hasPermission(candidate)) {
                return Integer.MAX_VALUE;
            }
            searchStart = dotIndex + 1;
        }
        // Wildcard check for the full permission
        if (hasPermission(wildcardPermission)) {
            return Integer.MAX_VALUE;
        }
        // Permission value cache for iterative check
        int max = 0;
        if (CHECK_EFFECTIVE) {
            boolean hasAny = false;
            final String stubPlus = stub + ".";
            final Set<PermissionAttachmentInfo> effective = player.getEffectivePermissions();
            if (!effective.isEmpty()) {
                for (final PermissionAttachmentInfo attach : effective) {
                    // Ignore all "false" permissions
                    if (!attach.getValue()) {
                        continue;
                    }
                    final String permStr = attach.getPermission();
                    if (permStr.startsWith(stubPlus)) {
                        hasAny = true;
                        final String end = permStr.substring(stubPlus.length());
                        if (MathMan.isInteger(end)) {
                            final int val = Integer.parseInt(end);
                            if (val > range) {
                                return val;
                            }
                            if (val > max) {
                                max = val;
                            }
                        }
                    }
                }
                if (hasAny) {
                    return max;
                }
                // Workaround
                for (final PermissionAttachmentInfo attach : effective) {
                    final String permStr = attach.getPermission();
                    if (permStr.startsWith("plots.") && !permStr.equals("plots.use")) {
                        return max;
                    }
                }
                CHECK_EFFECTIVE = false;
            }
        }
        final String numericPrefix = stub + ".";
        for (int i = range; i > 0; i--) {
            if (hasPermission(numericPrefix + i)) {
                return i;
            }
        }
        return max;
    }

    @Override
    public void teleport(final @NonNull Location location, final @NonNull TeleportCause cause) {
        if (!WorldUtil.isValidLocation(location)) {
            return;
        }
        final org.bukkit.Location bukkitLocation =
                new org.bukkit.Location(BukkitUtil.getWorld(location.getWorldName()), location.getX() + 0.5,
                        location.getY(), location.getZ() + 0.5, location.getYaw(), location.getPitch()
                );
        FoliaCompat.teleportPlayer(
                BukkitPlatform.getPlugin(BukkitPlatform.class),
                player,
                bukkitLocation,
                getTeleportCause(cause)
        );
    }

    @Override
    public String getName() {
        if (this.name == null) {
            this.name = this.player.getName();
        }
        return this.name;
    }

    @Override
    public void setCompassTarget(Location location) {
        final org.bukkit.Location compassTarget = new org.bukkit.Location(
                BukkitUtil.getWorld(location.getWorldName()),
                location.getX(),
                location.getY(),
                location.getZ()
        );
        this.runOnPlayer(player -> player.setCompassTarget(compassTarget));
    }

    @Override
    public Location getLocationFull() {
        if (FoliaCompat.isFolia() && !FoliaCompat.isOwnedByCurrentRegion(this.player)) {
            try (final MetaDataAccess<Location> locationAccess =
                         this.accessTemporaryMetaData(PlayerMetaDataKeys.TEMPORARY_LOCATION)) {
                final Location cachedLocation = locationAccess.get().orElse(null);
                if (cachedLocation != null) {
                    return cachedLocation;
                }
            }
        }
        return this.callOnPlayer(player -> BukkitUtil.adaptComplete(player.getLocation()));
    }

    @Override
    public void setWeather(final @NonNull PlotWeather weather) {
        this.runOnPlayer(player -> {
            switch (weather) {
                case CLEAR -> player.setPlayerWeather(WeatherType.CLEAR);
                case RAIN -> player.setPlayerWeather(WeatherType.DOWNFALL);
                case WORLD -> player.resetPlayerWeather();
                default -> {
                    // do nothing as this is PlotWeather.OFF
                }
            }
        });
    }

    @Override
    public com.sk89q.worldedit.world.gamemode.GameMode getGameMode() {
        return this.callOnPlayer(player -> switch (player.getGameMode()) {
            case ADVENTURE -> ADVENTURE;
            case CREATIVE -> CREATIVE;
            case SPECTATOR -> SPECTATOR;
            default -> SURVIVAL;
        });
    }

    @Override
    public void setGameMode(final com.sk89q.worldedit.world.gamemode.GameMode gameMode) {
        this.runOnPlayer(player -> {
            if (ADVENTURE.equals(gameMode)) {
                player.setGameMode(GameMode.ADVENTURE);
            } else if (CREATIVE.equals(gameMode)) {
                player.setGameMode(GameMode.CREATIVE);
            } else if (SPECTATOR.equals(gameMode)) {
                player.setGameMode(GameMode.SPECTATOR);
            } else {
                player.setGameMode(GameMode.SURVIVAL);
            }
        });
    }

    @Override
    public void setTime(final long time) {
        this.runOnPlayer(player -> {
            if (time != Long.MAX_VALUE) {
                player.setPlayerTime(time, false);
            } else {
                player.resetPlayerTime();
            }
        });
    }

    @Override
    public boolean getFlight() {
        return this.callOnPlayer(Player::getAllowFlight);
    }

    @Override
    public void setFlight(boolean fly) {
        this.runOnPlayer(player -> player.setAllowFlight(fly));
    }

    @Override
    public void playMusic(final @NonNull Location location, final @NonNull ItemType id) {
        this.runOnPlayer(player -> {
            if (id == ItemTypes.AIR) {
                if (MinecraftVersion.current().isOlderOrEqualThan(MinecraftVersion.THE_WILD_UPDATE)) {
                    player.stopSound(SoundCategory.MUSIC);
                    return;
                }
                // 1.18 and downwards require a specific Sound to stop (even tho the packet does not??)
                for (final Sound sound : MUSIC_DISC_SOUNDS) {
                    player.stopSound(sound, SoundCategory.MUSIC);
                }
                return;
            }
            player.playSound(
                    BukkitUtil.adapt(location),
                    Sound.valueOf(BukkitAdapter.adapt(id).name()),
                    SoundCategory.MUSIC,
                    Float.MAX_VALUE,
                    1f
            );
        });
    }

    @SuppressWarnings("deprecation") // Needed for Spigot compatibility
    @Override
    public void kick(final String message) {
        this.player.kickPlayer(message);
    }

    @Override
    public void stopSpectating() {
        this.runOnPlayer(player -> {
            if (player.getGameMode() == GameMode.SPECTATOR) {
                player.setSpectatorTarget(null);
            }
        });
    }

    @Override
    public boolean isBanned() {
        return this.player.isBanned();
    }

    @Override
    public @NonNull Audience getAudience() {
        return BukkitUtil.BUKKIT_AUDIENCES.player(this.player);
    }

    @Override
    public void removeEffect(@NonNull String name) {
        PotionEffectType type = PotionEffectType.getByName(name);
        if (type != null) {
            this.runOnPlayer(player -> player.removePotionEffect(type));
        }
    }

    @Override
    public boolean canSee(final PlotPlayer<?> other) {
        if (other instanceof ConsolePlayer) {
            return true;
        } else {
            return this.callOnPlayer(player -> player.canSee(((BukkitPlayer) other).getPlatformPlayer()));
        }
    }

    private void runOnPlayer(final @NonNull Consumer<Player> task) {
        FoliaCompat.runAtEntity(BukkitPlatform.getPlugin(BukkitPlatform.class), this.player, () -> task.accept(this.player));
    }

    private <T> T callOnPlayer(final @NonNull Function<Player, T> task) {
        if (!FoliaCompat.isFolia()) {
            return task.apply(this.player);
        }
        try {
            return FoliaCompat.callAtEntity(
                    BukkitPlatform.getPlugin(BukkitPlatform.class),
                    this.player,
                    entity -> task.apply((Player) entity)
            );
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (final ExecutionException e) {
            throw new RuntimeException(e.getCause() == null ? e : e.getCause());
        }
    }

    /**
     * Convert from PlotSquared's {@link TeleportCause} to Bukkit's {@link PlayerTeleportEvent.TeleportCause}
     *
     * @param cause PlotSquared teleport cause to convert
     * @return Bukkit's equivalent teleport cause
     */
    public PlayerTeleportEvent.TeleportCause getTeleportCause(final @NonNull TeleportCause cause) {
        if (TeleportCause.CauseSets.COMMAND.contains(cause)) {
            return PlayerTeleportEvent.TeleportCause.COMMAND;
        } else if (cause == TeleportCause.UNKNOWN) {
            return PlayerTeleportEvent.TeleportCause.UNKNOWN;
        }
        return PlayerTeleportEvent.TeleportCause.PLUGIN;
    }

}

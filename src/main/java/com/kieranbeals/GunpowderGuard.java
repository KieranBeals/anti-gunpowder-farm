package com.kieranbeals;

import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GunpowderGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger(
        "anti-gunpowder-farm"
    );

    private static final long COOLDOWN_MS = 60_000L;

    private static final int BUCKET_BLOCKS = 3;
    private static final int SCAN_RADIUS_BLOCKS = 2;
    private static final int SCAN_TICKS = 2;

    private static final Map<String, Long> LAST_AT_LOCATION_MS =
        new ConcurrentHashMap<>();

    private static final Queue<ScanRequest> queue =
        new ConcurrentLinkedQueue<>();

    private static final class ScanRequest {

        final ServerLevel level;
        final BlockPos pos;
        final long expireGameTime;

        final String dimShort;
        final String locationKey;
        final String mobId;

        final String killer; // player name or "none"
        final String cause; // source.getMsgId()
        final String blockReason; // "nonPlayer" | "cooldown" | "nonPlayer+cooldown"
        final long sinceLastMs; // -1 if unknown

        int removedGunpowder = 0;
        boolean logged = false;

        ScanRequest(
            ServerLevel level,
            BlockPos pos,
            long expireGameTime,
            String dimShort,
            String locationKey,
            String mobId,
            String killer,
            String cause,
            String blockReason,
            long sinceLastMs
        ) {
            this.level = level;
            this.pos = pos;
            this.expireGameTime = expireGameTime;
            this.dimShort = dimShort;
            this.locationKey = locationKey;
            this.mobId = mobId;
            this.killer = killer;
            this.cause = cause;
            this.blockReason = blockReason;
            this.sinceLastMs = sinceLastMs;
        }
    }

    public static void register() {
        ServerLivingEntityEvents.AFTER_DEATH.register(
            (LivingEntity entity, DamageSource source) -> {
                if (!(entity.level() instanceof ServerLevel level)) return;
                if (!isGunpowderMob(entity)) return;

                BlockPos pos = entity.blockPosition();
                String key = locationKey(level, pos);

                long nowMs = System.currentTimeMillis();
                Long last = LAST_AT_LOCATION_MS.get(key);
                long sinceLast = last == null ? -1L : (nowMs - last);
                boolean onCooldown = last != null && sinceLast < COOLDOWN_MS;

                KillInfo ki = killInfo(source);

                boolean playerKill = ki.killerPlayerName != null;
                boolean blockGunpowder = !playerKill || onCooldown;

                // Always update timestamp (even if blocked / even if nothing drops)
                LAST_AT_LOCATION_MS.put(key, nowMs);

                if (!blockGunpowder) return;

                String reason;
                if (!playerKill && onCooldown) reason = "nonPlayer+cooldown";
                else if (!playerKill) reason = "nonPlayer";
                else reason = "cooldown";

                long nowTick = level.getGameTime();
                queue.add(
                    new ScanRequest(
                        level,
                        pos,
                        nowTick + SCAN_TICKS,
                        shortDim(level),
                        key,
                        entity.getType().toString(),
                        playerKill ? ki.killerPlayerName : "none",
                        ki.cause,
                        reason,
                        sinceLast
                    )
                );

                cleanupOldCooldowns(nowMs);
            }
        );

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            Iterator<ScanRequest> it = queue.iterator();
            while (it.hasNext()) {
                ScanRequest req = it.next();

                if (req.level.getServer() != server) {
                    it.remove();
                    continue;
                }

                long nowTick = req.level.getGameTime();

                AABB box = new AABB(req.pos).inflate(SCAN_RADIUS_BLOCKS);
                for (ItemEntity itemEntity : req.level.getEntitiesOfClass(
                    ItemEntity.class,
                    box
                )) {
                    ItemStack stack = itemEntity.getItem();
                    if (stack.isEmpty()) continue;

                    if (stack.getItem() == Items.GUNPOWDER) {
                        req.removedGunpowder += stack.getCount();
                        itemEntity.discard(); // delete the dropped gunpowder
                    }
                }

                if (nowTick >= req.expireGameTime) {
                    // Only log if we actually removed something
                    if (!req.logged && req.removedGunpowder > 0) {
                        // one clean line, everything important
                        LOGGER.info(
                            "[AntiGP] removedGunpowder={} mob={} pos={},{},{} dim={} reason={} killer={} cause={} key={} sinceMs={}",
                            req.removedGunpowder,
                            req.mobId,
                            req.pos.getX(),
                            req.pos.getY(),
                            req.pos.getZ(),
                            req.dimShort,
                            req.blockReason,
                            req.killer,
                            req.cause,
                            req.locationKey,
                            req.sinceLastMs
                        );
                        req.logged = true;
                    }

                    it.remove();
                }
            }
        });
    }

    private static boolean isGunpowderMob(LivingEntity e) {
        return e instanceof Creeper || e instanceof Ghast || e instanceof Witch;
    }

    private static final class KillInfo {

        final String killerPlayerName; // null if not player
        final String cause; // source msgId, e.g. "player", "drown", "fall"

        KillInfo(String killerPlayerName, String cause) {
            this.killerPlayerName = killerPlayerName;
            this.cause = cause;
        }
    }

    private static KillInfo killInfo(DamageSource source) {
        if (source == null) return new KillInfo(null, "unknown");

        String cause = source.getMsgId();

        Entity attacker = source.getEntity();
        if (attacker instanceof Player p && !p.isSpectator()) {
            // FIX: GameProfile.name() instead of .getName()
            return new KillInfo(p.getGameProfile().name(), cause);
        }

        Entity direct = source.getDirectEntity();
        if (direct instanceof Projectile proj) {
            Entity owner = proj.getOwner();
            if (owner instanceof Player p && !p.isSpectator()) {
                // FIX: GameProfile.name() instead of .getName()
                return new KillInfo(p.getGameProfile().name(), cause);
            }
        }

        return new KillInfo(null, cause);
    }

    private static String locationKey(ServerLevel level, BlockPos pos) {
        int bx = Math.floorDiv(pos.getX(), BUCKET_BLOCKS);
        int bz = Math.floorDiv(pos.getZ(), BUCKET_BLOCKS);
        return shortDim(level) + ":" + bx + ":" + bz;
    }

    private static String shortDim(ServerLevel level) {
        String raw = level.dimension().toString();
        int idx = raw.lastIndexOf(" / ");
        if (idx != -1) {
            String tail = raw.substring(idx + 3);
            if (tail.endsWith("]")) tail = tail.substring(0, tail.length() - 1);
            return tail;
        }
        return raw;
    }

    private static void cleanupOldCooldowns(long nowMs) {
        if (LAST_AT_LOCATION_MS.size() < 20000) return;
        long cutoff = nowMs - (10 * 60_000L);
        LAST_AT_LOCATION_MS.entrySet().removeIf(e -> e.getValue() < cutoff);
    }
}

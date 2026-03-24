package com.kieranbeals;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;

public final class GunpowderGuard {

    static final int  PLAYER_MAX_KILLS  = 50;
    static final long PLAYER_RAMP_UP_MS = 15 * 60_000L;
    static final int  CHUNK_MAX_KILLS   =  3;
    static final long CHUNK_RAMP_UP_MS  =  5 * 60_000L;

    static final ConcurrentHashMap<UUID, TokenBucket> PLAYER_BUCKETS =
        new ConcurrentHashMap<>();

    static final ConcurrentHashMap<ResourceKey<Level>, ConcurrentHashMap<Long, TokenBucket>> CHUNK_BUCKETS =
        new ConcurrentHashMap<>();

    static final class TokenBucket {

        private final int    maxKills;
        private final double refillRate;
        private double tokens;
        private long   lastUpdatedMs;

        TokenBucket(int maxKills, long rampUpMs, long nowMs) {
            this.maxKills      = maxKills;
            this.refillRate    = (double) maxKills / rampUpMs;
            this.tokens        = maxKills;
            this.lastUpdatedMs = nowMs;
        }

        void refill(long nowMs) {
            long elapsed = nowMs - lastUpdatedMs;
            tokens        = Math.min(maxKills, tokens + elapsed * refillRate);
            lastUpdatedMs = nowMs;
        }

        boolean isFull()    { return tokens >= maxKills; }
        boolean hasToken()  { return tokens >= 1.0; }
        void    consume()   { tokens -= 1.0; }
        double  getTokens() { return tokens; }
    }

    /**
     * Called from the mixin before loot is dropped.
     * Returns true to allow the loot table to run, false to cancel it.
     */
    public static boolean allowLootDrop(LivingEntity entity, ServerLevel level, DamageSource source) {
        if (!isGunpowderMob(entity)) return true;

        KillInfo ki    = killInfo(source);
        BlockPos pos   = entity.blockPosition();
        String   mobId = entity.getType().toString();
        long     nowMs = System.currentTimeMillis();

        if (ki.killerUUID == null) {
            log(level, pos, mobId, "none", ki.cause, "nonPlayer", -1.0, -1.0);
            return false;
        }

        UUID   uuid = ki.killerUUID;
        int    cx   = pos.getX() >> 4;
        int    cz   = pos.getZ() >> 4;
        long   ck   = chunkKey(cx, cz);
        ResourceKey<Level> dim = level.dimension();

        // --- Player bucket (absent = full) ---
        TokenBucket pb = PLAYER_BUCKETS.get(uuid);
        if (pb != null) {
            pb.refill(nowMs);
            if (pb.isFull()) { PLAYER_BUCKETS.remove(uuid); pb = null; }
        }
        boolean playerHasToken = pb == null || pb.hasToken();
        double  playerTokens   = pb == null ? PLAYER_MAX_KILLS : pb.getTokens();

        // --- Chunk bucket (absent = full) ---
        ConcurrentHashMap<Long, TokenBucket> chunkMap =
            CHUNK_BUCKETS.computeIfAbsent(dim, k -> new ConcurrentHashMap<>());
        TokenBucket cb = chunkMap.get(ck);
        if (cb != null) {
            cb.refill(nowMs);
            if (cb.isFull()) { chunkMap.remove(ck); cb = null; }
        }
        boolean chunkHasToken = cb == null || cb.hasToken();
        double  chunkTokens   = cb == null ? CHUNK_MAX_KILLS : cb.getTokens();

        // --- Decision ---
        if (playerHasToken && chunkHasToken) {
            if (pb == null) {
                pb = new TokenBucket(PLAYER_MAX_KILLS, PLAYER_RAMP_UP_MS, nowMs);
                pb.consume();
                PLAYER_BUCKETS.put(uuid, pb);
            } else {
                pb.consume();
            }
            if (cb == null) {
                cb = new TokenBucket(CHUNK_MAX_KILLS, CHUNK_RAMP_UP_MS, nowMs);
                cb.consume();
                chunkMap.put(ck, cb);
            } else {
                cb.consume();
            }
            return true;
        }

        String reason;
        if (!playerHasToken && !chunkHasToken) reason = "bothRateLimit";
        else if (!playerHasToken)               reason = "playerRateLimit";
        else                                    reason = "chunkRateLimit";

        log(level, pos, mobId, ki.killerPlayerName, ki.cause, reason, playerTokens, chunkTokens);
        return false;
    }

    private static void log(
        ServerLevel level, BlockPos pos,
        String mobId, String killer, String cause,
        String reason, double playerTokens, double chunkTokens
    ) {
        AntiGunpowderFarm.LOGGER.info(
            "[AntiGP] blocked mob={} pos={},{},{} dim={} reason={} killer={} cause={} playerTokens={} chunkTokens={}",
            mobId,
            pos.getX(), pos.getY(), pos.getZ(),
            shortDim(level),
            reason, killer, cause,
            playerTokens, chunkTokens
        );
    }

    private static boolean isGunpowderMob(LivingEntity e) {
        return e instanceof Creeper || e instanceof Ghast || e instanceof Witch;
    }

    private static final class KillInfo {

        final UUID   killerUUID;
        final String killerPlayerName;
        final String cause;

        KillInfo(UUID killerUUID, String killerPlayerName, String cause) {
            this.killerUUID       = killerUUID;
            this.killerPlayerName = killerPlayerName;
            this.cause            = cause;
        }
    }

    private static KillInfo killInfo(DamageSource source) {
        if (source == null) return new KillInfo(null, null, "unknown");

        String cause = source.getMsgId();

        Entity attacker = source.getEntity();
        if (attacker instanceof Player p && !p.isSpectator()) {
            return new KillInfo(p.getUUID(), p.getGameProfile().name(), cause);
        }

        Entity direct = source.getDirectEntity();
        if (direct instanceof Projectile proj) {
            Entity owner = proj.getOwner();
            if (owner instanceof Player p && !p.isSpectator()) {
                return new KillInfo(p.getUUID(), p.getGameProfile().name(), cause);
            }
        }

        return new KillInfo(null, null, cause);
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private static String shortDim(ServerLevel level) {
        String raw = level.dimension().toString();
        int    idx = raw.lastIndexOf(" / ");
        if (idx != -1) {
            String tail = raw.substring(idx + 3);
            if (tail.endsWith("]")) tail = tail.substring(0, tail.length() - 1);
            return tail;
        }
        return raw;
    }
}

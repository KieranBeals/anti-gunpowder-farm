package com.kieranbeals;

import com.mojang.brigadier.context.CommandContext;
import java.util.Comparator;
import java.util.UUID;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AntiGunpowderFarm implements ModInitializer {

    public static final String MOD_ID = "anti-gunpowder-farm";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Anti Gunpowder Farm Initialized!");

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(
                Commands.literal("agf")
                    .requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                    .then(Commands.literal("buckets")
                        .executes(AntiGunpowderFarm::listBuckets)
                    )
            )
        );
    }

    private static int listBuckets(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        var server = src.getServer();

        // --- Player buckets ---
        var playerBuckets = GunpowderGuard.PLAYER_BUCKETS;
        if (playerBuckets.isEmpty()) {
            src.sendSuccess(() -> Component.literal("Players: none rate-limited."), false);
        } else {
            src.sendSuccess(() -> Component.literal("Players:"), false);
            playerBuckets.entrySet().stream()
                .sorted(Comparator.comparingDouble(e -> e.getValue().getTokens()))
                .forEach(e -> {
                    UUID   uuid   = e.getKey();
                    double tokens = e.getValue().getTokens();
                    var    online = server.getPlayerList().getPlayer(uuid);
                    String name   = online != null ? online.getGameProfile().name() : uuid.toString();
                    src.sendSuccess(
                        () -> Component.literal(
                            "  " + name + ": " + String.format("%.1f", tokens) + " / " + GunpowderGuard.PLAYER_MAX_KILLS
                        ),
                        false
                    );
                });
        }

        // --- Chunk buckets ---
        var chunkBuckets = GunpowderGuard.CHUNK_BUCKETS;
        boolean anyChunks = chunkBuckets.values().stream().anyMatch(m -> !m.isEmpty());
        if (!anyChunks) {
            src.sendSuccess(() -> Component.literal("Chunks: none rate-limited."), false);
        } else {
            src.sendSuccess(() -> Component.literal("Chunks:"), false);
            chunkBuckets.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey().identifier().toString()))
                .forEach(dimEntry -> {
                    String dim = dimEntry.getKey().identifier().getPath();
                    dimEntry.getValue().entrySet().stream()
                        .sorted(Comparator.comparingDouble(e -> e.getValue().getTokens()))
                        .forEach(e -> {
                            long   ck     = e.getKey();
                            int    cx     = (int) (ck >> 32);
                            int    cz     = (int) (ck & 0xFFFFFFFFL);
                            double tokens = e.getValue().getTokens();
                            src.sendSuccess(
                                () -> Component.literal(
                                    "  [" + dim + "] " + cx + "," + cz + ": "
                                    + String.format("%.1f", tokens) + " / " + GunpowderGuard.CHUNK_MAX_KILLS
                                ),
                                false
                            );
                        });
                });
        }

        return 1;
    }
}

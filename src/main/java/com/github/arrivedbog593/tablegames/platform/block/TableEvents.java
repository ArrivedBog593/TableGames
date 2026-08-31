package com.github.arrivedbog593.tablegames.platform.block;

import com.github.arrivedbog593.tablegames.TableGames;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Notices when a player stops being able to look at a table.
 * <p>
 * A dropped connection sends no close packet, so without this a table would
 * keep a seat occupied by somebody who is not on the server anymore, and a
 * ready button that nobody can ever satisfy.
 * <p>
 * A disconnect is treated exactly like closing the screen: the seat is kept
 * and the absence clock starts. The two are the same event from the table's
 * side, and giving them different rules would only invite somebody to find
 * out which one is kinder.
 */
@EventBusSubscriber(modid = TableGames.MOD_ID)
public final class TableEvents {

    private TableEvents() {
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        // Only tables in chunks that are already loaded are checked. One in
        // an unloaded chunk cannot be ticking, so its absence clock is not
        // running either; when it loads again, nobody is seated at it, because
        // seating is never saved.
        for (var level : player.server.getAllLevels()) {
            for (ChunkPos chunkPos : loadedChunksNear(level, player)) {
                var chunk = level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);
                if (chunk == null) {
                    continue;
                }
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    if (blockEntity instanceof TableBlockEntity table) {
                        table.leaveScreen(player.getUUID());
                    }
                }
            }
        }
    }

    /**
     * The chunks worth checking: those around where the player logged out.
     * <p>
     * A player can only have a table open if they were standing next to it,
     * and the reach check on every table action guarantees it. Sweeping every
     * loaded chunk in the world on every disconnect would be far more work
     * for the same answer.
     */
    private static java.util.List<ChunkPos> loadedChunksNear(
            net.minecraft.server.level.ServerLevel level, ServerPlayer player) {
        java.util.List<ChunkPos> nearby = new java.util.ArrayList<>();
        if (player.level() != level) {
            return nearby;
        }
        ChunkPos center = player.chunkPosition();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                nearby.add(new ChunkPos(center.x + x, center.z + z));
            }
        }
        return nearby;
    }
}

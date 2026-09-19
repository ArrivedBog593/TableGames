package com.github.arrivedbog593.tablegames.client;

import com.github.arrivedbog593.tablegames.platform.block.TableBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * Whether a screen opened on a table still has a table in front of it.
 * <p>
 * Plain screens get no {@code stillValid} the way container screens do, so
 * each one asks every tick. Two ways to lose the table: walking away from it,
 * or it stopping being there — broken by somebody else, blown up, replaced.
 * A screen left open on a missing table looks alive and does nothing, since
 * the server has no table to send its clicks to.
 */
final class TablePresence {

    /** Beyond this the player has clearly walked away from the table. */
    private static final double MAX_DISTANCE_SQUARED = 64.0;

    private TablePresence() {
    }

    static boolean lost(Minecraft minecraft, BlockPos tablePos) {
        if (minecraft == null || minecraft.player == null || minecraft.level == null) {
            return false;
        }
        return minecraft.player.distanceToSqr(tablePos.getCenter()) > MAX_DISTANCE_SQUARED
                || !(minecraft.level.getBlockState(tablePos).getBlock() instanceof TableBlock);
    }
}

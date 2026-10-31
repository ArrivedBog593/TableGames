package com.github.arrivedbog593.tablegames.platform.house;

import com.github.arrivedbog593.economycore.api.Staff;
import com.github.arrivedbog593.economycore.api.StaffRank;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

/**
 * What a staff rank means at the casino.
 * <p>
 * The ranks are the economy's, and the economy grants them nothing outside
 * its own blocks. Whatever they are worth here is decided here: an
 * administrator may step in at any table and run the house — its risk limits
 * and reserve — the way an operator can. The bank itself is EconomyCore's,
 * and EconomyCore lets administrators run it too. A moderator runs the shop
 * and the exchange and has no say in any of it.
 * <p>
 * Kept on this side on purpose. An economy that knew what its ranks unlock
 * in the casino would have to know about the casino, and a mod built on the
 * economy without it would inherit rules about tables it does not have.
 */
public final class HouseStaff {

    private HouseStaff() {
    }

    /** Whether this player is listed at the rank that reaches the casino. */
    public static boolean isAdmin(ServerPlayer player) {
        return Staff.rankOf(player.server, player.getUUID())
                .filter(rank -> rank == StaffRank.ADMIN)
                .isPresent();
    }

    /**
     * Whether this source may run {@code /tablegames house}.
     * <p>
     * Operators and the console always; a player otherwise only at the admin
     * rank. Asked by the command tree both when it is sent to a client and
     * again when the command runs, so a rank taken away mid-session stops
     * working at once even before the client hears about it.
     */
    public static boolean mayRunTheHouse(CommandSourceStack source) {
        if (source.hasPermission(2)) {
            return true;
        }
        ServerPlayer player = source.getPlayer();
        return player != null && isAdmin(player);
    }
}

package com.github.arrivedbog593.tablegames.platform.economy;

import com.github.arrivedbog593.tablegames.platform.network.CatalogSync;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Tells the other administrators what somebody just changed.
 * <p>
 * The commands have always done this: {@code sendSuccess} with the broadcast
 * flag reaches every operator, so removing a shop entry from chat is visible
 * to the rest of the staff. The GUI did the same work in silence, which meant
 * that whether a change was announced depended on which of two equivalent
 * routes an administrator happened to take.
 * <p>
 * That matters more here than it looks. Shop entry numbers are positions in a
 * list, and they move when one is removed, so an operator holding an older
 * catalog needs to hear about the removal, not only see the list redraw. The
 * announcement is the half of that story that {@link CatalogSync} cannot tell.
 * <p>
 * Sent to operators and to listed administrators alike, because both can make
 * these changes and both are affected by them — unlike the commands, which
 * only ever reach operators.
 */
public final class AdminNotices {

    private AdminNotices() {
    }

    /**
     * Announces a change to whoever else could have made it.
     * <p>
     * The actor gets the plain message. Everybody else gets it attributed in
     * the same gray italic form vanilla uses for command feedback, so it reads
     * as staff traffic rather than as something addressed to them.
     */
    public static void announce(ServerPlayer actor, Component message) {
        actor.sendSystemMessage(message);

        MinecraftServer server = actor.server;
        Component attributed = Component.translatable(
                        "chat.type.admin", actor.getDisplayName(), message)
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);

        for (ServerPlayer other : server.getPlayerList().getPlayers()) {
            if (other == actor) {
                continue;
            }
            if (other.hasPermissions(2)
                    || EconomyData.get(server).isStaff(other.getUUID())) {
                other.sendSystemMessage(attributed);
            }
        }
        server.sendSystemMessage(attributed);
    }
}
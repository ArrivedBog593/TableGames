package com.github.arrivedbog593.tablegames.platform.command;

import com.github.arrivedbog593.tablegames.platform.economy.EconomyData;
import com.github.arrivedbog593.tablegames.platform.item.AdminKeyItem;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * Who may configure the casino, and the card that lets them.
 * <p>
 * Listing somebody is what grants the permission; the card only carries it
 * into the world. That split is why {@code revoke} works even when the card
 * cannot be found: an unlisted player's cards open nothing.
 */
public final class AdminCommands {

    private AdminCommands() {
    }

    /**
     * Registers its own root rather than hanging off the shared one.
     * <p>
     * Every other command tree requires permission 2 at its root, and one
     * branch here must not: {@code admin key} is how a listed player who is
     * not an operator replaces a lost card. Adding it under a gated root
     * would have made it unreachable by exactly the people it exists for.
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("tablegames");

        root.then(Commands.literal("admin")
                // Granting and revoking are operator work. If an administrator
                // could appoint another, the list would spread on its own and
                // stop being something anybody controls.
                .then(Commands.literal("give")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(context -> give(context.getSource(),
                                        EntityArgument.getPlayer(context, "player")))))
                .then(Commands.literal("revoke")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(context -> revoke(context.getSource(),
                                        EntityArgument.getPlayer(context, "player")))))
                .then(Commands.literal("list")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> list(context.getSource())))
                // No permission check, on purpose. This is how somebody who is
                // already listed replaces a card they lost, and requiring an
                // operator for that would undo the point of delegating.
                .then(Commands.literal("key")
                        .executes(context -> issueTo(context.getSource()))));

        dispatcher.register(root);
    }

    private static int give(CommandSourceStack source, ServerPlayer target) {
        EconomyData data = EconomyData.get(source.getServer());
        if (!data.addAdministrator(target.getUUID())) {
            source.sendFailure(Component.translatable(
                    "tablegames.command.admin.already_listed", target.getDisplayName()));
            return 0;
        }
        handCard(target);

        source.sendSuccess(() -> Component.translatable(
                "tablegames.command.admin.granted", target.getDisplayName()), true);
        target.sendSystemMessage(Component.translatable("tablegames.admin.granted_to_you")
                .withStyle(ChatFormatting.GOLD));
        return 1;
    }

    private static int revoke(CommandSourceStack source, ServerPlayer target) {
        EconomyData data = EconomyData.get(source.getServer());
        if (!data.removeAdministrator(target.getUUID())) {
            source.sendFailure(Component.translatable(
                    "tablegames.command.admin.not_listed", target.getDisplayName()));
            return 0;
        }
        // Tidying up, not the revocation itself. Their cards stopped working
        // on the line above, wherever they are.
        int taken = AdminKeyItem.takeFrom(target);

        source.sendSuccess(() -> Component.translatable(
                "tablegames.command.admin.revoked", target.getDisplayName(), taken), true);
        target.sendSystemMessage(Component.translatable("tablegames.admin.revoked_from_you")
                .withStyle(ChatFormatting.RED));
        return 1;
    }

    /** Replaces a card for somebody who is already listed. */
    private static int issueTo(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!EconomyData.get(source.getServer()).isAdministrator(player.getUUID())) {
            // Operators are told to take one from the creative menu rather
            // than handed a bound card, because a bound card would imply a
            // listing they do not have and do not need.
            source.sendFailure(Component.translatable(player.hasPermissions(2)
                    ? "tablegames.command.admin.operators_use_creative"
                    : "tablegames.command.admin.not_an_administrator"));
            return 0;
        }
        handCard(player);
        source.sendSuccess(() -> Component.translatable(
                "tablegames.command.admin.key_issued"), false);
        return 1;
    }

    /**
     * Puts a card in their hands, or on the floor beside them.
     * <p>
     * Dropping it is safe despite the card refusing to be dropped by a
     * player: that refusal is about somebody throwing one away, and a card
     * that could not be delivered to a full inventory would be worse than one
     * lying at their feet for a moment.
     */
    private static void handCard(ServerPlayer target) {
        ItemStack card = AdminKeyItem.forPlayer(target.getUUID());
        if (!target.getInventory().add(card)) {
            target.drop(card, false);
        }
    }

    private static int list(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        var listed = EconomyData.get(server).administrators();
        if (listed.isEmpty()) {
            source.sendSuccess(() -> Component.translatable(
                    "tablegames.command.admin.none_listed"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(
                "tablegames.command.admin.list_header", listed.size())
                .withStyle(ChatFormatting.GOLD), false);
        for (UUID administrator : listed) {
            ServerPlayer online = server.getPlayerList().getPlayer(administrator);
            String name = online != null
                    ? online.getGameProfile().getName()
                    : server.getProfileCache() == null ? administrator.toString()
                            : server.getProfileCache().get(administrator)
                                    .map(GameProfile::getName)
                                    .orElse(administrator.toString());
            source.sendSuccess(() -> Component.translatable(
                    "tablegames.command.admin.list_entry", name), false);
        }
        return listed.size();
    }
}

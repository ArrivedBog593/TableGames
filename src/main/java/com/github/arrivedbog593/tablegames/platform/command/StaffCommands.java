package com.github.arrivedbog593.tablegames.platform.command;

import com.github.arrivedbog593.tablegames.platform.economy.EconomyData;
import com.github.arrivedbog593.tablegames.platform.economy.StaffRank;
import com.github.arrivedbog593.tablegames.platform.item.AdminKeyItem;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Who is on the casino staff, at which rank, and the card that lets them act.
 * <p>
 * Listing somebody is what grants the permission; the card only carries it
 * into the world. That split is why {@code remove} works even when the card
 * cannot be found: an unlisted player's cards open nothing.
 */
public final class StaffCommands {

    private StaffCommands() {
    }

    /**
     * Registers its own root rather than hanging off the shared one.
     * <p>
     * Every other command tree requires permission 2 at its root, and one
     * branch here must not: {@code staff key} is how a listed player who is
     * not an operator replaces a lost card. Adding it under a gated root
     * would have made it unreachable by exactly the people it exists for.
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("tablegames");

        RequiredArgumentBuilder<CommandSourceStack, EntitySelector> addTarget =
                Commands.argument("player", EntityArgument.player());
        for (StaffRank rank : StaffRank.values()) {
            addTarget.then(Commands.literal(rank.id())
                    .executes(context -> add(context.getSource(),
                            EntityArgument.getPlayer(context, "player"), rank)));
        }

        root.then(Commands.literal("staff")
                // Granting and removing are operator work. If staff could
                // appoint staff, the list would spread on its own and stop
                // being something anybody controls.
                .then(Commands.literal("add")
                        .requires(source -> source.hasPermission(2))
                        .then(addTarget))
                .then(Commands.literal("remove")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(context -> remove(context.getSource(),
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

    /**
     * Lists somebody at a rank, or moves them to it.
     * <p>
     * A card only on first listing. Somebody changing rank already has one,
     * and the card is the same for every rank.
     */
    private static int add(CommandSourceStack source, ServerPlayer target, StaffRank rank) {
        EconomyData data = EconomyData.get(source.getServer());
        Component rankName = Component.translatable(rank.translationKey());
        Optional<StaffRank> previous = data.setRank(target.getUUID(), rank);

        if (previous.filter(rank::equals).isPresent()) {
            source.sendFailure(Component.translatable(
                    "tablegames.command.staff.already_rank", target.getDisplayName(), rankName));
            return 0;
        }
        if (previous.isEmpty()) {
            handCard(target);
        }

        source.sendSuccess(() -> Component.translatable(previous.isEmpty()
                        ? "tablegames.command.staff.added"
                        : "tablegames.command.staff.rank_changed",
                target.getDisplayName(), rankName), true);
        target.sendSystemMessage(Component.translatable(rank.managesEveryTable()
                        ? "tablegames.staff.admin_to_you"
                        : "tablegames.staff.moderator_to_you")
                .withStyle(ChatFormatting.GOLD));
        return 1;
    }

    private static int remove(CommandSourceStack source, ServerPlayer target) {
        EconomyData data = EconomyData.get(source.getServer());
        if (!data.removeStaff(target.getUUID())) {
            source.sendFailure(Component.translatable(
                    "tablegames.command.staff.not_listed", target.getDisplayName()));
            return 0;
        }
        // Tidying up, not the revocation itself. Their cards stopped working
        // on the line above, wherever they are.
        int taken = AdminKeyItem.takeFrom(target);

        source.sendSuccess(() -> Component.translatable(
                "tablegames.command.staff.removed", target.getDisplayName(), taken), true);
        target.sendSystemMessage(Component.translatable("tablegames.staff.removed_from_you")
                .withStyle(ChatFormatting.RED));
        return 1;
    }

    /** Replaces a card for somebody who is already listed. */
    private static int issueTo(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!EconomyData.get(source.getServer()).isStaff(player.getUUID())) {
            // Operators are told to take one from the creative menu rather
            // than handed a bound card, because a bound card would imply a
            // listing they do not have and do not need.
            source.sendFailure(Component.translatable(player.hasPermissions(2)
                    ? "tablegames.command.staff.operators_use_creative"
                    : "tablegames.command.staff.not_staff"));
            return 0;
        }
        handCard(player);
        source.sendSuccess(() -> Component.translatable(
                "tablegames.command.staff.key_issued"), false);
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
        Map<UUID, StaffRank> listed = EconomyData.get(server).staff();
        if (listed.isEmpty()) {
            source.sendSuccess(() -> Component.translatable(
                    "tablegames.command.staff.none_listed"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(
                "tablegames.command.staff.list_header", listed.size())
                .withStyle(ChatFormatting.GOLD), false);
        listed.forEach((member, rank) -> {
            String name = nameOf(server, member);
            source.sendSuccess(() -> Component.translatable(
                    "tablegames.command.staff.list_entry", name,
                    Component.translatable(rank.translationKey())), false);
        });
        return listed.size();
    }

    private static String nameOf(MinecraftServer server, UUID playerId) {
        ServerPlayer online = server.getPlayerList().getPlayer(playerId);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        if (server.getProfileCache() == null) {
            return playerId.toString();
        }
        return server.getProfileCache().get(playerId)
                .map(GameProfile::getName)
                .orElse(playerId.toString());
    }
}

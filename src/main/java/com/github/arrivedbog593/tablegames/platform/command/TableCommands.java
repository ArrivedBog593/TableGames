package com.github.arrivedbog593.tablegames.platform.command;

import com.github.arrivedbog593.tablegames.engine.game.Game;
import com.github.arrivedbog593.tablegames.platform.block.TableBlockEntity;
import com.github.arrivedbog593.tablegames.platform.game.Games;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Assigning games to tables.
 * <p>
 * Operates on whichever table the sender is looking at rather than on
 * coordinates. Typing three numbers for a block you can already see is
 * friction with no upside, and getting one of them wrong silently configures
 * the wrong table.
 */
public final class TableCommands {

    /** How far to look for a table. Beyond this the player probably means something else. */
    private static final double REACH = 6.0;

    /** Game ids, drawn from the registry so a new game needs no command changes. */
    private static final SuggestionProvider<CommandSourceStack> GAME_IDS =
            (context, builder) -> {
                List<String> ids = new ArrayList<>();
                Games.registry().all().forEach(game -> ids.add(game.id()));
                return SharedSuggestionProvider.suggest(ids, builder);
            };

    private TableCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("tablegames")
                .requires(source -> source.hasPermission(2));

        root.then(Commands.literal("table")
                .then(Commands.literal("set")
                        .then(Commands.argument("game", StringArgumentType.word())
                                .suggests(GAME_IDS)
                                .executes(context -> setGame(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "game")))))
                .then(Commands.literal("clear")
                        .executes(context -> clearGame(context.getSource())))
                .then(Commands.literal("info")
                        .executes(context -> info(context.getSource())))
                .then(Commands.literal("games")
                        .executes(context -> listGames(context.getSource()))));

        dispatcher.register(root);
    }

    private static int setGame(CommandSourceStack source, String gameId)
            throws CommandSyntaxException {
        Optional<Game> game = Games.registry().get(gameId);
        if (game.isEmpty()) {
            source.sendFailure(Component.translatable(
                    "tablegames.command.table.no_such_game", gameId));
            return 0;
        }
        TableBlockEntity table = lookedAtTable(source);
        if (table == null) {
            return 0;
        }
        table.setGame(game.get());
        source.sendSuccess(() -> Component.translatable(
                "tablegames.command.table.assigned",
                Component.translatable(game.get().translationKey())), true);
        return 1;
    }

    private static int clearGame(CommandSourceStack source) throws CommandSyntaxException {
        TableBlockEntity table = lookedAtTable(source);
        if (table == null) {
            return 0;
        }
        table.setGame(null);
        source.sendSuccess(() -> Component.translatable(
                "tablegames.command.table.cleared"), true);
        return 1;
    }

    private static int info(CommandSourceStack source) throws CommandSyntaxException {
        TableBlockEntity table = lookedAtTable(source);
        if (table == null) {
            return 0;
        }
        Optional<Game> game = table.game();
        if (game.isEmpty()) {
            source.sendSuccess(() -> Component.translatable(
                    "tablegames.table.unassigned"), false);
            return 0;
        }
        Game assigned = game.get();
        source.sendSuccess(() -> Component.translatable(
                "tablegames.command.table.info",
                Component.translatable(assigned.translationKey()),
                assigned.minPlayers(),
                assigned.maxPlayers(),
                Component.translatable(assigned.isHouseBanked()
                        ? "tablegames.table.house_banked"
                        : "tablegames.table.player_versus_player")), false);
        return 1;
    }

    private static int listGames(CommandSourceStack source) {
        source.sendSuccess(() -> Component.translatable(
                "tablegames.command.table.games_title").withStyle(ChatFormatting.GOLD), false);
        for (Game game : Games.registry().all()) {
            source.sendSuccess(() -> Component.translatable(
                    "tablegames.command.table.games_entry",
                    Component.translatable(game.translationKey()),
                    Component.literal(game.id()).withStyle(ChatFormatting.DARK_GRAY)), false);
        }
        return Games.registry().size();
    }

    /**
     * The table the sender is looking at, or null after reporting why not.
     * <p>
     * Ray traces rather than trusting the crosshair target the client claims,
     * because a client is free to claim anything.
     */
    private static TableBlockEntity lookedAtTable(CommandSourceStack source)
            throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Vec3 eye = player.getEyePosition();
        Vec3 target = eye.add(player.getLookAngle().scale(REACH));

        HitResult hit = player.level().clip(new ClipContext(
                eye, target,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                player));

        if (hit.getType() != HitResult.Type.BLOCK) {
            source.sendFailure(Component.translatable("tablegames.command.table.none_in_sight"));
            return null;
        }
        BlockPos pos = ((BlockHitResult) hit).getBlockPos();
        BlockEntity entity = player.level().getBlockEntity(pos);
        if (!(entity instanceof TableBlockEntity table)) {
            source.sendFailure(Component.translatable("tablegames.command.table.not_a_table"));
            return null;
        }
        return table;
    }
}
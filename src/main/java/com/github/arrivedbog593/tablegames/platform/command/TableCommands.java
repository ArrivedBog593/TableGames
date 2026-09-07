package com.github.arrivedbog593.tablegames.platform.command;

import com.github.arrivedbog593.tablegames.engine.game.Game;
import com.github.arrivedbog593.tablegames.engine.games.roulette.BetLimits;
import com.github.arrivedbog593.tablegames.engine.games.roulette.BetType;
import com.github.arrivedbog593.tablegames.engine.table.RoundPhase;
import com.github.arrivedbog593.tablegames.platform.block.TableBlockEntity;
import com.github.arrivedbog593.tablegames.platform.economy.CreditFormat;
import com.github.arrivedbog593.tablegames.platform.game.Games;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
        // The root carries no requirement, and each branch below carries its
        // own. Brigadier merges same-named roots but keeps the requirement of
        // whichever was registered first, so one gated root here would have
        // gated every other class's commands too — including the one branch
        // that has to work without operator rights.
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("tablegames");

        // No blanket requirement on "table". Who may configure one depends on
        // the table being looked at, which Brigadier cannot know when it
        // evaluates a requirement, so the check lives inside each command, and
        // only pin and unpin are gated up here.
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
                        .executes(context -> listGames(context.getSource())))
                .then(Commands.literal("limits")
                        .then(Commands.literal("inside")
                                .then(Commands.argument("minimum", LongArgumentType.longArg(1))
                                        .then(Commands.argument("maximum",
                                                        LongArgumentType.longArg(0))
                                                .executes(context -> setLimits(
                                                        context.getSource(), true,
                                                        LongArgumentType.getLong(context,
                                                                "minimum"),
                                                        LongArgumentType.getLong(context,
                                                                "maximum"))))))
                        .then(Commands.literal("outside")
                                .then(Commands.argument("minimum", LongArgumentType.longArg(1))
                                        .then(Commands.argument("maximum",
                                                        LongArgumentType.longArg(0))
                                                .executes(context -> setLimits(
                                                        context.getSource(), false,
                                                        LongArgumentType.getLong(context,
                                                                "minimum"),
                                                        LongArgumentType.getLong(context,
                                                                "maximum"))))))
                        .then(Commands.literal("clear")
                                .executes(context -> clearLimits(context.getSource()))))
                .then(Commands.literal("trust")
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .executes(context -> trust(
                                        context.getSource(),
                                        GameProfileArgument.getGameProfiles(context, "player")))))
                .then(Commands.literal("untrust")
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .executes(context -> untrust(
                                        context.getSource(),
                                        GameProfileArgument.getGameProfiles(context, "player")))))
                .then(Commands.literal("trusted")
                        .executes(context -> listTrusted(context.getSource())))
                .then(Commands.literal("pin")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> setPinned(context.getSource(), true)))
                .then(Commands.literal("unpin")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> setPinned(context.getSource(), false))));

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
        TableBlockEntity table = configurableTable(source);
        if (table == null) {
            return 0;
        }
        if (!table.setGame(game.get())) {
            source.sendFailure(Component.translatable("tablegames.command.table.pinned"));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(
                "tablegames.command.table.assigned",
                Component.translatable(game.get().translationKey())), true);
        return 1;
    }

    private static int clearGame(CommandSourceStack source) throws CommandSyntaxException {
        TableBlockEntity table = configurableTable(source);
        if (table == null) {
            return 0;
        }
        if (!table.setGame(null)) {
            source.sendFailure(Component.translatable("tablegames.command.table.pinned"));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(
                "tablegames.command.table.cleared"), true);
        return 1;
    }

    /** One line of the limit report, saying which ceiling is doing the work. */
    private static void reportLimit(CommandSourceStack source, TableBlockEntity table,
                                    MinecraftServer server, BetType type, String key) {
        long effective = table.effectiveMaximum(server, type);
        boolean tableImposed = table.limits().maximumFor(type) <= effective
                && table.limits().maximumFor(type) != Long.MAX_VALUE;
        source.sendSuccess(() -> Component.translatable(key,
                CreditFormat.of(table.effectiveMinimum(type)),
                CreditFormat.of(effective),
                Component.translatable(tableImposed
                        ? "tablegames.command.table.info_by_table"
                        : "tablegames.command.table.info_by_house")), false);
    }

    /**
     * Sets what this table accepts, on top of what the house can afford.
     * <p>
     * A maximum of zero means the table imposes none and the bankroll alone
     * decides — which is how a table starts out, and the only way to express
     * "no ceiling of my own" in a command that takes numbers.
     */
    private static int setLimits(CommandSourceStack source, boolean inside,
                                 long minimum, long maximum)
            throws CommandSyntaxException {
        TableBlockEntity table = configurableTable(source);
        if (table == null) {
            return 0;
        }
        BetLimits updated;
        try {
            updated = inside
                    ? table.limits().withInside(minimum, maximum)
                    : table.limits().withOutside(minimum, maximum);
        } catch (IllegalArgumentException invalid) {
            source.sendFailure(Component.translatable(
                    "tablegames.command.table.limits_invalid"));
            return 0;
        }
        table.setLimits(updated);
        source.sendSuccess(() -> Component.translatable(
                inside ? "tablegames.command.table.limits_inside_set"
                        : "tablegames.command.table.limits_outside_set",
                CreditFormat.of(minimum),
                maximum == BetLimits.UNLIMITED
                        ? Component.translatable("tablegames.command.table.limits_none")
                        : Component.literal(CreditFormat.of(maximum))), true);
        return 1;
    }

    /** Drops a table's own limits, leaving only the bankroll's. */
    private static int clearLimits(CommandSourceStack source) throws CommandSyntaxException {
        TableBlockEntity table = configurableTable(source);
        if (table == null) {
            return 0;
        }
        table.setLimits(BetLimits.DEFAULT);
        source.sendSuccess(() -> Component.translatable(
                "tablegames.command.table.limits_cleared"), true);
        return 1;
    }

    /**
     * Fixes or releases a table's game.
     * <p>
     * Its own branch carries the operator requirement, since the rest of the
     * tree no longer does: pinning has to be the operator's tool, not
     * something a visitor can do to every table in the casino on their first
     * afternoon, and not something an owner can use to lock an operator out
     * of a table.
     */
    private static int setPinned(CommandSourceStack source, boolean pinned)
            throws CommandSyntaxException {
        TableBlockEntity table = lookedAtTable(source);
        if (table == null) {
            return 0;
        }
        table.setPinned(pinned);
        source.sendSuccess(() -> Component.translatable(pinned
                ? "tablegames.command.table.pin_set"
                : "tablegames.command.table.pin_cleared"), true);
        return 1;
    }

    /**
     * Reports what is happening at the table being looked at.
     * <p>
     * Live state, not the game's specification. It used to print the player
     * range and whether the game was house banked, which is identical for
     * every roulette table on the server and already available from
     * {@code table games} — so the one command that knew which table you
     * meant was the one that ignored it.
     */
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

        MutableComponent heading = Component.translatable(assigned.translationKey())
                .withStyle(ChatFormatting.GOLD);
        if (table.isPinned()) {
            heading = heading.append(Component.literal(" ")).append(
                    Component.translatable("tablegames.command.table.info_pinned")
                            .withStyle(ChatFormatting.DARK_GRAY));
        }
        MutableComponent title = heading;
        source.sendSuccess(() -> title, false);

        RoundPhase phase = table.phase();
        source.sendSuccess(() -> Component.translatable(
                "tablegames.command.table.info_phase",
                Component.translatable(phase.translationKey()),
                phase.isCountingDown()
                        ? Component.translatable("tablegames.command.table.info_seconds",
                        table.secondsRemaining())
                        : Component.empty()), false);

        List<UUID> seated = table.seatedPlayers();
        source.sendSuccess(() -> Component.translatable(
                "tablegames.command.table.info_seats",
                seated.size(), table.maxSeats(), table.spectatorCount()), false);

        MinecraftServer server = source.getServer();
        for (UUID occupant : seated) {
            String name = nameOf(server, occupant);
            source.sendSuccess(() -> Component.translatable(
                    "tablegames.command.table.info_seat",
                    name,
                    CreditFormat.of(table.wageredBy(occupant)),
                    Component.translatable(table.isReady(occupant)
                            ? "tablegames.command.table.info_ready"
                            : "tablegames.command.table.info_thinking")), false);
        }

        // The straight-up maximum, because it is the one that binds first and
        // the one people ask about. Everything else on the felt allows more.
        if (assigned.isHouseBanked()) {
            // Both, and labeled, because a single figure cannot say whether
            // it is the house's ceiling or the table's own choice — and
            // "maximum 5,000" is baffling next to a bankroll that could cover
            // far more.
            reportLimit(source, table, server, BetType.STRAIGHT_UP,
                    "tablegames.command.table.info_inside");
            reportLimit(source, table, server, BetType.RED,
                    "tablegames.command.table.info_outside");
        }
        return 1;
    }

    private static int listGames(CommandSourceStack source) {
        source.sendSuccess(() -> Component.translatable(
                "tablegames.command.table.games_title").withStyle(ChatFormatting.GOLD), false);
        for (Game game : Games.registry().all()) {
            source.sendSuccess(() -> Component.translatable(
                    "tablegames.command.table.games_entry",
                    Component.translatable(game.translationKey()),
                    Component.literal(game.id()).withStyle(ChatFormatting.DARK_GRAY),
                    game.minPlayers(),
                    game.maxPlayers(),
                    Component.translatable(game.isHouseBanked()
                            ? "tablegames.table.house_banked"
                            : "tablegames.table.player_versus_player")), false);
        }
        return Games.registry().size();
    }

    /**
     * Shares a table with somebody, or reports why not.
     * <p>
     * Takes a profile rather than an online player so that somebody can be
     * added or dropped while they are logged off, which is most of the time
     * on the servers this is for.
     */
    private static int trust(CommandSourceStack source, Collection<GameProfile> profiles)
            throws CommandSyntaxException {
        TableBlockEntity table = sharedTable(source);
        if (table == null) {
            return 0;
        }
        GameProfile guest = onlyOne(source, profiles);
        if (guest == null) {
            return 0;
        }
        if (table.owner().filter(guest.getId()::equals).isPresent()) {
            source.sendFailure(Component.translatable(
                    "tablegames.command.table.trust_owner", guest.getName()));
            return 0;
        }
        if (!table.trust(guest.getId())) {
            source.sendFailure(Component.translatable(
                    table.trusted().size() >= TableBlockEntity.MAX_TRUSTED
                            ? "tablegames.command.table.trust_full"
                            : "tablegames.command.table.trust_already",
                    guest.getName(), TableBlockEntity.MAX_TRUSTED));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(
                "tablegames.command.table.trust_added", guest.getName()), false);
        return 1;
    }

    private static int untrust(CommandSourceStack source, Collection<GameProfile> profiles)
            throws CommandSyntaxException {
        TableBlockEntity table = sharedTable(source);
        if (table == null) {
            return 0;
        }
        GameProfile guest = onlyOne(source, profiles);
        if (guest == null) {
            return 0;
        }
        if (!table.untrust(guest.getId())) {
            source.sendFailure(Component.translatable(
                    "tablegames.command.table.trust_not_listed", guest.getName()));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(
                "tablegames.command.table.trust_removed", guest.getName()), false);
        return 1;
    }

    /**
     * Who may configure the table being looked at.
     * <p>
     * Readable by anybody, unlike the commands that change it. Somebody who
     * cannot switch the game is exactly who benefits from being told whose
     * table it is and who to ask.
     */
    private static int listTrusted(CommandSourceStack source) throws CommandSyntaxException {
        TableBlockEntity table = lookedAtTable(source);
        if (table == null) {
            return 0;
        }
        MinecraftServer server = source.getServer();
        // A component rather than a string, so an unowned table reads in the
        // reader's language. Resolving a translation key on the server would
        // have printed the key itself, since only the client has the mod's
        // language files.
        Component ownerName = table.owner()
                .map(id -> (Component) Component.literal(nameOf(server, id)))
                .orElseGet(() -> Component.translatable(
                        "tablegames.command.table.trust_nobody"));
        source.sendSuccess(() -> Component.translatable(
                        "tablegames.command.table.trust_owner_is", ownerName)
                .withStyle(ChatFormatting.GOLD), false);

        Set<UUID> guests = table.trusted();
        if (guests.isEmpty()) {
            source.sendSuccess(() -> Component.translatable(
                    "tablegames.command.table.trust_none"), false);
            return 0;
        }
        for (UUID guest : guests) {
            source.sendSuccess(() -> Component.translatable(
                    "tablegames.command.table.trust_entry", nameOf(server, guest)), false);
        }
        return guests.size();
    }

    /** Exactly one profile, since sharing a table with a selector is a mistake. */
    private static GameProfile onlyOne(CommandSourceStack source,
                                       Collection<GameProfile> profiles) {
        if (profiles.size() != 1) {
            source.sendFailure(Component.translatable(
                    "tablegames.command.table.trust_one_player"));
            return null;
        }
        return profiles.iterator().next();
    }

    /** A name for an id, whether or not they are online. */
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

    /**
     * The table being looked at if the sender decides who may use it.
     * <p>
     * Stricter than {@link #configurableTable}: a guest may change the game
     * and may not change the guest list.
     */
    private static TableBlockEntity sharedTable(CommandSourceStack source)
            throws CommandSyntaxException {
        TableBlockEntity table = lookedAtTable(source);
        if (table == null) {
            return null;
        }
        if (!table.mayShare(source.getPlayerOrException())) {
            source.sendFailure(Component.translatable("tablegames.command.table.not_owner"));
            return null;
        }
        return table;
    }

    /**
     * The table being looked at, if the sender is allowed to reconfigure it.
     * <p>
     * Separate from {@link #lookedAtTable} because reading a table and
     * changing one are different rights: anybody may ask what a table is
     * doing, and only its owner, a listed administrator, or an operator may
     * change it.
     *
     * @return null when there is no table, or it is not theirs; a message has
     *         already been sent either way
     */
    private static TableBlockEntity configurableTable(CommandSourceStack source)
            throws CommandSyntaxException {
        TableBlockEntity table = lookedAtTable(source);
        if (table == null) {
            return null;
        }
        if (!table.mayConfigure(source.getPlayerOrException())) {
            source.sendFailure(Component.translatable("tablegames.command.table.not_yours"));
            return null;
        }
        return table;
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

        BlockHitResult hit = player.level().clip(new ClipContext(
                eye, target,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                player));

        if (hit.getType() != HitResult.Type.BLOCK) {
            source.sendFailure(Component.translatable("tablegames.command.table.none_in_sight"));
            return null;
        }
        BlockPos pos = hit.getBlockPos();
        BlockEntity entity = player.level().getBlockEntity(pos);
        if (!(entity instanceof TableBlockEntity table)) {
            source.sendFailure(Component.translatable("tablegames.command.table.not_a_table"));
            return null;
        }
        return table;
    }
}
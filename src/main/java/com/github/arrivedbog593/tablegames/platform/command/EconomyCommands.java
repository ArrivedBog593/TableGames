package com.github.arrivedbog593.tablegames.platform.command;

import com.github.arrivedbog593.tablegames.engine.economy.EconomyIssue;
import com.github.arrivedbog593.tablegames.platform.economy.EconomyData;
import com.github.arrivedbog593.tablegames.platform.economy.EconomyEvents;
import com.github.arrivedbog593.tablegames.platform.economy.ItemIds;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * In-game editing of conversion values and shop prices.
 * <p>
 * Everything can be done with the item in hand, so nobody has to know or type
 * a registry id. Every change is validated before it is committed: a value
 * that would let players mint credits is refused at the command with the
 * correct figure suggested, rather than accepted and complained about in a log
 * after the damage is done.
 */
public final class EconomyCommands {

    private static final String LIST_COMMAND = "/tablegames economy list";
    private static final String SHOP_LIST_COMMAND = "/tablegames shop list";

    private EconomyCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("tablegames")
                .requires(source -> source.hasPermission(2));

        root.then(Commands.literal("economy")
                .then(Commands.literal("set")
                        .then(Commands.argument("credits", LongArgumentType.longArg(1))
                                .executes(context -> setHeld(
                                        context.getSource(),
                                        LongArgumentType.getLong(context, "credits")))))
                .then(Commands.argument("item", ResourceLocationArgument.id())
                        .suggests(EconomySuggestions.PRICED_ITEMS)
                        .executes(context -> remove(
                                context.getSource(),
                                ResourceLocationArgument.getId(context, "item").toString())))
                .then(Commands.literal("list")
                        .executes(context -> listConversions(context.getSource(), 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(context -> listConversions(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "page")))))
                .then(Commands.literal("check")
                        .executes(context -> check(context.getSource()))));

        root.then(Commands.literal("shop")
                .then(Commands.literal("add")
                        .then(Commands.argument("price", LongArgumentType.longArg(1))
                                .executes(context -> addShopHeld(
                                        context.getSource(),
                                        LongArgumentType.getLong(context, "price")))))
                .then(Commands.argument("item", ResourceLocationArgument.id())
                        .suggests(EconomySuggestions.SHOP_ITEMS)
                        .executes(context -> removeShop(
                                context.getSource(),
                                ResourceLocationArgument.getId(context, "item").toString())))
                .then(Commands.literal("list")
                        .executes(context -> listShop(context.getSource(), 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(context -> listShop(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "page"))))));

        dispatcher.register(root);
    }

    // --- Conversion values --------------------------------------------------

    private static int setHeld(CommandSourceStack source, long credits)
            throws CommandSyntaxException {
        ItemStack held = requireHeld(source);
        if (held == null) {
            return 0;
        }
        return setConversion(source, ItemIds.idOf(held), credits);
    }

    private static int setConversion(CommandSourceStack source, String itemId, long credits) {
        MinecraftServer server = source.getServer();
        List<EconomyIssue> problems =
                EconomyEvents.economy().previewConversion(server, itemId, credits);
        if (reportRefusal(source, problems)) {
            return 0;
        }

        EconomyData.get(server).setConversion(itemId, credits);
        EconomyEvents.economy().rebuild(server);

        source.sendSuccess(() -> Component.translatable(
                "tablegames.command.economy.set", ItemIds.displayName(itemId), credits), true);
        warnAboutRemaining(source, problems);
        return 1;
    }

    private static int removeHeld(CommandSourceStack source) throws CommandSyntaxException {
        ItemStack held = requireHeld(source);
        if (held == null) {
            return 0;
        }
        return remove(source, ItemIds.idOf(held));
    }

    private static int remove(CommandSourceStack source, String itemId) {
        MinecraftServer server = source.getServer();
        if (EconomyData.get(server).removeConversion(itemId).isEmpty()) {
            source.sendFailure(Component.translatable(
                    "tablegames.command.economy.not_listed", ItemIds.displayName(itemId)));
            return 0;
        }
        EconomyEvents.economy().rebuild(server);

        source.sendSuccess(() -> Component.translatable(
                "tablegames.command.economy.removed", ItemIds.displayName(itemId)), true);

        // Removing an item can leave the rest without the recipe links that
        // justified their values, so say so rather than let it pass silently.
        warnAboutRemaining(source, EconomyEvents.economy().issues());
        return 1;
    }

    private static int listConversions(CommandSourceStack source, int requestedPage) {
        List<Map.Entry<String, Long>> entries = sortedEntries(
                EconomyEvents.economy().table().itemIds(),
                itemId -> EconomyEvents.economy().table().valueOf(itemId).orElse(0L));

        if (entries.isEmpty()) {
            source.sendSuccess(() -> Component.translatable(
                    "tablegames.command.economy.empty"), false);
            return 0;
        }

        int page = Pagination.clampPage(requestedPage, entries.size());
        source.sendSuccess(() -> Pagination.header(
                "tablegames.command.economy.title", LIST_COMMAND, page, entries.size()), false);

        for (Map.Entry<String, Long> entry : Pagination.slice(entries, page)) {
            source.sendSuccess(() -> Component.translatable(
                            "tablegames.command.economy.entry",
                            ItemIds.displayName(entry.getKey()),
                            entry.getValue(),
                            Component.literal(entry.getKey()).withStyle(ChatFormatting.DARK_GRAY)),
                    false);
        }
        return entries.size();
    }

    // --- Shop ---------------------------------------------------------------

    private static int addShopHeld(CommandSourceStack source, long price)
            throws CommandSyntaxException {
        ItemStack held = requireHeld(source);
        if (held == null) {
            return 0;
        }
        String itemId = ItemIds.idOf(held);

        List<EconomyIssue> problems = EconomyEvents.economy().previewShopPrice(itemId, price);
        if (reportRefusal(source, problems)) {
            return 0;
        }

        EconomyData.get(source.getServer()).setShopPrice(itemId, price);
        EconomyEvents.economy().rebuild(source.getServer());

        source.sendSuccess(() -> Component.translatable(
                "tablegames.command.shop.added", ItemIds.displayName(itemId), price), true);
        warnAboutRemaining(source, problems);
        return 1;
    }

    private static int removeShopHeld(CommandSourceStack source) throws CommandSyntaxException {
        ItemStack held = requireHeld(source);
        if (held == null) {
            return 0;
        }
        return removeShop(source, ItemIds.idOf(held));
    }

    private static int removeShop(CommandSourceStack source, String itemId) {
        if (EconomyData.get(source.getServer()).removeShopPrice(itemId).isEmpty()) {
            source.sendFailure(Component.translatable(
                    "tablegames.command.shop.not_listed", ItemIds.displayName(itemId)));
            return 0;
        }
        EconomyEvents.economy().rebuild(source.getServer());
        source.sendSuccess(() -> Component.translatable(
                "tablegames.command.shop.removed", ItemIds.displayName(itemId)), true);
        return 1;
    }

    private static int listShop(CommandSourceStack source, int requestedPage) {
        Map<String, Long> prices = EconomyData.get(source.getServer()).shopPrices();
        List<Map.Entry<String, Long>> entries = sortedEntries(
                prices.keySet(), itemId -> prices.getOrDefault(itemId, 0L));

        if (entries.isEmpty()) {
            source.sendSuccess(() -> Component.translatable(
                    "tablegames.command.shop.empty"), false);
            return 0;
        }

        int page = Pagination.clampPage(requestedPage, entries.size());
        source.sendSuccess(() -> Pagination.header(
                "tablegames.command.shop.title", SHOP_LIST_COMMAND, page, entries.size()), false);

        for (Map.Entry<String, Long> entry : Pagination.slice(entries, page)) {
            source.sendSuccess(() -> Component.translatable(
                            "tablegames.command.shop.entry",
                            ItemIds.displayName(entry.getKey()),
                            entry.getValue(),
                            Component.literal(entry.getKey()).withStyle(ChatFormatting.DARK_GRAY)),
                    false);
        }
        return entries.size();
    }

    // --- Diagnostics ---------------------------------------------------------

    private static int check(CommandSourceStack source) {
        List<EconomyIssue> issues = EconomyEvents.economy().issues();
        if (issues.isEmpty()) {
            source.sendSuccess(() -> Component.translatable(
                            "tablegames.command.economy.check_clean",
                            EconomyEvents.economy().table().size(),
                            EconomyEvents.economy().pricedRecipeCount())
                    .withStyle(ChatFormatting.GREEN), false);
            return 0;
        }
        for (EconomyIssue issue : issues) {
            source.sendSuccess(() -> Component.literal(issue.message())
                            .withStyle(issue.isError() ? ChatFormatting.RED : ChatFormatting.YELLOW),
                    false);
        }
        return issues.size();
    }

    // --- Shared helpers ------------------------------------------------------

    /** The item in the sender's main hand, or null after reporting the failure. */
    private static ItemStack requireHeld(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            source.sendFailure(Component.translatable("tablegames.command.no_item_held"));
            return null;
        }
        return held;
    }

    /**
     * Reports and refuses if the proposed change would create an exploit.
     *
     * @return true when the command should stop
     */
    private static boolean reportRefusal(CommandSourceStack source, List<EconomyIssue> problems) {
        List<EconomyIssue> errors = problems.stream().filter(EconomyIssue::isError).toList();
        if (errors.isEmpty()) {
            return false;
        }
        source.sendFailure(Component.translatable("tablegames.command.economy.refused")
                .withStyle(ChatFormatting.RED));
        for (EconomyIssue error : errors) {
            source.sendFailure(Component.literal(error.message()));
        }
        return true;
    }

    /** Passes along non-blocking warnings after a successful change. */
    private static void warnAboutRemaining(CommandSourceStack source,
                                           List<EconomyIssue> problems) {
        for (EconomyIssue issue : problems) {
            if (!issue.isError()) {
                source.sendSuccess(() -> Component.literal(issue.message())
                        .withStyle(ChatFormatting.YELLOW), false);
            }
        }
    }

    /** Entries sorted by value descending, then by id, so the list is stable. */
    private static List<Map.Entry<String, Long>> sortedEntries(
            Iterable<String> itemIds, Function<String, Long> valueOf) {
        List<Map.Entry<String, Long>> entries = new ArrayList<>();
        for (String itemId : itemIds) {
            entries.add(Map.entry(itemId, valueOf.apply(itemId)));
        }
        entries.sort(Comparator
                .<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue).reversed()
                .thenComparing(Map.Entry::getKey));
        return entries;
    }
}
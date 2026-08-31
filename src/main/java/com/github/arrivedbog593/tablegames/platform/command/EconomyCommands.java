package com.github.arrivedbog593.tablegames.platform.command;

import com.github.arrivedbog593.tablegames.engine.economy.EconomyIssue;
import com.github.arrivedbog593.tablegames.platform.economy.EconomyData;
import com.github.arrivedbog593.tablegames.platform.economy.EconomyEvents;
import com.github.arrivedbog593.tablegames.platform.economy.ItemIds;
import com.github.arrivedbog593.tablegames.platform.economy.ShopEntry;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
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
import java.util.Optional;
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
        // The root carries no requirement, and each branch below carries its
        // own. Brigadier merges same-named roots but keeps the requirement of
        // whichever was registered first, so one gated root here would have
        // gated every other class's commands too — including the one branch
        // that has to work without operator rights.
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("tablegames");

        root.then(Commands.literal("economy")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("set")
                        .then(Commands.argument("credits", LongArgumentType.longArg(1))
                                .executes(context -> setHeld(
                                        context.getSource(),
                                        LongArgumentType.getLong(context, "credits")))))
                // Behind a literal, unlike before. A bare argument here meant
                // any mistyped subcommand was read as an item id, so
                // "/tablegames economy shop" reported that minecraft:shop had
                // no conversion value — and a real typo could have quietly
                // deleted one.
                .then(Commands.literal("remove")
                        .then(Commands.argument("item", ResourceLocationArgument.id())
                                .suggests(EconomySuggestions.PRICED_ITEMS)
                                .executes(context -> remove(
                                        context.getSource(),
                                        ResourceLocationArgument.getId(context, "item")
                                                .toString()))))
                .then(Commands.literal("list")
                        .executes(context -> listConversions(context.getSource(), 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(context -> listConversions(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "page")))))
                .then(Commands.literal("check")
                        .executes(context -> check(context.getSource()))));

        root.then(Commands.literal("shop")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("add")
                        .then(Commands.argument("price", LongArgumentType.longArg(1))
                                .executes(context -> addShopHeld(
                                        context.getSource(),
                                        LongArgumentType.getLong(context, "price")))))
                // By entry id, not by item. Two entries can sell the same item
                // at different prices now — a plain sword and an enchanted one
                // — so naming the item no longer says which one to touch.
                .then(Commands.literal("remove")
                        .then(Commands.argument("entry", IntegerArgumentType.integer(1))
                                .suggests(EconomySuggestions.SHOP_ENTRIES)
                                .executes(context -> removeShop(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "entry")))))
                .then(Commands.literal("price")
                        .then(Commands.argument("entry", IntegerArgumentType.integer(1))
                                .suggests(EconomySuggestions.SHOP_ENTRIES)
                                .then(Commands.argument("price", LongArgumentType.longArg(1))
                                        .executes(context -> repriceShop(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "entry"),
                                                LongArgumentType.getLong(context, "price"))))))
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
            // Two different failures wearing the same message until now. An
            // item priced by a datapack is listed, is convertible, and still
            // cannot be removed here — saying it is "not listed" sent admins
            // looking for a bug that was not there.
            source.sendFailure(EconomyEvents.economy().isFromDatapack(itemId)
                    ? Component.translatable("tablegames.command.economy.from_datapack",
                    ItemIds.displayName(itemId))
                    : Component.translatable("tablegames.command.economy.not_listed",
                    ItemIds.displayName(itemId)));
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

        // The whole held stack, components and all. What the admin is
        // holding is what buyers receive — an enchanted sword listed here
        // used to come back out plain, because only its id was ever stored.
        int number = EconomyData.get(source.getServer()).addShopEntry(held.copy(), price);
        EconomyEvents.economy().rebuild(source.getServer());

        source.sendSuccess(() -> Component.translatable(
                "tablegames.command.shop.added",
                held.getHoverName(), price, number), true);
        warnAboutRemaining(source, problems);
        return 1;
    }

    private static int removeShop(CommandSourceStack source, int number) {
        Optional<ShopEntry> removed =
                EconomyData.get(source.getServer()).removeShopEntry(number);
        if (removed.isEmpty()) {
            source.sendFailure(Component.translatable(
                    "tablegames.command.shop.no_such_entry", number));
            return 0;
        }
        EconomyEvents.economy().rebuild(source.getServer());
        source.sendSuccess(() -> Component.translatable(
                "tablegames.command.shop.removed",
                removed.get().stack().getHoverName(), number), true);
        return 1;
    }

    private static int repriceShop(CommandSourceStack source, int number, long price) {
        Optional<ShopEntry> updated =
                EconomyData.get(source.getServer()).repriceShopEntry(number, price);
        if (updated.isEmpty()) {
            source.sendFailure(Component.translatable(
                    "tablegames.command.shop.no_such_entry", number));
            return 0;
        }
        EconomyEvents.economy().rebuild(source.getServer());
        source.sendSuccess(() -> Component.translatable(
                "tablegames.command.shop.repriced",
                updated.get().stack().getHoverName(), price, number), true);
        return 1;
    }

    private static int listShop(CommandSourceStack source, int requestedPage) {
        // Catalog order, which is what the numbers mean. An admin reads this
        // list to find the number to type into remove or price, so the
        // numbers running 1, 2, 3 are worth more here than the prices doing
        // so. How a player wants the shop itself sorted is their business,
        // and the shop screen handles that locally.
        List<ShopEntry> entries = EconomyData.get(source.getServer()).shopEntries();

        if (entries.isEmpty()) {
            source.sendSuccess(() -> Component.translatable(
                    "tablegames.command.shop.empty"), false);
            return 0;
        }

        int page = Pagination.clampPage(requestedPage, entries.size());
        source.sendSuccess(() -> Pagination.header(
                "tablegames.command.shop.title", SHOP_LIST_COMMAND, page, entries.size()), false);

        // The number is the position in the whole catalog, not in the page,
        // so it stays the number the other commands take.
        int firstOnPage = Pagination.firstIndex(page);
        List<ShopEntry> slice = Pagination.slice(entries, page);
        for (int i = 0; i < slice.size(); i++) {
            ShopEntry entry = slice.get(i);
            int number = firstOnPage + i + 1;
            source.sendSuccess(() -> Component.translatable(
                            "tablegames.command.shop.entry",
                            number,
                            entry.stack().getHoverName(),
                            entry.price(),
                            Component.literal(entry.hasComponents()
                                            ? entry.itemId() + " *"
                                            : entry.itemId())
                                    .withStyle(ChatFormatting.DARK_GRAY)),
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
package com.github.arrivedbog593.tablegames.platform.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * A directory of the mod's commands.
 * <p>
 * There are more than thirty of them across six groups now, and the only way
 * to find one was to type a prefix and read what Brigadier suggested — which
 * shows names and never says what any of them does.
 * <p>
 * Written out by hand rather than walked from the dispatcher. A generated list
 * gives usage strings and no explanations, and explanations are the entire
 * point: {@code /tablegames house reserve} tells nobody that the reserve is a
 * floor the live exposure can raise. The cost is that this file has to be
 * edited when a command is added, which is one line beside the several the
 * command itself took.
 */
public final class HelpCommands {

    /**
     * One line of the directory.
     *
     * @param usage      what to type, arguments and all
     * @param key        translation key for the one-line explanation
     * @param operatorOnly whether it is hidden from ordinary players
     */
    private record Entry(String usage, String key, boolean operatorOnly) {
    }

    /**
     * Grouped the way the commands are, and in the order somebody meets them:
     * their own credits first, then a table, then the machinery behind the
     * casino.
     */
    private static final List<Entry> ENTRIES = List.of(
            new Entry("/credits", "tablegames.help.credits", false),
            new Entry("/credits convert", "tablegames.help.credits_convert", false),
            new Entry("/credits redeem <item> [count]", "tablegames.help.credits_redeem", false),
            new Entry("/credits pay <player> <amount>", "tablegames.help.credits_pay", false),
            new Entry("/credits give <player> <amount>", "tablegames.help.credits_give", true),
            new Entry("/credits set <player> <amount>", "tablegames.help.credits_set", true),
            new Entry("/credits house", "tablegames.help.credits_house", true),

            new Entry("/tablegames table info", "tablegames.help.table_info", false),
            new Entry("/tablegames table games", "tablegames.help.table_games", false),
            new Entry("/tablegames table set <game>", "tablegames.help.table_set", false),
            new Entry("/tablegames table clear", "tablegames.help.table_clear", false),
            new Entry("/tablegames table limits inside <min> <max>",
                    "tablegames.help.table_limits_inside", false),
            new Entry("/tablegames table limits outside <min> <max>",
                    "tablegames.help.table_limits_outside", false),
            new Entry("/tablegames table limits clear",
                    "tablegames.help.table_limits_clear", false),
            new Entry("/tablegames table trusted", "tablegames.help.table_trusted", false),
            new Entry("/tablegames table trust <player>", "tablegames.help.table_trust", false),
            new Entry("/tablegames table untrust <player>",
                    "tablegames.help.table_untrust", false),

            new Entry("/tablegames house", "tablegames.help.house", true),
            new Entry("/tablegames house add <amount>", "tablegames.help.house_add", true),
            new Entry("/tablegames house take <amount>", "tablegames.help.house_take", true),
            new Entry("/tablegames house set <amount>", "tablegames.help.house_set", true),
            new Entry("/tablegames house exposure <percent>",
                    "tablegames.help.house_exposure", true),
            new Entry("/tablegames house reserve <credits>",
                    "tablegames.help.house_reserve", true),
            new Entry("/tablegames house spread <percent>", "tablegames.help.house_spread", true),
            new Entry("/tablegames house plan <maxbet>", "tablegames.help.house_plan", true),

            new Entry("/tablegames economy list [page]", "tablegames.help.economy_list", true),
            new Entry("/tablegames economy set <credits>", "tablegames.help.economy_set", true),
            new Entry("/tablegames economy remove <item>",
                    "tablegames.help.economy_remove", true),
            new Entry("/tablegames economy check", "tablegames.help.economy_check", true),

            new Entry("/tablegames shop list [page]", "tablegames.help.shop_list", true),
            new Entry("/tablegames shop add <price>", "tablegames.help.shop_add", true),
            new Entry("/tablegames shop price <entry> <price>", "tablegames.help.shop_price", true),
            new Entry("/tablegames shop remove <entry>", "tablegames.help.shop_remove", true),

            new Entry("/tablegames staff key", "tablegames.help.staff_key", false),
            new Entry("/tablegames staff list", "tablegames.help.staff_list", true),
            new Entry("/tablegames staff add <player> moderator",
                    "tablegames.help.staff_add_moderator", true),
            new Entry("/tablegames staff add <player> admin",
                    "tablegames.help.staff_add_admin", true),
            new Entry("/tablegames staff remove <player>", "tablegames.help.staff_remove", true));

    private HelpCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("tablegames");

        root.executes(context -> help(context.getSource(), 1));

        root.then(Commands.literal("help")
                .executes(context -> help(context.getSource(), 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(context -> help(
                                context.getSource(),
                                IntegerArgumentType.getInteger(context, "page")))));

        dispatcher.register(root);
    }

    private static int help(CommandSourceStack source, int requestedPage) {
        // Filtered by what this sender could actually run. A player reading
        // about /tablegames house take and being refused by it learns only
        // that the directory lies.
        List<Entry> visible = new ArrayList<>();
        for (Entry entry : ENTRIES) {
            if (!entry.operatorOnly() || source.hasPermission(2)) {
                visible.add(entry);
            }
        }

        int page = Pagination.clampPage(requestedPage, visible.size());
        source.sendSuccess(() -> Pagination.header(
                "tablegames.help.title", "/tablegames help", page, visible.size()), false);

        for (Entry entry : Pagination.slice(visible, page)) {
            source.sendSuccess(() -> line(entry), false);
        }
        return visible.size();
    }

    /**
     * One entry, with the usage clickable.
     * <p>
     * Suggested rather than run, since most of these take arguments and
     * running half a command only produces an error. Clicking puts it in the
     * chat box with the cursor after it, which is where somebody reading a
     * directory wants to end up.
     */
    private static Component line(Entry entry) {
        return Component.literal(entry.usage())
                .withStyle(style -> style
                        .withColor(ChatFormatting.YELLOW)
                        .withClickEvent(new ClickEvent(
                                ClickEvent.Action.SUGGEST_COMMAND, entry.usage()))
                        .withHoverEvent(new HoverEvent(
                                HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("tablegames.help.click_to_type"))))
                .append(Component.literal(" — ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.translatable(entry.key()).withStyle(ChatFormatting.GRAY));
    }
}
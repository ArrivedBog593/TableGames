package com.github.arrivedbog593.tablegames.platform.command;

import com.github.arrivedbog593.tablegames.platform.economy.EconomyData;
import com.github.arrivedbog593.tablegames.platform.economy.EconomyEvents;
import com.github.arrivedbog593.tablegames.platform.economy.ShopEntry;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;

import java.util.List;

/**
 * Tab completion drawn from the economy itself.
 * <p>
 * Deliberately not the vanilla item argument. Completing against all fifteen
 * hundred registered items to remove one of twelve priced ones means typing
 * the whole id by hand; completing against the twelve makes the command usable
 * with two keystrokes.
 */
public final class EconomySuggestions {

    private EconomySuggestions() {
    }

    /** Items that currently have a conversion value. */
    public static final SuggestionProvider<CommandSourceStack> PRICED_ITEMS =
            (context, builder) -> SharedSuggestionProvider.suggest(
                    EconomyEvents.economy().table().itemIds(), builder);

    /**
     * Shop entry numbers, with the item each one sells as the hover text.
     * <p>
     * A bare integer argument completes to nothing, which left the only way
     * to remove or reprice an entry being to run {@code shop list} first and
     * copy a number out of chat.
     * <p>
     * The tooltip matters more here than it looks. Numbers are positions, so
     * they shift whenever something is removed; showing the item beside each
     * one means nobody has to trust a number they memorized a minute ago.
     */
    public static final SuggestionProvider<CommandSourceStack> SHOP_ENTRIES =
            (context, builder) -> {
                List<ShopEntry> entries = EconomyData.get(
                        context.getSource().getServer()).shopEntries();
                for (int i = 0; i < entries.size(); i++) {
                    builder.suggest(i + 1, entries.get(i).stack().getHoverName());
                }
                return builder.buildFuture();
            };
}
package com.github.arrivedbog593.tablegames.platform.command;

import com.github.arrivedbog593.tablegames.platform.economy.EconomyData;
import com.github.arrivedbog593.tablegames.platform.economy.EconomyEvents;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;

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

    /** Items currently listed in the shop. */
    public static final SuggestionProvider<CommandSourceStack> SHOP_ITEMS =
            (context, builder) -> SharedSuggestionProvider.suggest(
                    EconomyData.get(context.getSource().getServer()).shopPrices().keySet(),
                    builder);
}
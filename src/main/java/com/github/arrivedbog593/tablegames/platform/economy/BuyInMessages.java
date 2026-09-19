package com.github.arrivedbog593.tablegames.platform.economy;

import com.github.arrivedbog593.tablegames.engine.table.BuyIn;
import net.minecraft.network.chat.Component;

/**
 * What to tell a player whose buy-in or top-up was turned down.
 * <p>
 * Shared by the table, which refuses, and the buy-in prompt, which warns
 * before sending. Built in one place so the two cannot drift apart: a prompt
 * that approved a figure the server then refused in other words would be
 * worse than no prompt.
 */
public final class BuyInMessages {

    private BuyInMessages() {
    }

    /**
     * @param stack     what the player already has at the table, zero to sit
     * @param available what they could still reserve
     */
    public static Component describe(BuyIn buyIn, BuyIn.Problem problem,
                                     long stack, long available) {
        return switch (problem) {
            case BELOW_MINIMUM -> Component.translatable(problem.translationKey(),
                    CreditFormat.of(buyIn.minimum()));
            case ABOVE_MAXIMUM -> Component.translatable(problem.translationKey(),
                    CreditFormat.of(buyIn.maximum()),
                    CreditFormat.of(buyIn.largestAddition(stack, Long.MAX_VALUE)));
            case INSUFFICIENT -> Component.translatable(problem.translationKey(),
                    CreditFormat.of(available));
            case NOT_POSITIVE -> Component.translatable(problem.translationKey());
        };
    }
}

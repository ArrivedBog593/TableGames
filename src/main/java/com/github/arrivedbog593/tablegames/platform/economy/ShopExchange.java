package com.github.arrivedbog593.tablegames.platform.economy;

import com.github.arrivedbog593.tablegames.engine.economy.TransactionType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Buying from the casino shop.
 * <p>
 * One direction only. The shop sells things it will not buy back, which is
 * what makes it a credit sink rather than a second cashier — and a sink is
 * what a casino economy needs to stay stable. Table games hand the house a
 * small statistical edge with enormous variance; the shop hands it a
 * predictable income with none.
 * <p>
 * Credits spent here go to the house bankroll rather than vanishing. Deleting
 * them would shrink the money supply every time somebody bought a sword,
 * which sounds tidy and quietly wrecks the economy over a few months.
 * <p>
 * Server thread only.
 */
public final class ShopExchange {

    private ShopExchange() {
    }

    /**
     * The outcome of a purchase.
     *
     * @param success    whether anything was bought
     * @param itemCount  items handed over
     * @param credits    credits charged
     * @param failureKey translation key when nothing happened, else null
     * @param affordable how many the balance would have covered
     * @param required   what the refused purchase would have cost
     */
    public record Result(boolean success, long itemCount, long credits,
                         String failureKey, long affordable, long required) {

        static Result failed(String failureKey) {
            return new Result(false, 0, 0, failureKey, 0, 0);
        }

        static Result shortOf(String failureKey, long affordable, long required) {
            return new Result(false, 0, 0, failureKey, affordable, required);
        }

        static Result ok(long itemCount, long credits) {
            return new Result(true, itemCount, credits, null, 0, 0);
        }
    }

    /**
     * Buys an exact number of an item at the shop price.
     * <p>
     * All or nothing, for the same reason the cashier is: charging for
     * fifteen and delivering ten is worse than refusing and saying why.
     */
    public static Result buy(ServerPlayer player, ShopEntry entry, long count,
                             CreditStorage storage) {
        long unitPrice = entry.price();
        if (count <= 0 || unitPrice <= 0) {
            return Result.failed("tablegames.shop.not_for_sale");
        }

        // Spendable, not balance. Wagers are not debited when they are
        // placed, so the balance still counts credits that are already riding
        // on a spin. Selling against those was a way of cancelling a wager
        // after betting had closed: buy an item with the same credits, watch
        // the round, drop a stake it could no longer cover, sell the item
        // back.
        long balance = storage.balanceOf(player.getUUID());
        long spendable = OutcomeSettler.stakes().spendable(player.getUUID(), balance);
        long cost;
        try {
            cost = Math.multiplyExact(count, unitPrice);
        } catch (ArithmeticException absurd) {
            return Result.shortOf("tablegames.shop.cannot_afford",
                    spendable / unitPrice, Long.MAX_VALUE);
        }
        if (cost > spendable) {
            // Two different problems and two different answers. Being short
            // of credits is the player's own business; being short only
            // because of a live wager is something they cannot see, and
            // saying "not enough credits" while the balance clearly says
            // otherwise reads as a bug.
            return Result.shortOf(cost <= balance
                            ? "tablegames.shop.credits_on_a_table"
                            : "tablegames.shop.cannot_afford",
                    spendable / unitPrice, cost);
        }

        ItemStack prototype = entry.prototype();
        long delivered = Math.multiplyExact(count, prototype.getCount());
        long room = Inventories.spaceFor(player, prototype);
        if (room < delivered) {
            return Result.shortOf("tablegames.shop.no_room_for", room, cost);
        }

        if (!storage.withdraw(player.getUUID(), cost)) {
            return Result.failed("tablegames.shop.cannot_afford");
        }

        // Straight into the bankroll. This is the casino's steady income, and
        // the reason it can survive a night of players getting lucky.
        storage.creditHouse(cost);
        Inventories.give(player, prototype, delivered);

        String what = delivered + "x " + entry.itemId()
                + (entry.hasComponents() ? " (custom)" : "");
        EconomyEvents.record(storage, TransactionType.SHOP_PURCHASE, player.getUUID(),
                -cost, storage.balanceOf(player.getUUID()), what);
        EconomyEvents.recordHouse(storage, TransactionType.SHOP_PURCHASE, cost,
                storage.houseBalance(), "sold " + what);

        return Result.ok(delivered, cost);
    }
}

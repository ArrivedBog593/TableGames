package com.github.arrivedbog593.tablegames.platform.economy;

import com.github.arrivedbog593.tablegames.engine.economy.TransactionType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The cashier: the one place where items become credits and credits become
 * items.
 * <p>
 * Every path is ordered so a failure partway through cannot destroy value.
 * Credits are only taken once the delivery is known to succeed in full, and
 * items are only consumed once the deposit has been accepted.
 * <p>
 * An exact request is all or nothing. Asking for fifteen and receiving ten,
 * with the balance spent is worse than being told plainly that ten is what
 * the balance covers — so a short balance or a short inventory refuses the
 * whole thing and reports the number that would have worked.
 * <p>
 * Server thread only.
 */
public final class CreditExchange {

    private CreditExchange() {
    }

    /**
     * The outcome of an exchange.
     *
     * @param success    whether anything happened
     * @param itemCount  items moved
     * @param credits    credits moved, always positive
     * @param failureKey translation key when nothing happened, else null
     * @param affordable how many items the player could actually have had
     * @param required   credits the refused request would have needed
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
     * Turns a stack into credits.
     * <p>
     * The stack is emptied only after the deposit succeeds, so hitting the
     * balance cap costs the player nothing.
     */
    public static Result deposit(ServerPlayer player, ItemStack stack,
                                 EconomyManager economy, CreditStorage storage) {
        if (!economy.isConvertible(stack)) {
            return Result.failed("tablegames.exchange.not_convertible");
        }
        long value = economy.valueOf(stack).orElse(0L);
        if (value <= 0) {
            return Result.failed("tablegames.exchange.not_convertible");
        }
        if (!storage.deposit(player.getUUID(), value)) {
            return Result.failed("tablegames.exchange.cap_reached");
        }

        int count = stack.getCount();
        stack.setCount(0);
        return Result.ok(count, value);
    }

    /**
     * Buys back as many of an item as the balance covers.
     * <p>
     * Leftover credits that do not cover one more item stay in the balance.
     * If the inventory cannot take the lot, the request is cut down to what
     * fits rather than refused: nothing was named, so nothing is being
     * shortchanged.
     */
    public static Result redeemAll(ServerPlayer player, Item item,
                                   EconomyManager economy, CreditStorage storage) {
        long unitPrice = buybackUnitOf(item, economy);
        if (unitPrice <= 0) {
            return Result.failed("tablegames.exchange.not_convertible");
        }

        long balance = storage.balanceOf(player.getUUID());
        long affordable = balance / unitPrice;
        if (affordable <= 0) {
            return Result.shortOf("tablegames.exchange.cannot_afford_one", 0, unitPrice);
        }

        long count = Math.min(affordable, Inventories.spaceFor(player, item));
        if (count <= 0) {
            return Result.failed("tablegames.exchange.no_room");
        }
        return commit(player, item, count, count * unitPrice,
                economy.table().surchargeOn(ItemIds.idOf(item), count), storage);
    }

    /**
     * Buys back an exact number of items.
     * <p>
     * Refuses outright if the balance or the inventory falls short, reporting
     * how many would have worked so the player knows what to ask for next.
     */
    public static Result redeemExactly(ServerPlayer player, Item item, long requested,
                                       EconomyManager economy, CreditStorage storage) {
        if (requested <= 0) {
            return Result.failed("tablegames.exchange.not_convertible");
        }
        long unitPrice = buybackUnitOf(item, economy);
        if (unitPrice <= 0) {
            return Result.failed("tablegames.exchange.not_convertible");
        }

        long balance = storage.balanceOf(player.getUUID());
        // Multiplied exactly: a requested count large enough to overflow used
        // to wrap negative and sail past the affordability check.
        long cost;
        try {
            cost = Math.multiplyExact(requested, unitPrice);
        } catch (ArithmeticException absurd) {
            return Result.shortOf("tablegames.exchange.cannot_afford",
                    balance / unitPrice, Long.MAX_VALUE);
        }
        if (cost > balance) {
            return Result.shortOf("tablegames.exchange.cannot_afford",
                    balance / unitPrice, cost);
        }

        long room = Inventories.spaceFor(player, item);
        if (room < requested) {
            return Result.shortOf("tablegames.exchange.no_room_for", room, cost);
        }
        return commit(player, item, requested, cost,
                economy.table().surchargeOn(ItemIds.idOf(item), requested), storage);
    }

    /** Takes the credits, then hands over the items. Both are known to work by now. */
    private static Result commit(ServerPlayer player, Item item, long count,
                                 long cost, long surcharge, CreditStorage storage) {
        if (!storage.withdraw(player.getUUID(), cost)) {
            return Result.failed("tablegames.exchange.cannot_afford_one");
        }
        // The surcharge is the house's take, not credits that stop existing.
        // Letting it vanish would shrink the money supply without anybody
        // being paid, which is a different thing from a fee and much harder
        // to reason about later.
        if (surcharge > 0) {
            storage.creditHouse(surcharge);
            EconomyEvents.recordHouse(storage, TransactionType.SPREAD, surcharge,
                    storage.houseBalance(), ItemIds.idOf(item) + " x" + count);
        }
        Inventories.give(player, item, count);
        return Result.ok(count, cost);
    }

    /** What one of these costs to buy back, surcharge included. */
    private static long buybackUnitOf(Item item, EconomyManager economy) {
        String id = ItemIds.idOf(item);
        return economy.table().contains(id) ? economy.table().buybackUnit(id) : 0;
    }
}

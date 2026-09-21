package com.github.arrivedbog593.tablegames.platform.economy;

import com.github.arrivedbog593.tablegames.engine.economy.TransactionType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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

    /** How many distinct items one buyback may name. */
    public static final int MAX_LINES = 32;

    /** The most of one item a single line may ask for. */
    public static final int MAX_COUNT = 10_000;

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
     * One line of a buyback, as the player's screen quoted it.
     * <p>
     * Keyed by item id rather than by a position in the catalog. The cashier
     * prices by id — one value per id, no duplicates possible — so the id is
     * the identity that already exists, and a position is only a rendering of
     * it. That distinction matters here in a way it does not in the shop: the
     * catalog is sent sorted by value, so repricing any single item reorders
     * the whole list and a position sent back means something else entirely.
     * The shop's positions are stored and stay put.
     *
     * @param itemId            what is being bought back
     * @param count             how many
     * @param expectedUnitPrice what one cost on the screen that sent this,
     *                          surcharge included
     */
    public record Line(String itemId, long count, long expectedUnitPrice) {
    }

    /**
     * The outcome of a buyback of several items.
     *
     * @param success    whether the whole request went through
     * @param failureKey translation key when nothing happened, else null
     * @param arguments  what that message needs, in order
     * @param itemCount  items handed over across every line
     * @param credits    credits charged across every line
     */
    public record CartResult(boolean success, String failureKey, List<Object> arguments,
                             long itemCount, long credits) {

        static CartResult failed(String failureKey, Object... arguments) {
            return new CartResult(false, failureKey, List.of(arguments), 0, 0);
        }

        static CartResult ok(long itemCount, long credits) {
            return new CartResult(true, null, List.of(), itemCount, credits);
        }

        /** The message arguments in the shape {@code Component.translatable} wants. */
        public Object[] argumentArray() {
            return arguments.toArray();
        }
    }

    /**
     * Buys back several items at once, or none of them.
     * <p>
     * The order of the checks is the point. Every line is resolved and priced,
     * then the total is weighed against what the player can actually spend,
     * then the whole delivery is fitted into a copy of their inventory — and
     * only after all three does a single credit move.
     * <p>
     * Prices are read from the live table rather than trusted, as they always
     * have been. What is new is that the screen's price travels with the
     * request and has to agree, so nobody is charged a figure they never saw.
     */
    public static CartResult redeemCart(ServerPlayer player, List<Line> lines,
                                        EconomyManager economy, CreditStorage storage) {
        if (lines.isEmpty()) {
            return CartResult.failed("tablegames.exchange.cart_empty");
        }
        if (lines.size() > MAX_LINES) {
            return CartResult.failed("tablegames.exchange.cart_invalid");
        }

        List<Priced> priced = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        long total = 0;

        for (Line line : lines) {
            if (line.count() <= 0 || line.count() > MAX_COUNT) {
                return CartResult.failed("tablegames.exchange.cart_invalid");
            }
            // Two lines for one item would each pass their own checks and
            // together charge twice. The screen merges them; a crafted packet
            // does not have to.
            if (!seen.add(line.itemId())) {
                return CartResult.failed("tablegames.exchange.cart_invalid");
            }

            Optional<Item> item = ItemIds.item(line.itemId());
            if (item.isEmpty()) {
                return CartResult.failed("tablegames.exchange.no_such_item", line.itemId());
            }
            long unitPrice = buybackUnitOf(item.get(), economy);
            if (unitPrice <= 0) {
                return CartResult.failed("tablegames.exchange.cart_not_convertible",
                        ItemIds.displayName(line.itemId()));
            }
            if (unitPrice != line.expectedUnitPrice()) {
                return CartResult.failed("tablegames.exchange.price_changed",
                        ItemIds.displayName(line.itemId()),
                        CreditFormat.of(line.expectedUnitPrice()),
                        CreditFormat.of(unitPrice));
            }

            long cost;
            try {
                cost = Math.multiplyExact(line.count(), unitPrice);
                total = Math.addExact(total, cost);
            } catch (ArithmeticException absurd) {
                return CartResult.failed("tablegames.exchange.cart_invalid");
            }
            priced.add(new Priced(item.get(), line.itemId(), line.count(), cost));
        }

        // Spendable, not balance: what is riding on a live round is already
        // promised to it. See the note in redeemExactly.
        long balance = storage.balanceOf(player.getUUID());
        long spendable = OutcomeSettler.stakes().spendable(player.getUUID(), balance);
        if (total > spendable) {
            return CartResult.failed(total <= balance
                            ? "tablegames.exchange.cart_credits_on_a_table"
                            : "tablegames.exchange.cart_cannot_afford",
                    CreditFormat.of(spendable), CreditFormat.of(total));
        }

        // Fitted all together against one working copy, so each line sees the
        // slots the lines before it already claimed. One at a time, three
        // lines of sixty-four each pass on their own and fail together.
        Inventories.Space space = Inventories.snapshot(player);
        long items = 0;
        for (Priced line : priced) {
            ItemStack prototype = new ItemStack(line.item());
            long room = space.roomFor(prototype);
            if (space.reserve(prototype, line.count()) < line.count()) {
                // Named, because "not enough room" over a list of items
                // leaves the player guessing which one to trim.
                return CartResult.failed("tablegames.exchange.cart_no_room_for",
                        ItemIds.displayName(line.itemId()), room);
            }
            items = Math.addExact(items, line.count());
        }

        // Everything above passed, so nothing below can fail. Charged line by
        // line rather than once for the total, so each logged movement shows
        // the balance it actually left behind.
        for (Priced line : priced) {
            deliver(player, economy, storage, line);
        }
        return CartResult.ok(items, total);
    }

    /** One resolved line, priced and ready. */
    private record Priced(Item item, String itemId, long count, long cost) {
    }

    /** Charges for one line and hands it over. Only ever called after validation. */
    private static void deliver(ServerPlayer player, EconomyManager economy,
                                CreditStorage storage, Priced line) {
        if (!storage.withdraw(player.getUUID(), line.cost())) {
            // Unreachable: the total was checked against the spendable
            // balance before anything moved. Left loud rather than silent,
            // because if it ever fires, the check above is wrong.
            throw new IllegalStateException(
                    "Cashier cart passed its affordability check and then could not pay for "
                            + line.itemId());
        }

        long surcharge = economy.table().surchargeOn(line.itemId(), line.count());
        if (surcharge > 0) {
            storage.creditHouse(surcharge);
            EconomyEvents.recordHouse(storage, TransactionType.SPREAD, surcharge,
                    storage.houseBalance(), line.itemId() + " x" + line.count());
        }
        Inventories.give(player, line.item(), line.count());

        EconomyEvents.record(storage, TransactionType.CONVERT_OUT, player.getUUID(),
                -line.cost(), storage.balanceOf(player.getUUID()),
                line.count() + "x " + line.itemId() + " (cashier)");
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

        // Spendable, not balance: what is riding on a live round is already
        // promised to it. See the note in redeemExactly.
        long balance = storage.balanceOf(player.getUUID());
        long spendable = OutcomeSettler.stakes().spendable(player.getUUID(), balance);
        long affordable = spendable / unitPrice;
        if (affordable <= 0) {
            return Result.shortOf(unitPrice <= balance
                            ? "tablegames.exchange.credits_on_a_table"
                            : "tablegames.exchange.cannot_afford_one",
                    0, unitPrice);
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

        // Spendable, not balance. A wager is not debited when it is placed,
        // so the balance still counts credits that a spin is waiting on.
        // Buying items with them was a way of canceling a wager after
        // betting had closed, since the round then dropped a stake it could
        // no longer cover.
        long balance = storage.balanceOf(player.getUUID());
        long spendable = OutcomeSettler.stakes().spendable(player.getUUID(), balance);
        // Multiplied exactly: a requested count large enough to overflow used
        // to wrap negative and sail past the affordability check.
        long cost;
        try {
            cost = Math.multiplyExact(requested, unitPrice);
        } catch (ArithmeticException absurd) {
            return Result.shortOf("tablegames.exchange.cannot_afford",
                    spendable / unitPrice, Long.MAX_VALUE);
        }
        if (cost > spendable) {
            // Short of credits and short only because of a live wager are
            // different problems. Saying "not enough credits" to somebody
            // whose balance plainly covers it reads as a bug.
            return Result.shortOf(cost <= balance
                            ? "tablegames.exchange.credits_on_a_table"
                            : "tablegames.exchange.cannot_afford",
                    spendable / unitPrice, cost);
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

package com.github.arrivedbog593.tablegames.platform.economy;

import com.github.arrivedbog593.tablegames.engine.economy.TransactionType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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

    /**
     * How many distinct entries one purchase may name.
     * <p>
     * A cart is assembled by hand, so this is far above anything a player
     * would build. It exists so a crafted packet cannot ask the server to
     * walk an arbitrary list.
     */
    public static final int MAX_LINES = 32;

    /** The most of one entry a single line may ask for. */
    public static final int MAX_COUNT = 10_000;

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
     * One line of a cart, as the player's screen quoted it.
     *
     * @param number            the entry's place in the catalog as the server
     *                          sent it. The position is the entry's identity:
     *                          the shop sells whole stacks, so two entries can
     *                          share an item id and differ only in their
     *                          components
     * @param count             how many lots, not how many items. An entry
     *                          selling sixteen ingots delivers thirty-two for
     *                          a count of two
     * @param expectedUnitPrice what one-lot cost on the screen that sent this
     */
    public record Line(int number, long count, long expectedUnitPrice) {
    }

    /**
     * The outcome of a cart.
     * <p>
     * Carries its own message arguments because a cart can fail for a reason
     * that names a specific entry, and "some line changed price" is not a
     * usable thing to tell somebody looking at nine of them.
     *
     * @param success    whether the whole cart went through
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
     * Buys a whole cart, or none of it.
     * <p>
     * The order of the checks is the point. Every line is resolved and priced,
     * then the total is weighed against what the player can actually spend,
     * then the whole delivery is fitted into a copy of their inventory — and
     * only after all three does a single credit move. A cart that failed
     * halfway would have charged for some lines and refused others, which is
     * the shape of bug that ends in a manual refund.
     * <p>
     * Prices are re-read from {@link EconomyData} rather than trusted, as they
     * always have been. What is new is that the screen's price travels with
     * the request and has to agree: the server used to charge whatever the
     * catalog said at the moment the click landed, so an administrator
     * repricing an entry while somebody was reading it charged them a figure
     * they never saw. Refusing costs nothing — the player buys again a second
     * later at the price now on screen — and it protects them in both
     * directions, since a price that fell is just as wrong as one that rose.
     */
    public static CartResult buyAll(ServerPlayer player, List<Line> lines,
                                    CreditStorage storage) {
        if (lines.isEmpty()) {
            return CartResult.failed("tablegames.shop.cart_empty");
        }
        if (lines.size() > MAX_LINES) {
            return CartResult.failed("tablegames.shop.cart_invalid");
        }

        EconomyData data = EconomyData.get(player.server);

        // Resolved and priced first, with nothing touched. A line naming an
        // entry that has since been removed is a stale screen, not an attack:
        // entry numbers shift when an administrator deletes one, so what the
        // player is looking at may simply have moved.
        List<Priced> priced = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        long total = 0;

        for (Line line : lines) {
            if (line.count() <= 0 || line.count() > MAX_COUNT) {
                return CartResult.failed("tablegames.shop.cart_invalid");
            }
            // Two lines for one entry would each pass their own checks and
            // together charge twice. The screen merges them; a crafted packet
            // does not have to.
            if (!seen.add(line.number())) {
                return CartResult.failed("tablegames.shop.cart_invalid");
            }

            Optional<ShopEntry> found = data.shopEntry(line.number());
            if (found.isEmpty()) {
                return CartResult.failed("tablegames.shop.entry_gone");
            }
            ShopEntry entry = found.get();
            if (entry.price() <= 0) {
                return CartResult.failed("tablegames.shop.not_for_sale");
            }
            if (entry.price() != line.expectedUnitPrice()) {
                return CartResult.failed("tablegames.shop.price_changed",
                        entry.prototype().getHoverName(),
                        CreditFormat.of(line.expectedUnitPrice()),
                        CreditFormat.of(entry.price()));
            }

            long cost;
            long delivered;
            try {
                cost = Math.multiplyExact(line.count(), entry.price());
                delivered = Math.multiplyExact(line.count(), entry.prototype().getCount());
                total = Math.addExact(total, cost);
            } catch (ArithmeticException absurd) {
                return CartResult.failed("tablegames.shop.cart_invalid");
            }
            priced.add(new Priced(entry, line.count(), cost, delivered));
        }

        // Spendable, not balance. Wagers are not debited when they are
        // placed, so the balance still counts credits that are already riding
        // on a spin. Selling against those was a way of cancelling a wager
        // after betting had closed: buy an item with the same credits, watch
        // the round, drop a stake it could no longer cover, sell the item
        // back.
        long balance = storage.balanceOf(player.getUUID());
        long spendable = OutcomeSettler.stakes().spendable(player.getUUID(), balance);
        if (total > spendable) {
            // Two different problems and two different answers. Being short
            // of credits is the player's own business; being short only
            // because of a live wager is something they cannot see, and
            // saying "not enough credits" while the balance clearly says
            // otherwise reads as a bug.
            return CartResult.failed(total <= balance
                            ? "tablegames.shop.cart_credits_on_a_table"
                            : "tablegames.shop.cart_cannot_afford",
                    CreditFormat.of(spendable), CreditFormat.of(total));
        }

        // Fitted all together against one working copy, so each line sees the
        // slots the lines before it already claimed.
        Inventories.Space space = Inventories.snapshot(player);
        long items = 0;
        for (Priced line : priced) {
            long room = space.roomFor(line.entry().prototype());
            if (space.reserve(line.entry().prototype(), line.delivered()) < line.delivered()) {
                // Named, because "not enough room" over a cart of nine lines
                // leaves the player guessing which one to trim. The figure is
                // room for this line with the earlier ones already fitted,
                // which is the number they need to type.
                return CartResult.failed("tablegames.shop.cart_no_room_for",
                        line.entry().prototype().getHoverName(), room);
            }
            items = Math.addExact(items, line.delivered());
        }

        // Everything above passed, so nothing below can fail. Charged line by
        // line rather than once for the total, so each logged movement shows
        // the balance it actually left behind — a single withdrawal followed
        // by per-line log entries would write balances that never existed.
        for (Priced line : priced) {
            deliver(player, storage, line);
        }
        return CartResult.ok(items, total);
    }

    /** One resolved line, priced and ready. */
    private record Priced(ShopEntry entry, long count, long cost, long delivered) {
    }

    /** Charges for one line and hands it over. Only ever called after validation. */
    private static void deliver(ServerPlayer player, CreditStorage storage, Priced line) {
        if (!storage.withdraw(player.getUUID(), line.cost())) {
            // Unreachable: the total was checked against the spendable
            // balance before anything moved, and nothing else runs on this
            // thread in between. Left as a loud line in the log rather than a
            // silent return, because if it ever fires, the check above is
            // wrong and that is worth knowing.
            throw new IllegalStateException(
                    "Shop cart passed its affordability check and then could not pay for "
                            + line.entry().itemId());
        }

        // Straight into the bankroll. This is the casino's steady income, and
        // the reason it can survive a night of players getting lucky.
        storage.creditHouse(line.cost());
        Inventories.give(player, line.entry().prototype(), line.delivered());

        String what = line.delivered() + "x " + line.entry().itemId()
                + (line.entry().hasComponents() ? " (custom)" : "");
        EconomyEvents.record(storage, TransactionType.SHOP_PURCHASE, player.getUUID(),
                -line.cost(), storage.balanceOf(player.getUUID()), what);
        EconomyEvents.recordHouse(storage, TransactionType.SHOP_PURCHASE, line.cost(),
                storage.houseBalance(), "sold " + what);
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
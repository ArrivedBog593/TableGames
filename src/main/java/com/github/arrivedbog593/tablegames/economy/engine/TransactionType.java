package com.github.arrivedbog593.tablegames.economy.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a logged credit movement was for.
 * <p>
 * The {@code recoverable} flag decides what may be replayed after a crash,
 * and it is the whole reason this is a type rather than a free-text label.
 * <p>
 * The distinction is not about importance but about whether items were
 * involved. A world rolled back to its last save also rolls back
 * inventories: a player who converted sixty-four ingots gets the ingots back.
 * Replaying the credits from that conversion would hand them both, turning a
 * crash into a duplication exploit. So conversions and purchases are left to
 * revert alongside the items they touched, while purely numeric movements —
 * admin grants, transfers, a game's wagers and winnings — are safe to restore.
 * <p>
 * An open set rather than an enum, because the economy does not know every
 * reason credits move: a mod built on it registers its own with
 * {@link #register}, the way TableGames registers its wagers and payouts. It
 * must do so before the server starts — that is when crash recovery reads the
 * log, and a line whose type nobody has registered is not replayed.
 */
public final class TransactionType {

    private static final Map<String, TransactionType> BY_CODE = new ConcurrentHashMap<>();

    /** An operator granted credits. No items involved. */
    public static final TransactionType ADMIN_GIVE = register("admin_give", true);

    /** An operator removed credits. No items involved. */
    public static final TransactionType ADMIN_TAKE = register("admin_take", true);

    /** Credits moved between two players. */
    public static final TransactionType TRANSFER = register("transfer", true);

    /** Items became credits. The items revert with the world, so this must too. */
    public static final TransactionType CONVERT_IN = register("convert_in", false);

    /** Credits became items. Same reasoning in reverse. */
    public static final TransactionType CONVERT_OUT = register("convert_out", false);

    /** A shop purchase delivered an item. */
    public static final TransactionType SHOP_PURCHASE = register("shop_purchase", false);

    /**
     * The house's cut of a buyback.
     * <p>
     * Not recoverable, and for the same reason as the conversion it rides on:
     * the item handed over reverts with the world on a rollback, so replaying
     * the fee would charge for a trade that no longer happened.
     */
    public static final TransactionType SPREAD = register("spread", false);

    private final String code;
    private final boolean recoverable;

    private TransactionType(String code, boolean recoverable) {
        this.code = code;
        this.recoverable = recoverable;
    }

    /**
     * Declares a reason credits move.
     * <p>
     * Registering the same code again with the same flag returns the type
     * already registered, so a class that is loaded twice — or two tests that
     * both declare one — cannot trip over each other. The same code with the
     * other flag is a mistake that would decide crash recovery by load order,
     * so it throws.
     *
     * @param code        what the log writes: lowercase letters, digits and
     *                    underscores, and never changed once worlds have used it
     * @param recoverable whether replaying it after a crash is safe — true
     *                    only if no item changed hands
     */
    public static TransactionType register(String code, boolean recoverable) {
        Objects.requireNonNull(code, "code");
        if (!code.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("Transaction codes are lowercase snake case, was " + code);
        }
        TransactionType type = BY_CODE.computeIfAbsent(code, c -> new TransactionType(c, recoverable));
        if (type.recoverable != recoverable) {
            throw new IllegalStateException("Transaction type " + code
                    + " is already registered as " + (type.recoverable ? "recoverable" : "not recoverable"));
        }
        return type;
    }

    /** Whether replaying this after a crash is safe from duplication. */
    public boolean isRecoverable() {
        return recoverable;
    }

    /** The name written to the log. */
    public String code() {
        return code;
    }

    /** Parses a code from a log line, or null if nobody has registered it. */
    public static TransactionType fromCode(String code) {
        return code == null ? null : BY_CODE.get(code);
    }

    /** Every type registered so far, in no particular order. */
    public static List<TransactionType> values() {
        return Collections.unmodifiableList(new ArrayList<>(BY_CODE.values()));
    }

    @Override
    public String toString() {
        return code.toUpperCase(Locale.ROOT);
    }
}

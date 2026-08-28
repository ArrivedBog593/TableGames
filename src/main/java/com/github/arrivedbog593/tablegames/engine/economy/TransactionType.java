package com.github.arrivedbog593.tablegames.engine.economy;

/**
 * What a logged credit movement was for.
 * <p>
 * The {@code recoverable} flag decides what may be replayed after a crash,
 * and it is the whole reason this is an enum rather than a free-text label.
 * <p>
 * The distinction is not about importance but about whether items were
 * involved. A world rolled back to its last save also rolls back
 * inventories: a player who converted sixty-four ingots gets the ingots back.
 * Replaying the credits from that conversion would hand them both, turning a
 * crash into a duplication exploit. So conversions and purchases are left to
 * revert alongside the items they touched, while purely numeric movements —
 * bets, payouts, rake, admin grants — are safe to restore.
 */
public enum TransactionType {

    /** An operator granted credits. No items involved. */
    ADMIN_GIVE(true),

    /** An operator removed credits. No items involved. */
    ADMIN_TAKE(true),

    /** Credits moved between two players. */
    TRANSFER(true),

    /** A wager left a player's balance. */
    BET(true),

    /** Winnings arrived. */
    PAYOUT(true),

    /** The house took its cut. */
    RAKE(true),

    /** The house covered a payout it was liable for. */
    HOUSE_PAYOUT(true),

    /** Items became credits. The items revert with the world, so this must too. */
    CONVERT_IN(false),

    /** Credits became items. Same reasoning in reverse. */
    CONVERT_OUT(false),

    /** A shop purchase delivered an item. */
    SHOP_PURCHASE(false);

    private final boolean recoverable;

    TransactionType(boolean recoverable) {
        this.recoverable = recoverable;
    }

    /** Whether replaying this after a crash is safe from duplication. */
    public boolean isRecoverable() {
        return recoverable;
    }

    /** Lowercase name as written to the log. */
    public String code() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Parses a code from a log line, or null if unrecognized. */
    public static TransactionType fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (TransactionType type : values()) {
            if (type.code().equals(code)) {
                return type;
            }
        }
        return null;
    }
}
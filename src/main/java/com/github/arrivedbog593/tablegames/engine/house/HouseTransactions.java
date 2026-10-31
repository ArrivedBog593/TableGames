package com.github.arrivedbog593.tablegames.engine.house;

import com.github.arrivedbog593.economycore.api.TransactionType;

/**
 * The reasons credits move that only the casino has.
 * <p>
 * All four are purely numeric — no item changes hands at a table — so all
 * four are safe to replay after a crash. The codes are what the log has
 * always written for them, so logs from before the economy was split out
 * still recover.
 * <p>
 * They have to be registered before the server starts, which is when crash
 * recovery reads the log: {@link #bootstrap} is called from the mod's
 * constructor for that reason.
 */
public final class HouseTransactions {

    /** A wager left a player's balance. */
    public static final TransactionType BET = TransactionType.register("bet", true);

    /** Winnings arrived. */
    public static final TransactionType PAYOUT = TransactionType.register("payout", true);

    /** The house took its cut. */
    public static final TransactionType RAKE = TransactionType.register("rake", true);

    /** The house covered a payout it was liable for. */
    public static final TransactionType HOUSE_PAYOUT = TransactionType.register("house_payout", true);

    private HouseTransactions() {
    }

    /** Registers the types; the fields above do it, so this only loads the class. */
    public static void bootstrap() {
        // Nothing to do beyond being called.
    }
}

package com.github.arrivedbog593.tablegames.engine.session;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The settled result of a finished hand.
 * <p>
 * The engine produces this; the platform layer is what actually moves
 * credits. That split keeps every rule testable without persistence and
 * gives one single place where money changes hands.
 *
 * @param payouts    net change per player; every seated player appears exactly once
 * @param winners    players who took a share of the pots, possibly several on a split
 * @param rake       credits withheld by the house
 * @param summaryKey translation key describing how the hand ended
 */
public record Outcome(List<Payout> payouts, List<UUID> winners, long rake, String summaryKey) {

    public Outcome {
        payouts = List.copyOf(Objects.requireNonNull(payouts, "payouts"));
        winners = List.copyOf(Objects.requireNonNull(winners, "winners"));
        Objects.requireNonNull(summaryKey, "summaryKey");
        if (rake < 0) {
            throw new IllegalArgumentException("Negative rake: " + rake);
        }
    }

    /**
     * Total credits created or destroyed by this hand, rake included.
     * <p>
     * For a player-versus-player game this must be zero: chips only change
     * an owner. A non-zero value means the house was on the hook, which is legal
     * for blackjack and roulette but a bug for poker or dompe. Assert on this
     * in tests — it is the cheapest possible guard against an economic exploit.
     */
    public long netCreditChange() {
        long sum = rake;
        for (Payout payout : payouts) {
            sum += payout.delta();
        }
        return sum;
    }

    /** True when no credits were minted: the table only redistributed chips. */
    public boolean isZeroSum() {
        return netCreditChange() == 0;
    }
}
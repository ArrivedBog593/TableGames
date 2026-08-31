package com.github.arrivedbog593.tablegames.engine.games.roulette;

import java.util.Objects;

/**
 * A single wager on the layout.
 * <p>
 * A player may hold several of these at once: 500 on black, 200 on 17, and
 * 300 on the first dozen are three separate bets resolved independently.
 * This is why roulette keeps its own bet list instead of relying on
 * {@code Seat.currentBet}, which is a single running total.
 *
 * @param type   what is being bet on
 * @param target the named pocket for a straight-up bet, null otherwise
 * @param amount credits staked, always positive
 */
public record RouletteBet(BetType type, Pocket target, long amount) {

    public RouletteBet {
        Objects.requireNonNull(type, "type");
        if (amount <= 0) {
            throw new IllegalArgumentException("Bet amount must be positive: " + amount);
        }
        if (type.requiresTarget() && target == null) {
            throw new IllegalArgumentException(type + " requires a target pocket");
        }
        if (!type.requiresTarget() && target != null) {
            throw new IllegalArgumentException(type + " must not name a pocket");
        }
    }

    /** An outside bet such as red, odd, or a dozen. */
    public static RouletteBet outside(BetType type, long amount) {
        return new RouletteBet(type, null, amount);
    }

    /** A bet on one specific pocket. */
    public static RouletteBet straightUp(Pocket target, long amount) {
        return new RouletteBet(BetType.STRAIGHT_UP, target, amount);
    }

    public boolean wins(Pocket result) {
        return type.wins(result, target);
    }

    /**
     * Total credits returned to the player if this bet wins: the stake plus
     * the profit. Losing bets return nothing, as the stake was already taken
     * when the bet was placed.
     */
    public long payout(Pocket result) {
        return wins(result) ? amount + amount * type.payoutRatio() : 0;
    }

    /**
     * What the house owes on this bet if the ball lands in {@code result}:
     * positive when it pays out, negative when it keeps the stake.
     * <p>
     * The profit only, not the returned stake. Credits do not move when a bet
     * is placed, so the stake never left the player, and the house is only ever
     * on the hook for the winnings.
     */
    public long houseCost(Pocket result) {
        return wins(result) ? amount * type.payoutRatio() : -amount;
    }
}

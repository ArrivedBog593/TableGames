package com.github.arrivedbog593.tablegames.engine.games.blackjack;

import java.util.Objects;
import java.util.UUID;

/**
 * A player sitting in the dealer's chair, staking their own credits.
 * <p>
 * The kitchen-table game. Nobody dealt in plays against the house: each
 * plays against this player, who takes what the others lose and pays what
 * they win, so the hand is zero-sum.
 *
 * @param playerId    who holds the bank this hand
 * @param credits     what they can pay out; the hand never takes on a
 *                    wager they could not cover
 * @param playsByHand whether they choose their own draws, as at a kitchen
 *                    table, or the dealer's rule draws for them
 * @param winsTies    whether a tie goes to the bank instead of being a push,
 *                    the traditional home rule
 */
public record BlackjackBanker(UUID playerId, long credits, boolean playsByHand, boolean winsTies) {

    public BlackjackBanker {
        Objects.requireNonNull(playerId, "playerId");
        if (credits < 0) {
            throw new IllegalArgumentException("Negative credits: " + credits);
        }
    }
}

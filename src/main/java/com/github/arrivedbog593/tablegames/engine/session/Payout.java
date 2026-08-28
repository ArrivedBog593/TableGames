package com.github.arrivedbog593.tablegames.engine.session;

import java.util.Objects;
import java.util.UUID;

/**
 * A single player's net credit change from a finished hand.
 *
 * @param playerId who is being settled
 * @param delta    net change: positive for a win, negative for a loss, zero
 *                 for a pushed bet
 */
public record Payout(UUID playerId, long delta) {

    public Payout {
        Objects.requireNonNull(playerId, "playerId");
    }

    public boolean isWin() {
        return delta > 0;
    }

    public boolean isPush() {
        return delta == 0;
    }
}
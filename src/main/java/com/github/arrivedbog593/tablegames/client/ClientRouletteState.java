package com.github.arrivedbog593.tablegames.client;

import com.github.arrivedbog593.tablegames.platform.network.RouletteStatePayload;

/**
 * The roulette round the server last described, for the screen to draw.
 * <p>
 * Client only and entirely without authority. Every bet the screen sends is
 * revalidated against the table maximum, the house bankroll and the player's
 * real balance before it counts for anything.
 */
public final class ClientRouletteState {

    private static RouletteStatePayload state = RouletteStatePayload.idle();

    private ClientRouletteState() {
    }

    public static void accept(RouletteStatePayload payload) {
        state = payload;
    }

    public static RouletteStatePayload state() {
        return state;
    }

    /** What this player has staked on the layout this round. */
    public static long wagered() {
        long total = 0;
        for (RouletteStatePayload.Wager wager : state.myBets()) {
            total += wager.amount();
        }
        return total;
    }
}
package com.github.arrivedbog593.tablegames.client;

import com.github.arrivedbog593.tablegames.platform.network.RouletteStatePayload;

/**
 * The roulette round the server last described, for the screen to draw.
 * <p>
 * Client only and entirely without authority. Every bet the screen sends is
 * revalidated against the table maximum, the house bankroll, and the player's
 * real balance before it counts for anything.
 */
public final class ClientRouletteState {

    private static RouletteStatePayload state = RouletteStatePayload.idle();

    /** When a winning pocket first appeared, for timing the wheel animation. */
    private static long resultArrivedAt;

    private ClientRouletteState() {
    }

    public static void accept(RouletteStatePayload payload) {
        // The animation runs off a local clock started the moment the result
        // arrives, not off a countdown in the packet. State is broadcast about
        // once a second, which is far too coarse for a ball to move smoothly,
        // and the animation is cosmetic anyway — the round is already settled
        // by the time any of this is drawn.
        boolean appeared = payload.hasResult() && !state.hasResult();
        if (appeared) {
            resultArrivedAt = System.currentTimeMillis();
        } else if (!payload.hasResult()) {
            resultArrivedAt = 0;
        }
        state = payload;
    }

    /**
     * Milliseconds since the winning pocket was announced, or -1 when no
     * result is being shown.
     */
    public static long sinceResult() {
        if (!state.hasResult() || resultArrivedAt == 0) {
            return -1;
        }
        return System.currentTimeMillis() - resultArrivedAt;
    }

    public static RouletteStatePayload state() {
        return state;
    }

    /** Whether the viewer holds a seat rather than only watching. */
    public static boolean isSeated() {
        return state.isSeated();
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
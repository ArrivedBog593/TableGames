package com.github.arrivedbog593.tablegames.client;

import com.github.arrivedbog593.tablegames.engine.table.RoundPhase;
import com.github.arrivedbog593.tablegames.platform.network.RouletteStatePayload;

import java.util.List;

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

    /**
     * What the viewer had on the felt the last time a spin actually settled.
     * <p>
     * Captured here rather than remembered by the screen, because a screen
     * is thrown away and rebuilt every time the player reopens the table,
     * while this survives for as long as the game does — closing and
     * reopening the table should not cost you the bet "repeat" would rebuild.
     */
    private static List<RouletteStatePayload.Wager> lastSettledBets = List.of();

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

        // Snapshotted from the phase, not from "bets went from something to
        // nothing" — that also happens the moment a player clears their own
        // bets mid-round, and a manual clear is not a settled round with
        // something worth repeating. By the time RESULT arrives the felt has
        // already been swept for the next round, so what "repeat" needs has
        // to come from the state just before this one, not from this packet.
        if (payload.phase() == RoundPhase.RESULT && !state.myBets().isEmpty()) {
            lastSettledBets = state.myBets();
        }
        state = payload;
    }

    /**
     * What to rebuild for "repeat" or "double", from the last round the
     * viewer actually had something riding on. Empty when they never have.
     */
    public static List<RouletteStatePayload.Wager> lastSettledBets() {
        return lastSettledBets;
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
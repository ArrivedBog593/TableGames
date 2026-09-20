package com.github.arrivedbog593.tablegames.client;

import com.github.arrivedbog593.tablegames.platform.network.SlotsStatePayload;

/**
 * The slot machine the server last described, for the screen to draw.
 * <p>
 * Client only and entirely without authority. Every pull the screen sends is
 * revalidated against the machine's limits, the house bankroll and the
 * player's real stack before a reel turns.
 */
public final class ClientSlotsState {

    private static SlotsStatePayload state = SlotsStatePayload.idle();

    /** When the reels last started turning, for timing the animation. */
    private static long rollStartedAt;

    /**
     * What the player last pulled for, so the machine offers the same again.
     * <p>
     * Kept here rather than in the screen, which is thrown away and rebuilt
     * every time the player reopens the cabinet. Somebody who stepped away
     * for a moment should find the lines and the stake they were playing,
     * not one line at the minimum.
     */
    private static int lines = 1;
    private static long perLine;

    private ClientSlotsState() {
    }

    public static void accept(SlotsStatePayload payload) {
        // The reels run off a local clock started when the server says they
        // are turning, not off a countdown in the packet. State arrives about
        // once a tick at best, which is too coarse to spin anything smoothly,
        // and the animation is cosmetic: the spin was settled before the
        // first frame of it was drawn.
        if (payload.machine().rolling() && !state.machine().rolling()) {
            rollStartedAt = System.currentTimeMillis();
        } else if (!payload.machine().rolling()) {
            rollStartedAt = 0;
        }
        state = payload;
    }

    public static SlotsStatePayload state() {
        return state;
    }

    public static boolean isSeated() {
        return state.isSeated();
    }

    /** Milliseconds since the reels started, or -1 when they are still. */
    public static long sinceRollStarted() {
        return rollStartedAt == 0 ? -1 : System.currentTimeMillis() - rollStartedAt;
    }

    /** How many lines the player has selected. */
    public static int lines() {
        return Math.clamp(lines, 1, com.github.arrivedbog593.tablegames.engine.games.slots.Payline.MAX);
    }

    public static void setLines(int chosen) {
        lines = chosen;
    }

    /**
     * What the player is staking a line, never below what the machine takes.
     * <p>
     * Clamped on the way out rather than on the way in, because the machine
     * the figure has to suit is the one the screen is looking at now: a
     * player who walks from a one-credit cabinet to a hundred-credit one
     * should not be offered their old stake as if it were legal here.
     */
    public static long perLine() {
        long minimum = state.machine().betMinimum();
        long maximum = state.machine().betMaximum();
        long chosen = Math.max(perLine, minimum);
        return maximum > 0 ? Math.min(chosen, maximum) : chosen;
    }

    public static void setPerLine(long chosen) {
        perLine = Math.max(0, chosen);
    }
}

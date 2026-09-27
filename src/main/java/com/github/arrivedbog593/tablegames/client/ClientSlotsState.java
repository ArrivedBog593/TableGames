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
     * When the server last said they had stopped, which is not when they
     * stop on screen.
     * <p>
     * The landing symbols arrive in the same breath as the word that the
     * spin is over, and a drum that was at full speed one frame and parked
     * the next does not read as a drum. So the screen keeps turning for a
     * moment after this, slowing each reel onto what it landed on. Zero
     * while they are turning, and while they never have on this client.
     */
    private static long rollStoppedAt;

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
        boolean was = state.machine().rolling();
        boolean turning = payload.machine().rolling();
        if (turning && !was) {
            rollStartedAt = System.currentTimeMillis();
            rollStoppedAt = 0;
        } else if (was && !turning) {
            // Left standing rather than cleared: the screen still has a reel
            // or two to bring down, and it measures where they are from the
            // moment they started.
            rollStoppedAt = System.currentTimeMillis();
        }
        state = payload;
    }

    public static SlotsStatePayload state() {
        return state;
    }

    public static boolean isSeated() {
        return state.isSeated();
    }

    /** Milliseconds since the reels started, or -1 when they never have. */
    public static long sinceRollStarted() {
        return rollStartedAt == 0 ? -1 : System.currentTimeMillis() - rollStartedAt;
    }

    /**
     * Milliseconds since the server said the reels had stopped, or -1 while
     * they are turning and for a player who arrived after they had.
     * <p>
     * The second case is why this is not derived from the payload: somebody
     * who opens a cabinet showing last week's result should see it sitting
     * there, not watch it land.
     */
    public static long sinceRollStopped() {
        return rollStoppedAt == 0 ? -1 : System.currentTimeMillis() - rollStoppedAt;
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
        return Math.clamp(perLine, state.machine().betMinimum(), ceiling());
    }

    /**
     * The most a line may be staked at on the lines chosen now: the machine's
     * own maximum, or what the house can still cover, whichever is lower.
     * Never below the minimum, so a house that cannot cover even that leaves
     * the stake at the minimum and the server says why when the lever is
     * pulled, rather than the screen offering a stake the machine does not take.
     */
    public static long ceiling() {
        long minimum = state.machine().betMinimum();
        long maximum = state.machine().betMaximum();
        long ceiling = Math.min(maximum > 0 ? maximum : Long.MAX_VALUE,
                state.machine().houseCap(lines()));
        return Math.max(minimum, ceiling);
    }

    public static void setPerLine(long chosen) {
        perLine = Math.max(0, chosen);
    }
}

package com.github.arrivedbog593.tablegames.platform.block;

/**
 * How the roulette strip runs down to its pocket, for everything that shows
 * it or sounds it.
 * <p>
 * A ball, not a drum: it leaves the hand at full speed and only ever slows,
 * so there is no spin-up here — that is the slot machine's, and belongs to
 * reels that start from rest. What there is instead is three stretches.
 * First the ball runs round the rim, shedding speed fast. Then it drops off
 * the rim and rolls the last few pockets slowly enough to read each one go
 * by. Then it arrives — still moving — overshoots the winner, strikes a fret
 * and comes back past it, strikes again, and settles.
 * <p>
 * The roll is the reason for splitting the run at all. A single ease-out
 * curve spent almost its whole length in a blur and was within two pockets
 * of the end with most of a second still to go, so the slowing everybody
 * watches a wheel for lasted about three tenths of a second. The bounces are
 * the reason the roll does not stop dead on the winner: a real ball hops
 * between pockets before it lies still, and the moment it seems about to
 * settle in the wrong one is most of what watching a wheel is for.
 * <p>
 * Theater, all of it. The winner is decided on the server before the first
 * frame; this only decides how the waiting looks and sounds. Measured in
 * pockets, not pixels, and continuous throughout: no stretch hands the next
 * a speed it did not have.
 */
public final class WheelMotion {

    /** How long the ball runs round the rim. */
    static final double RIM_MILLIS = 2300;

    /** How fast the ball is going when it leaves the rim, in pockets per millisecond. */
    static final double ROLL_SPEED = 9.0 / 1000;

    /** How fast it is still going when it reaches the winner. */
    static final double ARRIVE_SPEED = 6.0 / 1000;

    /** How many pockets it rolls past between leaving the rim and reaching the winner. */
    static final double ROLL_POCKETS = 5;

    /** How long the roll takes, slowing steadily from one speed to the other. */
    static final double ROLL_MILLIS = 2 * ROLL_POCKETS / (ROLL_SPEED + ARRIVE_SPEED);

    /** How far past the winner it carries before the first fret turns it back. */
    static final double OVERSHOOT = 0.8;

    /** How long that takes, slowing steadily from its arriving speed to nothing. */
    static final double OVERSHOOT_MILLIS = 2 * OVERSHOOT / ARRIVE_SPEED;

    /**
     * Where each swing after the overshoot ends, as pockets from the winner,
     * and how long it takes: back past it, forward a little, and still. Each
     * smaller than the last, each a little quicker.
     */
    private static final double[] SWING_TO = {-0.35, 0.15, 0.0};
    private static final double[] SWING_MILLIS = {150, 110, 90};

    /** When the ball first reaches the winner. */
    static final double ARRIVE_AT = RIM_MILLIS + ROLL_MILLIS;

    /** When it stops at the far end of the overshoot. */
    static final double PEAK_AT = ARRIVE_AT + OVERSHOOT_MILLIS;

    private static final double END_AT;

    /**
     * When the ball strikes a fret and turns back: the end of the overshoot
     * and of every swing but the last. The table's sound puts a clack on
     * each, so what is heard and what is seen turn at the same instant.
     */
    public static final long[] HIT_MILLIS;

    static {
        double at = PEAK_AT;
        HIT_MILLIS = new long[SWING_TO.length];
        HIT_MILLIS[0] = Math.round(PEAK_AT);
        for (int i = 0; i < SWING_TO.length; i++) {
            at += SWING_MILLIS[i];
            if (i + 1 < SWING_TO.length) {
                HIT_MILLIS[i + 1] = Math.round(at);
            }
        }
        END_AT = at;
    }

    /** From the throw to the ball lying still in its pocket. */
    public static final long SPIN_MILLIS = (long) Math.ceil(END_AT);

    /** Times round the cylinder before the pocket. */
    static final int TURNS = 3;

    private WheelMotion() {
    }

    /**
     * How many pockets it runs past on the way to this one: whole turns,
     * then round to it. Worked out once, here, so the strip and the table's
     * sound agree on how far the ball goes.
     *
     * @param landedIndex where the winner sits on the cylinder
     * @param ringSize    how many pockets the cylinder has
     */
    public static int pocketsToPass(int landedIndex, int ringSize) {
        return TURNS * ringSize + landedIndex;
    }

    /**
     * Where the strip stands, in pockets past the marker, this long after the
     * throw. Past the winner during the overshoot and the swings; exactly on
     * it once the ball is still.
     *
     * @param millis since the result arrived
     * @param total  how many pockets the strip passes to reach the winner
     */
    public static double pocketsAt(long millis, double total) {
        if (millis <= 0) {
            return 0;
        }
        if (millis >= END_AT) {
            return total;
        }
        double rimPockets = total - ROLL_POCKETS;
        if (millis < RIM_MILLIS) {
            // Steady deceleration from the throw down to the roll's speed,
            // covering everything but the last few pockets.
            double throwSpeed = 2 * rimPockets / RIM_MILLIS - ROLL_SPEED;
            double slowing = (throwSpeed - ROLL_SPEED) / RIM_MILLIS;
            return throwSpeed * millis - 0.5 * slowing * millis * millis;
        }
        if (millis < ARRIVE_AT) {
            double t = millis - RIM_MILLIS;
            double slowing = (ROLL_SPEED - ARRIVE_SPEED) / ROLL_MILLIS;
            return rimPockets + ROLL_SPEED * t - 0.5 * slowing * t * t;
        }
        if (millis < PEAK_AT) {
            double t = millis - ARRIVE_AT;
            double slowing = ARRIVE_SPEED / OVERSHOOT_MILLIS;
            return total + ARRIVE_SPEED * t - 0.5 * slowing * t * t;
        }
        // The swings: from one turning point to the next, easing out of one
        // and into the other, so it is still at every fret it strikes.
        double from = OVERSHOOT;
        double start = PEAK_AT;
        for (int i = 0; i < SWING_TO.length; i++) {
            if (millis < start + SWING_MILLIS[i]) {
                double u = (millis - start) / SWING_MILLIS[i];
                double eased = (1 - Math.cos(Math.PI * u)) / 2;
                return total + from + (SWING_TO[i] - from) * eased;
            }
            from = SWING_TO[i];
            start += SWING_MILLIS[i];
        }
        return total;
    }

    /** Whether the ball is still moving. */
    public static boolean running(long millis) {
        return millis >= 0 && millis < SPIN_MILLIS;
    }
}

package com.github.arrivedbog593.tablegames.platform.block;

import com.github.arrivedbog593.tablegames.engine.games.slots.SlotMachine;

import java.util.Arrays;

/**
 * How a slot machine's drums move, for everything that shows it or sounds it.
 * <p>
 * One model and two readers. The screen draws the drums from it, and the
 * cabinet plays its ticks and landings from it, so the reel a player hears
 * come down is the reel they see come down. They used to be one private
 * piece of the screen, and a second copy for the sound would have drifted
 * from the first the day either was tuned.
 * <p>
 * It measures in symbols, not pixels. What a symbol is worth on screen is
 * the screen's business; the cabinet has no pixels at all.
 * <p>
 * Nothing here is authority. The result is settled on the server the
 * instant the lever is pulled and withheld until the spin is over; all this
 * decides is how the waiting looks and sounds.
 */
public final class ReelMotion {

    /**
     * How long one symbol takes to pass on each reel at full speed.
     * <p>
     * Three different rates, so the drums read as three separate things
     * rather than one picture being shuffled.
     */
    private static final long[] STEP_MILLIS = {80, 95, 110};

    /**
     * How long a reel takes to come up to speed from standing.
     * <p>
     * Long enough to watch. A quarter of a second is a cut, not a pull: the
     * drum is at its top speed before the eye has found it, and the whole
     * spin then reads as one constant blur with a stop bolted on each end.
     * At this length the rise is its own part of the animation, and a third
     * of the time the server holds the spin for.
     */
    static final long SPIN_UP_MILLIS = 700;

    /** The wait between one reel beginning to slow and the next beginning to. */
    static final long STOP_STAGGER_MILLIS = 120;

    /**
     * How many symbols a reel spends slowing down from full speed.
     * <p>
     * The knob that decides whether the stop reads as a stop. Distance, not
     * time, because time is what falls out of it: a reel sheds a fixed number
     * of symbols at constant deceleration, so the slower drums take longer to
     * do it, exactly as a heavier one would. Five is enough that the last
     * symbol alone takes about half the slowdown, which is the part the eye
     * actually reads as coasting.
     */
    static final int SETTLE_SYMBOLS = 5;

    /**
     * How long the drums keep moving after the server says the spin is over.
     * <p>
     * The worst case rather than a figure of its own, because each reel works
     * out its own slowing from how far it has to go: the last one to start is
     * also the slowest, and the furthest it can be asked to travel is one
     * symbol more than {@link #SETTLE_SYMBOLS}.
     */
    public static final long LANDING_MILLIS = STOP_STAGGER_MILLIS * (SlotMachine.REELS - 1)
            + 2L * (SETTLE_SYMBOLS + 1) * Arrays.stream(STEP_MILLIS).max().orElse(0L);

    private ReelMotion() {
    }

    /**
     * Where a reel is, what it is coming to rest on, and whether it is there.
     *
     * @param symbols  how far the drum has turned, in symbols
     * @param resultAt the place on the drum where the result's top row sits,
     *                 or {@link #NOWHERE} while the reel is still turning
     * @param resting  whether the reel has come to a stop
     */
    public record At(double symbols, int resultAt, boolean resting) {

        /** No result on this drum yet: everything on it is the client's noise. */
        public static final int NOWHERE = Integer.MIN_VALUE;
    }

    /**
     * Where one reel is, given how long ago it started and how long ago the
     * server said to stop.
     * <p>
     * Worked out from the two clocks every time rather than stepped along, so
     * nothing has to be remembered between frames and a dropped frame costs
     * nothing.
     * <p>
     * Once a reel begins to slow it is given somewhere to stop — the next
     * whole symbol {@link #SETTLE_SYMBOLS} further on — and it coasts there
     * under constant deceleration, which is the whole of why this reads as a
     * reel winding down rather than a reel being switched off. Constant
     * deceleration is the one curve that leaves the cruise at exactly the
     * speed the drum was already turning and arrives at exactly zero.
     * Anything eased more sharply has to start the slowdown faster than the
     * spin to cover the same ground in the same time, and a drum that speeds
     * up before it stops does not look like it is stopping at all. The cost
     * is that the duration stops being ours to pick: it falls out of the
     * distance and the speed, so a slower drum takes longer.
     *
     * @param reel     which reel, left to right
     * @param spinning milliseconds since the reels started; negative counts as zero
     * @param stopped  milliseconds since the server said the spin was over,
     *                 or negative while it has not
     */
    public static At of(int reel, long spinning, long stopped) {
        spinning = Math.max(0, spinning);
        long settling = stopped < 0 ? -1 : stopped - STOP_STAGGER_MILLIS * reel;
        if (settling <= 0) {
            return new At(traveled(reel, spinning), At.NOWHERE, false);
        }

        double from = traveled(reel, spinning - settling);

        // From v to nothing at a steady rate covers v·t/2, so the time this
        // reel needs is twice the distance over the speed it is leaving at.
        // The speed is read off the ramp rather than assumed to be the
        // cruise, and the distance is cut to what that speed can shed inside
        // the landing: a reel told to stop while still coming up to speed
        // would otherwise have to cover five symbols in the same time as a
        // fast one, and could only do it by speeding up before it stopped —
        // the very thing this curve exists to avoid. At full speed the cut
        // never bites; it is there for spins shorter than the ramp.
        double entry = speedAt(reel, spinning - settling);
        double budget = LANDING_MILLIS - STOP_STAGGER_MILLIS * reel;
        // The slowest reel at full speed lands on exactly the budget, so the
        // floor is taken with a hair of slack: a rounding error there would
        // quietly cost it a symbol of slowdown.
        int shed = (int) Math.max(0, Math.min(SETTLE_SYMBOLS, Math.floor(entry * budget / 2 + 1e-9) - 1));
        int restingAt = (int) Math.floor(from) + shed + 1;
        double slowing = Math.min(budget, 2.0 * (restingAt - from) / entry);
        double progress = Math.min(1.0, settling / slowing);
        double eased = 1 - (1 - progress) * (1 - progress);
        return new At(from + (restingAt - from) * eased, -restingAt, progress >= 1.0);
    }

    /** The speed a reel cruises at once it is up to it, in symbols per millisecond. */
    private static double cruiseOf(int reel) {
        return 1.0 / STEP_MILLIS[reel % STEP_MILLIS.length];
    }

    /**
     * How fast a reel is turning this many milliseconds into its pull.
     * <p>
     * A smoothstep up to the cruise rather than a straight ramp. Constant
     * acceleration reaches full speed and then stops accelerating in the same
     * instant, and that corner is visible — the drum arrives at its top speed
     * with a flick. This leaves standing gently and settles onto the cruise
     * gently, because it is flat at both ends.
     */
    static double speedAt(int reel, long millis) {
        if (millis <= 0) {
            return 0;
        }
        double cruise = cruiseOf(reel);
        if (millis >= SPIN_UP_MILLIS) {
            return cruise;
        }
        double u = millis / (double) SPIN_UP_MILLIS;
        return cruise * u * u * (3 - 2 * u);
    }

    /**
     * How far a reel has turned, in symbols, this many milliseconds in.
     * <p>
     * The integral of {@link #speedAt}. The smoothstep covers half of what
     * the cruise would have in the same time, which is what lets the second
     * line stay the plain one: past the ramp the drum is simply cruising,
     * half a ramp behind where it would be had it never had to start.
     */
    static double traveled(int reel, long millis) {
        if (millis <= 0) {
            return 0;
        }
        double cruise = cruiseOf(reel);
        if (millis < SPIN_UP_MILLIS) {
            double u = millis / (double) SPIN_UP_MILLIS;
            return cruise * SPIN_UP_MILLIS * u * u * u * (1 - u / 2.0);
        }
        return cruise * (millis - SPIN_UP_MILLIS / 2.0);
    }
}

package com.github.arrivedbog593.tablegames.engine.games.roulette;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * The set of pockets a ball can land in.
 * <p>
 * Deliberately data rather than hardcoded logic, so a server can run European
 * and American tables side by side in the same world or define its own
 * layout entirely.
 * <p>
 * Both standard wheels pay as though there were exactly 36 pockets. Every
 * extra green pocket is pure house edge: one zero gives 2.70%, two give
 * 5.26%. That is the whole difference between the two variants.
 */
public final class RouletteWheel {

    /**
     * Numbers painted red on a standard wheel. The rest of 1-36 are black.
     * This layout is fixed by convention and is the same on both variants.
     */
    private static final int[] RED_NUMBERS = {
            1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36
    };

    /**
     * The order pockets sit in around a real single-zero cylinder.
     * <p>
     * Not the numeric order. A real wheel scatters the numbers so that red
     * and black alternate all the way round and high and low are spread
     * evenly — which is why a ball drawn falling into "the next number along"
     * looks wrong to anyone who has seen a roulette table.
     */
    private static final int DOUBLE_ZERO_MARKER = -1;

    private static final int[] EUROPEAN_CYLINDER = {
            0, 32, 15, 19, 4, 21, 2, 25, 17, 34, 6, 27, 13, 36, 11, 30, 8, 23,
            10, 5, 24, 16, 33, 1, 20, 14, 31, 9, 22, 18, 29, 7, 28, 12, 35, 3, 26
    };

    /** The same for a double-zero cylinder, whose order is entirely different. */
    private static final int[] AMERICAN_CYLINDER = {
            0, 28, 9, 26, 30, 11, 7, 20, 32, 17, 5, 22, 34, 15, 3, 24, 36, 13, 1,
            DOUBLE_ZERO_MARKER,
            27, 10, 25, 29, 12, 8, 19, 31, 18, 6, 21, 33, 16, 4, 23, 35, 14, 2
    };

    /** 37 pockets, a single zero. House edge 2.70%. */
    public static final RouletteWheel EUROPEAN = new RouletteWheel("european", false);

    /** 38 pockets, zero and double zero. House edge 5.26%. */
    public static final RouletteWheel AMERICAN = new RouletteWheel("american", true);

    /**
     * Payouts are quoted as if the wheel had this many pockets. Real wheels
     * have more, and the surplus is the house edge.
     */
    private static final int FAIR_POCKET_COUNT = 36;

    private final String id;
    private final List<Pocket> pockets;
    private final List<Pocket> cylinder;

    private RouletteWheel(String id, boolean withDoubleZero) {
        this.id = Objects.requireNonNull(id, "id");
        List<Pocket> built = new ArrayList<>();
        built.add(Pocket.zero());
        if (withDoubleZero) {
            built.add(Pocket.doubleZeroPocket());
        }
        for (int number = 1; number <= 36; number++) {
            built.add(Pocket.of(number, isRed(number) ? PocketColor.RED : PocketColor.BLACK));
        }
        this.pockets = List.copyOf(built);

        List<Pocket> ring = new ArrayList<>();
        for (int number : withDoubleZero ? AMERICAN_CYLINDER : EUROPEAN_CYLINDER) {
            ring.add(number == DOUBLE_ZERO_MARKER
                    ? Pocket.doubleZeroPocket()
                    : (number == 0 ? Pocket.zero()
                            : Pocket.of(number, isRed(number)
                                    ? PocketColor.RED : PocketColor.BLACK)));
        }
        this.cylinder = List.copyOf(ring);
    }

    /**
     * The pockets in the order they sit around the physical cylinder.
     * <p>
     * Presentation only: nothing about the odds depends on it, since every
     * pocket is equally likely whatever order they are drawn in. It exists so
     * a wheel drawn on screen looks like a wheel rather than a number line
     * bent into a circle.
     */
    public List<Pocket> cylinder() {
        return cylinder;
    }

    /** Where a pocket sits around the cylinder, or -1 if it is not on it. */
    public int cylinderIndexOf(Pocket pocket) {
        return cylinder.indexOf(pocket);
    }

    private static boolean isRed(int number) {
        for (int red : RED_NUMBERS) {
            if (red == number) {
                return true;
            }
        }
        return false;
    }

    public String id() {
        return id;
    }

    public String translationKey() {
        return "tablegames.roulette.wheel." + id;
    }

    public List<Pocket> pockets() {
        return pockets;
    }

    public int pocketCount() {
        return pockets.size();
    }

    /** Spins the ball. Every pocket is equally likely. */
    public Pocket spin(RandomGenerator random) {
        Objects.requireNonNull(random, "random");
        return pockets.get(random.nextInt(pockets.size()));
    }

    /**
     * The house edge as a fraction, e.g., 0.0270 for the European wheel.
     * <p>
     * Identical for every bet type on a standard wheel, because all payouts
     * are scaled to a hypothetical 36-pocket wheel.
     */
    public double houseEdge() {
        return (double) (pocketCount() - FAIR_POCKET_COUNT) / pocketCount();
    }

    @Override
    public String toString() {
        return "RouletteWheel[" + id + ", " + pocketCount() + " pockets]";
    }

    /**
     * The most the house could owe on a set of bets, whatever the ball does.
     * <p>
     * Computed exactly, by asking every pocket what it would cost and keeping
     * the worst answer, rather than by summing each bet's own worst case. The
     * simple sum is wildly pessimistic on a busy layout — a player covering
     * both red and black cannot win both, yet a per-bet estimate charges the
     * house for both — and a limit that pessimistic would refuse ordinary
     * play long before the bankroll was ever at risk.
     * <p>
     * Losing bets count against the total because the house keeps them. A
     * round where the stakes coming in exceed anything going out costs the
     * house nothing, so it reports zero rather than a negative.
     */
    public long worstCaseHouseCost(Iterable<RouletteBet> bets) {
        Objects.requireNonNull(bets, "bets");
        long worst = 0;
        for (Pocket pocket : pockets) {
            long cost = 0;
            for (RouletteBet bet : bets) {
                cost += bet.houseCost(pocket);
            }
            worst = Math.max(worst, cost);
        }
        return worst;
    }
}

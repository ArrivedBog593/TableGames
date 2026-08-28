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
}
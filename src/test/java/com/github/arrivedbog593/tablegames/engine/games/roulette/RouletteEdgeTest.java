package com.github.arrivedbog593.tablegames.engine.games.roulette;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exhaustive expected-value checks over every pocket on the wheel.
 * <p>
 * These are exact rather than simulated: with only 37 or 38 outcomes, the
 * whole probability space fits in a loop, so there is nothing to sample and
 * no flakiness. If a payout ratio is ever wrong, the edge drifts and these
 * fail immediately.
 */
class RouletteEdgeTest {

    /** Expected return per credit staked, averaged over every pocket. */
    private static double expectedReturn(RouletteWheel wheel, BetType type, Pocket target) {
        long stake = 100;
        long total = 0;
        for (Pocket pocket : wheel.pockets()) {
            RouletteBet bet = type.requiresTarget()
                    ? RouletteBet.straightUp(target, stake)
                    : RouletteBet.outside(type, stake);
            total += bet.payout(pocket);
        }
        return (double) total / (wheel.pocketCount() * stake);
    }

    private static void assertEdgeMatches(RouletteWheel wheel, BetType type, Pocket target) {
        double edge = 1.0 - expectedReturn(wheel, type, target);
        assertEquals(wheel.houseEdge(), edge, 1e-9,
                type + " on the " + wheel.id() + " wheel");
    }

    @Test
    void everyBetTypeCarriesTheSameEdgeOnTheEuropeanWheel() {
        for (BetType type : BetType.values()) {
            assertEdgeMatches(RouletteWheel.EUROPEAN, type,
                    type.requiresTarget() ? Pocket.zero() : null);
        }
    }

    @Test
    void everyBetTypeCarriesTheSameEdgeOnTheAmericanWheel() {
        for (BetType type : BetType.values()) {
            assertEdgeMatches(RouletteWheel.AMERICAN, type,
                    type.requiresTarget() ? Pocket.zero() : null);
        }
    }

    @Test
    void straightUpOnAnyNumberCarriesTheSameEdge() {
        for (Pocket target : RouletteWheel.AMERICAN.pockets()) {
            assertEdgeMatches(RouletteWheel.AMERICAN, BetType.STRAIGHT_UP, target);
        }
    }

    @Test
    void theHouseAlwaysHasAnEdgeButNeverAHugeOne() {
        for (RouletteWheel wheel : new RouletteWheel[]{
                RouletteWheel.EUROPEAN, RouletteWheel.AMERICAN}) {
            assertTrue(wheel.houseEdge() > 0, "the house must never be at a loss");
            assertTrue(wheel.houseEdge() < 0.06, "an edge this steep would drain players");
        }
    }

    @Test
    void americanPlayersLoseRoughlyTwiceAsFast() {
        long staked = 1_000_000;
        double europeanLoss = staked * RouletteWheel.EUROPEAN.houseEdge();
        double americanLoss = staked * RouletteWheel.AMERICAN.houseEdge();

        assertEquals(27_027, europeanLoss, 1);
        assertEquals(52_632, americanLoss, 1);
    }
}
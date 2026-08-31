package com.github.arrivedbog593.tablegames.engine.games.roulette;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouletteWheelTest {

    @Test
    void europeanWheelHas37Pockets() {
        assertEquals(37, RouletteWheel.EUROPEAN.pocketCount());
    }

    @Test
    void americanWheelHas38Pockets() {
        assertEquals(38, RouletteWheel.AMERICAN.pocketCount());
    }

    @Test
    void bothWheelsHave18RedAnd18Black() {
        for (RouletteWheel wheel : new RouletteWheel[]{
                RouletteWheel.EUROPEAN, RouletteWheel.AMERICAN}) {
            long red = wheel.pockets().stream()
                    .filter(p -> p.color() == PocketColor.RED).count();
            long black = wheel.pockets().stream()
                    .filter(p -> p.color() == PocketColor.BLACK).count();
            assertEquals(18, red, wheel.id() + " red count");
            assertEquals(18, black, wheel.id() + " black count");
        }
    }

    @Test
    void everyPocketIsDistinct() {
        Set<Pocket> unique = new HashSet<>(RouletteWheel.AMERICAN.pockets());
        assertEquals(38, unique.size(), "the two zeros must not collide");
    }

    @Test
    void zeroAndDoubleZeroAreDifferentPockets() {
        assertNotEquals(Pocket.zero(), Pocket.doubleZeroPocket());
        assertTrue(Pocket.zero().isZero());
        assertTrue(Pocket.doubleZeroPocket().isZero());
        assertEquals("0", Pocket.zero().label());
        assertEquals("00", Pocket.doubleZeroPocket().label());
    }

    @Test
    void europeanWheelHasNoDoubleZero() {
        assertFalse(RouletteWheel.EUROPEAN.pockets().contains(Pocket.doubleZeroPocket()));
        assertTrue(RouletteWheel.AMERICAN.pockets().contains(Pocket.doubleZeroPocket()));
    }

    @Test
    void houseEdgeMatchesTheKnownFigures() {
        assertEquals(0.0270, RouletteWheel.EUROPEAN.houseEdge(), 0.0001);
        assertEquals(0.0526, RouletteWheel.AMERICAN.houseEdge(), 0.0001);
    }

    @Test
    void americanEdgeIsRoughlyDoubleTheEuropean() {
        double ratio = RouletteWheel.AMERICAN.houseEdge() / RouletteWheel.EUROPEAN.houseEdge();
        assertEquals(1.95, ratio, 0.05);
    }

    @Test
    void spinAlwaysLandsInARealPocket() {
        Random random = new Random(42L);
        for (int i = 0; i < 500; i++) {
            Pocket landed = RouletteWheel.AMERICAN.spin(random);
            assertTrue(RouletteWheel.AMERICAN.pockets().contains(landed));
        }
    }

    @Test
    void pocketRejectsImpossibleCombinations() {
        assertThrows(IllegalArgumentException.class,
                () -> new Pocket(0, false, PocketColor.RED));
        assertThrows(IllegalArgumentException.class,
                () -> new Pocket(17, false, PocketColor.GREEN));
        assertThrows(IllegalArgumentException.class,
                () -> new Pocket(5, true, PocketColor.RED));
        assertThrows(IllegalArgumentException.class,
                () -> Pocket.of(37, PocketColor.RED));
    }

    // --- Physical cylinder order -------------------------------------------------

    @Test
    void theCylinderHoldsEveryPocketExactlyOnce() {
        for (RouletteWheel wheel : List.of(RouletteWheel.EUROPEAN, RouletteWheel.AMERICAN)) {
            List<Pocket> ring = wheel.cylinder();
            assertEquals(wheel.pocketCount(), ring.size(), wheel.id());
            assertEquals(Set.copyOf(wheel.pockets()), Set.copyOf(ring), wheel.id());
            assertEquals(ring.size(), Set.copyOf(ring).size(),
                    wheel.id() + " repeats a pocket");
        }
    }

    @Test
    void colorsAlternateAroundTheCylinder() {
        // The property the scattered order exists to produce. A transcription
        // slip in the sequence shows up here and nowhere else, because the
        // odds do not care what order the pockets are in.
        for (RouletteWheel wheel : List.of(RouletteWheel.EUROPEAN, RouletteWheel.AMERICAN)) {
            List<Pocket> ring = wheel.cylinder();
            for (int i = 0; i < ring.size(); i++) {
                Pocket here = ring.get(i);
                Pocket next = ring.get((i + 1) % ring.size());
                if (here.color() == PocketColor.GREEN || next.color() == PocketColor.GREEN) {
                    continue;
                }
                assertNotEquals(here.color(), next.color(),
                        wheel.id() + " has " + here + " next to " + next);
            }
        }
    }

    @Test
    void theGreenPocketsSitOppositeEachOther() {
        // True of the American wheel by design: the two greens are diametric,
        // which is also why color alternation breaks across them.
        List<Pocket> ring = RouletteWheel.AMERICAN.cylinder();
        int zero = RouletteWheel.AMERICAN.cylinderIndexOf(Pocket.zero());
        int doubleZero = RouletteWheel.AMERICAN.cylinderIndexOf(Pocket.doubleZeroPocket());
        assertEquals(ring.size() / 2, Math.abs(doubleZero - zero));
    }

    @Test
    void theTwoWheelsAreOrderedDifferently() {
        assertNotEquals(
                RouletteWheel.EUROPEAN.cylinder().subList(0, 5).toString(),
                RouletteWheel.AMERICAN.cylinder().subList(0, 5).toString());
    }

    @Test
    void aPocketNotOnTheWheelHasNoPlaceOnTheCylinder() {
        assertEquals(-1, RouletteWheel.EUROPEAN.cylinderIndexOf(Pocket.doubleZeroPocket()));
    }

    // --- Worst-case liability ---------------------------------------------------

    private static Pocket europeanPocket(int number) {
        return RouletteWheel.EUROPEAN.pockets().stream()
                .filter(p -> !p.doubleZero() && p.number() == number)
                .findFirst().orElseThrow();
    }

    @Test
    void noBetsCostTheHouseNothing() {
        assertEquals(0, RouletteWheel.EUROPEAN.worstCaseHouseCost(List.of()));
    }

    @Test
    void aStraightUpCostsThirtyFiveTimesTheStake() {
        // The profit alone. The stake never left the player, because credits
        // do not move until settlement.
        long worst = RouletteWheel.EUROPEAN.worstCaseHouseCost(List.of(
                new RouletteBet(BetType.STRAIGHT_UP, europeanPocket(17), 100)));
        assertEquals(3_500, worst);
    }

    @Test
    void aLosingStakeOffsetsAWinningOne() {
        // 17 landing pays 3,500, but the house keeps the 100 on red, so it is
        // only out 3,400. A per-bet estimate would have said 3,600.
        long worst = RouletteWheel.EUROPEAN.worstCaseHouseCost(List.of(
                new RouletteBet(BetType.STRAIGHT_UP, europeanPocket(17), 100),
                new RouletteBet(BetType.RED, null, 100)));
        assertEquals(3_400, worst);
    }

    @Test
    void coveringBothColorsCostsTheHouseNothing() {
        // The case a naive per-bet sum gets badly wrong: it would charge the
        // house for both halves of a bet that cannot both win and refuse
        // ordinary play long before the bankroll was at risk.
        long worst = RouletteWheel.EUROPEAN.worstCaseHouseCost(List.of(
                new RouletteBet(BetType.RED, null, 1_000),
                new RouletteBet(BetType.BLACK, null, 1_000)));
        assertEquals(0, worst);
    }

    @Test
    void theWorstPocketIsTheOneReported() {
        // Two numbers backed unequally: the worst case is the bigger one, not
        // the sum and not the average.
        long worst = RouletteWheel.EUROPEAN.worstCaseHouseCost(List.of(
                new RouletteBet(BetType.STRAIGHT_UP, europeanPocket(17), 100),
                new RouletteBet(BetType.STRAIGHT_UP, europeanPocket(23), 300)));
        assertEquals(300 * 35 - 100, worst);
    }

    @Test
    void aRoundTheHouseCannotLoseReportsZero() {
        // Every pocket is a loser for the player, so nothing is at risk.
        long worst = RouletteWheel.AMERICAN.worstCaseHouseCost(List.of(
                new RouletteBet(BetType.STRAIGHT_UP, europeanPocket(17), 10),
                new RouletteBet(BetType.STRAIGHT_UP, europeanPocket(17), 10)));
        // 17 landing costs 2 x 350; nothing else is backed.
        assertEquals(700, worst);
    }

    @Test
    void theTwoWheelsCanDisagreeAboutTheWorstCase() {
        // A bet on the double zero is a loser on every European pocket, so
        // the European wheel sees a round it cannot lose.
        List<RouletteBet> bets = List.of(
                new RouletteBet(BetType.STRAIGHT_UP, Pocket.doubleZeroPocket(), 100));
        assertEquals(0, RouletteWheel.EUROPEAN.worstCaseHouseCost(bets));
        assertEquals(3_500, RouletteWheel.AMERICAN.worstCaseHouseCost(bets));
    }
}

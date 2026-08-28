package com.github.arrivedbog593.tablegames.engine.games.roulette;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
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
}
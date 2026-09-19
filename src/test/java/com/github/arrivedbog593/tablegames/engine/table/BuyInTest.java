package com.github.arrivedbog593.tablegames.engine.table;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BuyInTest {

    private static final BuyIn CAPPED = new BuyIn(100, 5_000);
    private static final BuyIn OPEN = new BuyIn(100, BuyIn.UNLIMITED);

    @Test
    void sittingDownWithinTheRangeIsAccepted() {
        assertEquals(Optional.empty(), CAPPED.problemWith(100, 0, 25_000));
        assertEquals(Optional.empty(), CAPPED.problemWith(5_000, 0, 25_000));
    }

    @Test
    void sittingDownBelowTheMinimumIsRefused() {
        assertEquals(Optional.of(BuyIn.Problem.BELOW_MINIMUM), CAPPED.problemWith(99, 0, 25_000));
    }

    @Test
    void aTopUpHasNoMinimumOfItsOwn() {
        // Whoever is already sitting cleared the minimum on the way in.
        assertEquals(Optional.empty(), CAPPED.problemWith(1, 400, 25_000));
    }

    @Test
    void theMaximumCountsTheWholeStackNotTheTopUp() {
        assertEquals(Optional.empty(), CAPPED.problemWith(1_000, 4_000, 25_000));
        assertEquals(Optional.of(BuyIn.Problem.ABOVE_MAXIMUM),
                CAPPED.problemWith(1_001, 4_000, 25_000));
    }

    @Test
    void nobodyReservesWhatTheyDoNotHave() {
        assertEquals(Optional.of(BuyIn.Problem.INSUFFICIENT), OPEN.problemWith(3_000, 0, 2_999));
    }

    @Test
    void nothingAndLessThanNothingAreRefused() {
        assertEquals(Optional.of(BuyIn.Problem.NOT_POSITIVE), OPEN.problemWith(0, 500, 1_000));
        assertEquals(Optional.of(BuyIn.Problem.NOT_POSITIVE), OPEN.problemWith(-5, 500, 1_000));
    }

    @Test
    void anUncappedTableTakesAsMuchAsThePlayerCanCover() {
        assertEquals(Optional.empty(), OPEN.problemWith(1_000_000, 0, 1_000_000));
        assertEquals(1_000_000, OPEN.largestAddition(0, 1_000_000));
    }

    @Test
    void theLargestAdditionIsTheTighterOfRoomAndFunds() {
        assertEquals(1_000, CAPPED.largestAddition(4_000, 25_000), "room left under the cap");
        assertEquals(300, CAPPED.largestAddition(0, 300), "what the player has");
        assertEquals(0, CAPPED.largestAddition(5_000, 25_000), "a full stack takes nothing");
    }

    @Test
    void anInvertedRangeCannotBeBuilt() {
        assertThrows(IllegalArgumentException.class, () -> new BuyIn(500, 100));
        assertThrows(IllegalArgumentException.class, () -> new BuyIn(0, 100));
    }
}

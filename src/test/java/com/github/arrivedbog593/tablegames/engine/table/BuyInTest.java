package com.github.arrivedbog593.tablegames.engine.table;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BuyInTest {

    private static final BuyIn CAPPED = new BuyIn(100, 5_000);
    private static final BuyIn OPEN = new BuyIn(100, BuyIn.UNLIMITED);

    /** Sitting down: the minimum applies. */
    private static Optional<BuyIn.Problem> joining(BuyIn buyIn, long amount, long available) {
        return buyIn.problemWith(amount, 0, available, true);
    }

    /** Already in the seat, whatever is left of the stack. */
    private static Optional<BuyIn.Problem> toppingUp(BuyIn buyIn, long amount, long stack,
                                                     long available) {
        return buyIn.problemWith(amount, stack, available, false);
    }

    @Test
    void sittingDownWithinTheRangeIsAccepted() {
        assertEquals(Optional.empty(), joining(CAPPED, 100, 25_000));
        assertEquals(Optional.empty(), joining(CAPPED, 5_000, 25_000));
    }

    @Test
    void sittingDownBelowTheMinimumIsRefused() {
        assertEquals(Optional.of(BuyIn.Problem.BELOW_MINIMUM), joining(CAPPED, 99, 25_000));
    }

    @Test
    void aTopUpHasNoMinimumOfItsOwn() {
        // Whoever is already sitting cleared the minimum on the way in.
        assertEquals(Optional.empty(), toppingUp(CAPPED, 1, 400, 25_000));
    }

    @Test
    void aPlayerWhoLostEverythingIsStillSitting() {
        // An empty stack used to be read as an empty seat, so somebody who
        // had just been cleaned out was told to buy in from scratch to put
        // one more chip in. They never got up.
        assertEquals(Optional.empty(), toppingUp(CAPPED, 1, 0, 25_000));
        assertEquals(Optional.of(BuyIn.Problem.BELOW_MINIMUM), joining(CAPPED, 1, 25_000));
    }

    @Test
    void theMaximumCountsTheWholeStackNotTheTopUp() {
        assertEquals(Optional.empty(), toppingUp(CAPPED, 1_000, 4_000, 25_000));
        assertEquals(Optional.of(BuyIn.Problem.ABOVE_MAXIMUM),
                toppingUp(CAPPED, 1_001, 4_000, 25_000));
    }

    @Test
    void nobodyReservesWhatTheyDoNotHave() {
        assertEquals(Optional.of(BuyIn.Problem.INSUFFICIENT), joining(OPEN, 3_000, 2_999));
        assertEquals(Optional.of(BuyIn.Problem.INSUFFICIENT), toppingUp(OPEN, 3_000, 500, 2_999));
    }

    @Test
    void nothingAndLessThanNothingAreRefused() {
        assertEquals(Optional.of(BuyIn.Problem.NOT_POSITIVE), toppingUp(OPEN, 0, 500, 1_000));
        assertEquals(Optional.of(BuyIn.Problem.NOT_POSITIVE), toppingUp(OPEN, -5, 500, 1_000));
    }

    @Test
    void anUncappedTableTakesAsMuchAsThePlayerCanCover() {
        assertEquals(Optional.empty(), joining(OPEN, 1_000_000, 1_000_000));
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

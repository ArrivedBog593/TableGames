package com.github.arrivedbog593.tablegames.engine.games.roulette;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouletteLayoutTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    private static final Pocket SEVENTEEN = Pocket.of(17, PocketColor.BLACK);
    private static final Pocket TWENTY_THREE = Pocket.of(23, PocketColor.RED);

    @Test
    void aFreshLayoutIsEmpty() {
        RouletteLayout layout = new RouletteLayout();
        assertTrue(layout.isEmpty());
        assertTrue(layout.betsOf(ALICE).isEmpty());
        assertEquals(0, layout.wageredBy(ALICE));
        assertTrue(layout.allBets().isEmpty());
        assertTrue(layout.players().isEmpty());
    }

    @Test
    void placingTracksItUnderThePlayer() {
        RouletteLayout layout = new RouletteLayout();
        RouletteBet bet = RouletteBet.straightUp(SEVENTEEN, 100);
        layout.place(ALICE, bet);

        assertFalse(layout.isEmpty());
        assertEquals(List.of(bet), layout.betsOf(ALICE));
        assertEquals(100, layout.wageredBy(ALICE));
        assertEquals(List.of(ALICE), layout.players());
        assertTrue(layout.betsOf(BOB).isEmpty());
    }

    @Test
    void aPlayerCanHoldSeveralBetsAtOnce() {
        RouletteLayout layout = new RouletteLayout();
        layout.place(ALICE, RouletteBet.straightUp(SEVENTEEN, 100));
        layout.place(ALICE, RouletteBet.outside(BetType.RED, 50));

        assertEquals(150, layout.wageredBy(ALICE));
        assertEquals(2, layout.betsOf(ALICE).size());
    }

    @Test
    void stakedOnCountsOnlyTheSamePosition() {
        // Two chips on red are one stake; a chip on red and one on 17 are two.
        RouletteLayout layout = new RouletteLayout();
        layout.place(ALICE, RouletteBet.outside(BetType.RED, 50));
        layout.place(ALICE, RouletteBet.outside(BetType.RED, 25));
        layout.place(ALICE, RouletteBet.straightUp(SEVENTEEN, 100));

        assertEquals(75, layout.stakedOn(ALICE, RouletteBet.outside(BetType.RED, 1)));
        assertEquals(100, layout.stakedOn(ALICE, RouletteBet.straightUp(SEVENTEEN, 1)));
        assertEquals(0, layout.stakedOn(ALICE, RouletteBet.straightUp(TWENTY_THREE, 1)));
    }

    @Test
    void stakedOnWithNoPlayerCountsTheWholeTable() {
        RouletteLayout layout = new RouletteLayout();
        layout.place(ALICE, RouletteBet.outside(BetType.RED, 50));
        layout.place(BOB, RouletteBet.outside(BetType.RED, 30));
        layout.place(BOB, RouletteBet.straightUp(SEVENTEEN, 10));

        assertEquals(80, layout.stakedOn(null, RouletteBet.outside(BetType.RED, 1)));
        assertEquals(10, layout.stakedOn(null, RouletteBet.straightUp(SEVENTEEN, 1)));
    }

    @Test
    void allBetsCollectsEveryoneOnTheLayout() {
        RouletteLayout layout = new RouletteLayout();
        RouletteBet aliceBet = RouletteBet.outside(BetType.RED, 50);
        RouletteBet bobBet = RouletteBet.straightUp(SEVENTEEN, 10);
        layout.place(ALICE, aliceBet);
        layout.place(BOB, bobBet);

        assertEquals(2, layout.allBets().size());
        assertTrue(layout.allBets().containsAll(List.of(aliceBet, bobBet)));
    }

    @Test
    void playersAreListedInTheOrderTheyFirstBet() {
        RouletteLayout layout = new RouletteLayout();
        layout.place(BOB, RouletteBet.outside(BetType.RED, 10));
        layout.place(ALICE, RouletteBet.outside(BetType.BLACK, 10));
        layout.place(BOB, RouletteBet.outside(BetType.ODD, 10));

        assertEquals(List.of(BOB, ALICE), layout.players());
    }

    @Test
    void clearingRemovesOnlyThatPlayer() {
        RouletteLayout layout = new RouletteLayout();
        layout.place(ALICE, RouletteBet.outside(BetType.RED, 50));
        layout.place(BOB, RouletteBet.outside(BetType.RED, 30));

        assertTrue(layout.clear(ALICE));
        assertTrue(layout.betsOf(ALICE).isEmpty());
        assertEquals(List.of(BOB), layout.players());
    }

    @Test
    void clearingSomebodyWithNothingDownReportsIt() {
        RouletteLayout layout = new RouletteLayout();
        assertFalse(layout.clear(ALICE));
    }

    @Test
    void clearAllDropsEveryone() {
        RouletteLayout layout = new RouletteLayout();
        layout.place(ALICE, RouletteBet.outside(BetType.RED, 50));
        layout.place(BOB, RouletteBet.outside(BetType.RED, 30));

        layout.clearAll();

        assertTrue(layout.isEmpty());
        assertTrue(layout.allBets().isEmpty());
    }

    @Test
    void betsOfIsNotBackedByTheLayoutsOwnList() {
        RouletteLayout layout = new RouletteLayout();
        layout.place(ALICE, RouletteBet.outside(BetType.RED, 50));
        List<RouletteBet> snapshot = layout.betsOf(ALICE);

        layout.place(ALICE, RouletteBet.outside(BetType.BLACK, 10));

        assertEquals(1, snapshot.size(), "a returned snapshot must not see later changes");
    }
}

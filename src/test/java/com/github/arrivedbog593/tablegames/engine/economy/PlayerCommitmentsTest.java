package com.github.arrivedbog593.tablegames.engine.economy;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerCommitmentsTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());
    private static final String TABLE = "0,64,0";
    private static final String OTHER_TABLE = "8,64,0";

    @Test
    void anEmptyRegistryCommitsNothing() {
        PlayerCommitments commitments = new PlayerCommitments();

        assertEquals(0, commitments.committedBy(ALICE));
        assertEquals(1_000, commitments.spendable(ALICE, 1_000));
        assertEquals(0, commitments.playerCount());
    }

    @Test
    void whatIsOnTheLayoutCannotBeSpentElsewhere() {
        // The hole this exists for: bet everything, wait for the lock, buy an
        // item with the same credits, and the spin drops a wager that was
        // neither won nor lost.
        PlayerCommitments commitments = new PlayerCommitments();
        commitments.commit(ALICE, TABLE, 1_000);

        assertEquals(0, commitments.spendable(ALICE, 1_000));
        assertFalse(commitments.canSpend(ALICE, 1_000, 1));
    }

    @Test
    void aCommitmentIsATotalAndNotAnIncrement() {
        // A table reports what is on its felt, and says so on every change.
        // Adding would count the first chip again with everyone after it.
        PlayerCommitments commitments = new PlayerCommitments();
        commitments.commit(ALICE, TABLE, 100);
        commitments.commit(ALICE, TABLE, 250);

        assertEquals(250, commitments.committedBy(ALICE));
    }

    @Test
    void twoTablesCannotPromiseTheSameCredits() {
        PlayerCommitments commitments = new PlayerCommitments();
        commitments.commit(ALICE, TABLE, 600);
        commitments.commit(ALICE, OTHER_TABLE, 400);

        assertEquals(1_000, commitments.committedBy(ALICE));
        assertEquals(600, commitments.committedBy(ALICE, TABLE));
        assertEquals(0, commitments.spendable(ALICE, 1_000));
    }

    @Test
    void oneTableReleasingLeavesTheOtherAlone() {
        PlayerCommitments commitments = new PlayerCommitments();
        commitments.commit(ALICE, TABLE, 600);
        commitments.commit(ALICE, OTHER_TABLE, 400);

        commitments.release(ALICE, TABLE);

        assertEquals(400, commitments.committedBy(ALICE));
    }

    @Test
    void aRoundEndingFreesEverybodyAtThatTable() {
        PlayerCommitments commitments = new PlayerCommitments();
        commitments.commit(ALICE, TABLE, 600);
        commitments.commit(BOB, TABLE, 300);
        commitments.commit(BOB, OTHER_TABLE, 50);

        commitments.release(TABLE);

        assertEquals(0, commitments.committedBy(ALICE));
        assertEquals(50, commitments.committedBy(BOB));
        assertEquals(1, commitments.playerCount());
    }

    @Test
    void committingNothingIsHowAWagerIsTakenBack() {
        PlayerCommitments commitments = new PlayerCommitments();
        commitments.commit(ALICE, TABLE, 600);

        commitments.commit(ALICE, TABLE, 0);

        assertEquals(0, commitments.committedBy(ALICE));
        assertEquals(0, commitments.playerCount());
    }

    @Test
    void releasingSomethingThatWasNeverCommittedIsHarmless() {
        // Every path that ends a round has to release, so releasing has to be
        // safe to call twice and safe to call first.
        PlayerCommitments commitments = new PlayerCommitments();

        commitments.release(TABLE);
        commitments.release(ALICE, TABLE);

        assertEquals(0, commitments.playerCount());
    }

    @Test
    void spendableNeverGoesNegative() {
        // An operator can set a balance below what is riding on a table, and
        // a caller asking what is left deserves nothing rather than a
        // negative allowance it has to remember to clamp.
        PlayerCommitments commitments = new PlayerCommitments();
        commitments.commit(ALICE, TABLE, 1_000);

        assertEquals(0, commitments.spendable(ALICE, 200));
        assertFalse(commitments.canSpend(ALICE, 200, 1));
    }

    @Test
    void whatIsLeftOverIsStillSpendable() {
        PlayerCommitments commitments = new PlayerCommitments();
        commitments.commit(ALICE, TABLE, 400);

        assertEquals(600, commitments.spendable(ALICE, 1_000));
        assertTrue(commitments.canSpend(ALICE, 1_000, 600));
        assertFalse(commitments.canSpend(ALICE, 1_000, 601));
    }

    @Test
    void oneSeatDoesNotSpeakForAnother() {
        PlayerCommitments commitments = new PlayerCommitments();
        commitments.commit(ALICE, TABLE, 400);

        assertEquals(0, commitments.committedBy(BOB));
        assertEquals(1_000, commitments.spendable(BOB, 1_000));
    }

    @Test
    void aTableIgnoresItsOwnChipsWhenJudgingThem() {
        // A table replaces its own figure rather than adding to it, so a
        // player raising a wager must not be blocked by the wager they are
        // raising.
        PlayerCommitments commitments = new PlayerCommitments();
        commitments.commit(ALICE, TABLE, 600);
        commitments.commit(ALICE, OTHER_TABLE, 300);

        assertEquals(300, commitments.committedElsewhere(ALICE, TABLE));
        assertEquals(600, commitments.committedElsewhere(ALICE, OTHER_TABLE));
        assertEquals(900, commitments.committedElsewhere(ALICE, "a table with nothing on it"));
    }

    @Test
    void aNegativeCommitmentIsARefusal() {
        PlayerCommitments commitments = new PlayerCommitments();

        assertThrows(IllegalArgumentException.class,
                () -> commitments.commit(ALICE, TABLE, -1));
    }

    @Test
    void clearingIsWhatAStoppingServerDoes() {
        PlayerCommitments commitments = new PlayerCommitments();
        commitments.commit(ALICE, TABLE, 400);
        commitments.commit(BOB, OTHER_TABLE, 400);

        commitments.clear();

        assertEquals(0, commitments.playerCount());
        assertEquals(0, commitments.committedBy(ALICE));
    }
}

package com.github.arrivedbog593.tablegames.engine.table;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableStacksTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    @Test
    void aTopUpAddsToWhatIsThere() {
        TableStacks stacks = new TableStacks();
        stacks.add(ALICE, 5_000);
        stacks.add(ALICE, 1_000);
        assertEquals(6_000, stacks.stackOf(ALICE));
    }

    @Test
    void roundsMoveTheStackBothWays() {
        TableStacks stacks = new TableStacks();
        stacks.add(ALICE, 5_000);
        stacks.settle(ALICE, 5_000);
        assertEquals(10_000, stacks.stackOf(ALICE), "a win grows the stack");
        stacks.settle(ALICE, -10_000);
        assertEquals(0, stacks.stackOf(ALICE), "losing it all leaves nothing");
    }

    @Test
    void anEmptyStackKeepsItsPlace() {
        // A player at zero stays seated and may top up.
        TableStacks stacks = new TableStacks();
        stacks.add(ALICE, 100);
        stacks.settle(ALICE, -100);
        assertTrue(stacks.holds(ALICE));
    }

    @Test
    void aStackNeverGoesNegative() {
        TableStacks stacks = new TableStacks();
        stacks.add(ALICE, 100);
        stacks.settle(ALICE, -500);
        assertEquals(0, stacks.stackOf(ALICE));
    }

    @Test
    void settlingSomebodyWithoutAStackChangesNothing() {
        TableStacks stacks = new TableStacks();
        stacks.settle(BOB, 500);
        assertFalse(stacks.holds(BOB));
        assertEquals(0, stacks.stackOf(BOB));
    }

    @Test
    void standingUpForgetsTheStack() {
        TableStacks stacks = new TableStacks();
        stacks.add(ALICE, 100);
        stacks.add(BOB, 200);
        stacks.remove(ALICE);
        assertEquals(List.of(BOB), stacks.players());
    }

    @Test
    void nothingIsNotABuyIn() {
        TableStacks stacks = new TableStacks();
        assertThrows(IllegalArgumentException.class, () -> stacks.add(ALICE, 0));
    }
}

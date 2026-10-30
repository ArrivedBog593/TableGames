package com.github.arrivedbog593.tablegames.economy.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionTypeTest {

    @Test
    void aRegisteredTypeIsFoundByItsCode() {
        TransactionType tip = TransactionType.register("test_tip", true);
        assertSame(tip, TransactionType.fromCode("test_tip"));
        assertTrue(tip.isRecoverable());
        assertTrue(TransactionType.values().contains(tip));
    }

    @Test
    void registeringTheSameTypeTwiceGivesTheSameOne() {
        // Two classes declaring the same movement, or one loaded twice, must
        // not end up with two types that compare unequal.
        TransactionType first = TransactionType.register("test_twice", false);
        assertSame(first, TransactionType.register("test_twice", false));
    }

    @Test
    void theSameCodeCannotBeBothRecoverableAndNot() {
        // Otherwise which one won would depend on class loading order, and
        // that would decide whether a crash duplicates credits.
        TransactionType.register("test_conflict", true);
        assertThrows(IllegalStateException.class,
                () -> TransactionType.register("test_conflict", false));
    }

    @Test
    void codesAreWhatTheLogCanCarry() {
        // The log is tab separated and parsed back by code; anything but
        // lowercase snake case is a line recovery might not read.
        assertThrows(IllegalArgumentException.class, () -> TransactionType.register("Has Space", true));
        assertThrows(IllegalArgumentException.class, () -> TransactionType.register("tab\there", true));
        assertThrows(IllegalArgumentException.class, () -> TransactionType.register("", true));
    }

    @Test
    void theEconomyKeepsItsOwnCodes() {
        // These are written in every world's log. Renaming one would make the
        // lines already written unreadable to recovery.
        assertEquals("admin_give", TransactionType.ADMIN_GIVE.code());
        assertEquals("convert_in", TransactionType.CONVERT_IN.code());
        assertEquals("spread", TransactionType.SPREAD.code());
        assertFalse(TransactionType.SHOP_PURCHASE.isRecoverable());
    }
}

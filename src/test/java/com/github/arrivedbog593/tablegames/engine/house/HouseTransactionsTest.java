package com.github.arrivedbog593.tablegames.engine.house;

import com.github.arrivedbog593.tablegames.economy.engine.TransactionType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HouseTransactionsTest {

    @Test
    void nothingAtATableTouchesItems() {
        // So all of it may be replayed after a crash. A game that ever moved
        // an item would need a type of its own that is not.
        for (TransactionType type : List.of(HouseTransactions.BET, HouseTransactions.PAYOUT,
                HouseTransactions.RAKE, HouseTransactions.HOUSE_PAYOUT)) {
            assertTrue(type.isRecoverable(), type + " must be recoverable");
        }
    }

    @Test
    void theCodesAreTheOnesLogsAlreadyHold() {
        // Written before the economy was split out, when these were part of
        // its own enum. A different spelling and those lines stop recovering.
        assertEquals("bet", HouseTransactions.BET.code());
        assertEquals("payout", HouseTransactions.PAYOUT.code());
        assertEquals("rake", HouseTransactions.RAKE.code());
        assertEquals("house_payout", HouseTransactions.HOUSE_PAYOUT.code());
        assertSame(HouseTransactions.BET, TransactionType.fromCode("bet"));
    }
}

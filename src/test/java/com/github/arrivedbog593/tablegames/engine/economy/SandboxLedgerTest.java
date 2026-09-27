package com.github.arrivedbog593.tablegames.engine.economy;

import com.github.arrivedbog593.tablegames.engine.session.Outcome;
import com.github.arrivedbog593.tablegames.engine.session.Payout;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SandboxLedgerTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    /** The real house's rules; only the balance is the ledger's. */
    private static final HouseBankroll POLICY = HouseBankroll.of(1);

    private static Outcome paying(UUID player, long delta) {
        return new Outcome(List.of(new Payout(player, delta)), List.of(), 0, "test");
    }

    @Test
    void everyPlayerStartsAtTheSameBalance() {
        SandboxLedger ledger = new SandboxLedger(10_000_000, 50_000);
        assertEquals(50_000, ledger.balanceOf(ALICE));
        assertEquals(50_000, ledger.balanceOf(BOB));
    }

    /** A player's win is the pretend house's loss, and nobody else's. */
    @Test
    void aWinMovesCreditsFromThePretendHouseToThePlayer() {
        SandboxLedger ledger = new SandboxLedger(10_000_000, 50_000);
        SettlementAudit.Verdict verdict = ledger.settle(paying(ALICE, 3_500), true, POLICY);

        assertTrue(verdict.approved());
        assertEquals(53_500, ledger.balanceOf(ALICE));
        assertEquals(10_000_000 - 3_500, ledger.bank());
        assertEquals(50_000, ledger.balanceOf(BOB));
    }

    @Test
    void aLossMovesCreditsTheOtherWay() {
        SandboxLedger ledger = new SandboxLedger(10_000_000, 50_000);
        ledger.settle(paying(ALICE, -100), true, POLICY);
        assertEquals(49_900, ledger.balanceOf(ALICE));
        assertEquals(10_000_100, ledger.bank());
    }

    /** The limits come from the pretend bank under the real rules. */
    @Test
    void theBankrollIsThePolicyOverThePretendBank() {
        HouseBankroll policy = new HouseBankroll(123, 10, 5_000);
        SandboxLedger ledger = new SandboxLedger(2_000_000, 0);
        HouseBankroll seen = ledger.bankroll(policy);
        assertEquals(2_000_000, seen.balance());
        assertEquals(10, seen.exposurePercent());
        assertEquals(5_000, seen.minimumReserve());
    }

    /** A pretend house that cannot pay refuses the hand, and nothing moves. */
    @Test
    void aHouseThatCannotCoverRefusesAndMovesNothing() {
        SandboxLedger ledger = new SandboxLedger(1_000, 50_000);
        SettlementAudit.Verdict verdict = ledger.settle(paying(ALICE, 5_000), true, POLICY);

        assertFalse(verdict.approved());
        assertEquals(50_000, ledger.balanceOf(ALICE));
        assertEquals(1_000, ledger.bank());
    }

    @Test
    void aPlayerWhoCannotCoverRefusesAndMovesNothing() {
        SandboxLedger ledger = new SandboxLedger(10_000_000, 100);
        SettlementAudit.Verdict verdict = ledger.settle(paying(ALICE, -500), true, POLICY);

        assertFalse(verdict.approved());
        assertEquals(100, ledger.balanceOf(ALICE));
        assertEquals(10_000_000, ledger.bank());
    }

    @Test
    void refusesFiguresOutsideWhatAnAccountCanHold() {
        assertThrows(IllegalArgumentException.class, () -> new SandboxLedger(-1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new SandboxLedger(0, CreditAccount.MAX_BALANCE + 1));
    }
}

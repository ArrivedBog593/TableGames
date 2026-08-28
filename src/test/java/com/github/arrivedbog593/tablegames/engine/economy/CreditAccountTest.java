package com.github.arrivedbog593.tablegames.engine.economy;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreditAccountTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    @Test
    void depositAndWithdrawMoveTheBalance() {
        CreditAccount account = CreditAccount.empty(ALICE);
        assertTrue(account.deposit(500));
        assertEquals(500, account.balance());
        assertTrue(account.withdraw(200));
        assertEquals(300, account.balance());
    }

    @Test
    void overdraftIsRefusedAndChangesNothing() {
        CreditAccount account = new CreditAccount(ALICE, 100);
        assertFalse(account.withdraw(101));
        assertEquals(100, account.balance(), "a failed withdrawal must not move credits");
    }

    @Test
    void balanceCanNeverGoNegative() {
        CreditAccount account = CreditAccount.empty(ALICE);
        assertFalse(account.withdraw(1));
        assertEquals(0, account.balance());
    }

    @Test
    void negativeAmountsAreProgrammerErrors() {
        CreditAccount account = new CreditAccount(ALICE, 100);
        assertThrows(IllegalArgumentException.class, () -> account.deposit(-1));
        assertThrows(IllegalArgumentException.class, () -> account.withdraw(-1));
        assertThrows(IllegalArgumentException.class, () -> new CreditAccount(ALICE, -1));
    }

    @Test
    void depositsCannotBreachTheCap() {
        CreditAccount account = new CreditAccount(ALICE, CreditAccount.MAX_BALANCE - 10);
        assertFalse(account.deposit(11), "would overflow the cap");
        assertEquals(CreditAccount.MAX_BALANCE - 10, account.balance());
        assertTrue(account.deposit(10));
        assertEquals(CreditAccount.MAX_BALANCE, account.balance());
    }

    @Test
    void theCapProtectsAgainstLongOverflow() {
        CreditAccount account = new CreditAccount(ALICE, CreditAccount.MAX_BALANCE);
        assertFalse(account.deposit(Long.MAX_VALUE));
        assertEquals(CreditAccount.MAX_BALANCE, account.balance(),
                "an overflow must never wrap into a negative balance");
    }

    @Test
    void transferMovesCreditsBetweenAccounts() {
        CreditAccount from = new CreditAccount(ALICE, 1000);
        CreditAccount to = new CreditAccount(BOB, 0);

        assertTrue(CreditAccount.transfer(from, to, 400));
        assertEquals(600, from.balance());
        assertEquals(400, to.balance());
    }

    @Test
    void failedTransferLeavesBothSidesUntouched() {
        CreditAccount from = new CreditAccount(ALICE, 100);
        CreditAccount to = new CreditAccount(BOB, 50);

        assertFalse(CreditAccount.transfer(from, to, 500));
        assertEquals(100, from.balance());
        assertEquals(50, to.balance());
    }

    @Test
    void transferRollsBackIfTheDestinationCannotAccept() {
        CreditAccount from = new CreditAccount(ALICE, 1000);
        CreditAccount to = new CreditAccount(BOB, CreditAccount.MAX_BALANCE);

        assertFalse(CreditAccount.transfer(from, to, 1000));
        assertEquals(1000, from.balance(), "credits must not vanish in transit");
        assertEquals(CreditAccount.MAX_BALANCE, to.balance());
    }

    @Test
    void transferIsConservative() {
        CreditAccount from = new CreditAccount(ALICE, 1000);
        CreditAccount to = new CreditAccount(BOB, 250);
        long before = from.balance() + to.balance();

        CreditAccount.transfer(from, to, 300);
        CreditAccount.transfer(to, from, 900);
        CreditAccount.transfer(from, to, 50);

        assertEquals(before, from.balance() + to.balance(),
                "no sequence of transfers may change the total supply");
    }
}
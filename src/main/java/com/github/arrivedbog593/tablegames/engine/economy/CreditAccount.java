package com.github.arrivedbog593.tablegames.engine.economy;

import java.util.Objects;
import java.util.UUID;

/**
 * A player's credit balance.
 * <p>
 * Every mutation is checked and reported rather than assumed. Withdrawing
 * more than the balance returns false instead of going negative: a negative
 * balance is how duplication bugs turn into an economic collapse, so it is
 * made unrepresentable.
 * <p>
 * Not thread-safe. All credit movements must happen on the server thread.
 * Two threads touching one account are exactly the race that mints money.
 */
public final class CreditAccount {

    /**
     * Hard ceiling on a balance.
     * <p>
     * Well below {@link Long#MAX_VALUE} on purpose: if arithmetic ever
     * overflows, a balance silently wraps to a huge negative number and the
     * damage is invisible until someone notices. A cap this far from the
     * limit means any overflow attempt is caught as a rejected deposit
     * instead.
     */
    public static final long MAX_BALANCE = 1_000_000_000_000L;

    private final UUID owner;
    private long balance;

    public CreditAccount(UUID owner, long initialBalance) {
        this.owner = Objects.requireNonNull(owner, "owner");
        if (initialBalance < 0) {
            throw new IllegalArgumentException("Negative initial balance: " + initialBalance);
        }
        if (initialBalance > MAX_BALANCE) {
            throw new IllegalArgumentException("Initial balance over cap: " + initialBalance);
        }
        this.balance = initialBalance;
    }

    public static CreditAccount empty(UUID owner) {
        return new CreditAccount(owner, 0);
    }

    public UUID owner() {
        return owner;
    }

    public long balance() {
        return balance;
    }

    public boolean canAfford(long amount) {
        return amount >= 0 && balance >= amount;
    }

    /**
     * Adds credits.
     *
     * @return false if the deposit would breach {@link #MAX_BALANCE}, in
     *         which case nothing is changed
     * @throws IllegalArgumentException if the amount is negative
     */
    public boolean deposit(long amount) {
        requireNonNegative(amount);
        if (amount > MAX_BALANCE - balance) {
            return false;
        }
        balance += amount;
        return true;
    }

    /**
     * Removes credits.
     *
     * @return false if the balance is too low, in which case nothing is
     *         changed
     * @throws IllegalArgumentException if the amount is negative
     */
    public boolean withdraw(long amount) {
        requireNonNegative(amount);
        if (balance < amount) {
            return false;
        }
        balance -= amount;
        return true;
    }

    /**
     * Moves credits between accounts, all or nothing.
     * <p>
     * If the deposit side fails, the withdrawal is rolled back, so credits can
     * never be destroyed in transit.
     *
     * @return false if the transfer did not happen
     */
    public static boolean transfer(CreditAccount from, CreditAccount to, long amount) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from == to) {
            return amount >= 0;
        }
        if (!from.withdraw(amount)) {
            return false;
        }
        if (!to.deposit(amount)) {
            from.deposit(amount);
            return false;
        }
        return true;
    }

    private static void requireNonNegative(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Negative amount: " + amount);
        }
    }

    @Override
    public String toString() {
        return "CreditAccount[" + owner + ", " + balance + "cr]";
    }
}
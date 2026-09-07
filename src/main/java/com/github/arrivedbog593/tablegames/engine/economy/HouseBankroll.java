package com.github.arrivedbog593.tablegames.engine.economy;

/**
 * The casino's funds, and what they allow it to risk.
 * <p>
 * A house-banked game mints credits whenever it loses, so the only thing
 * standing between a lucky night and an economy full of invented money is
 * this: the house never accepts a wager it could not pay out.
 * <p>
 * The table maximum is derived from the balance rather than configured
 * separately, because a fixed maximum is wrong the moment the balance moves.
 * With a million in the bank and a ten-thousand maximum, one straight-up win
 * at 35 to 1 takes 360,000 — over a third of the casino — and that is not a
 * risk, it is a certainty waiting its turn. Deriving the limit means a
 * casino that is losing quietly tightens its own bets until it recovers, with
 * nobody having to notice.
 *
 * @param balance         credits the households
 * @param exposurePercent most of the bankroll a single payout may claim
 * @param minimumReserve  below this, house-banked games close entirely. The
 *                        operator sets a floor and
 *                        {@link #withReserveAtLeast} raises it to whatever
 *                        the tables have already promised
 */
public record HouseBankroll(long balance, int exposurePercent, long minimumReserve) {

    /**
     * Five percent. Low enough that a dozen bad beats in a row cannot break
     * the casino, high enough that the table maximum is not insulting.
     */
    public static final int DEFAULT_EXPOSURE_PERCENT = 5;

    /**
     * Under this, the bankroll is too thin for the derived limits to mean
     * anything and house games simply close. Player-versus-player games are
     * unaffected, since the house risks nothing in those.
     * <p>
     * A floor and not the whole answer. Ten thousand is a sensible number for
     * a casino that opened last week and a meaningless one beside a bank of a
     * hundred million, which could lose all but a ten-thousandth of itself
     * with every table still open and no warning printed anywhere. What the
     * reserve should actually protect is what the house has already promised,
     * which changes minute by minute: see {@link #withReserveAtLeast}.
     */
    public static final long DEFAULT_MINIMUM_RESERVE = 10_000;

    /** How healthy the bankroll is. */
    public enum Status {
        /** Comfortable. Everything open. */
        HEALTHY,
        /** Open, but thin enough that the operator should be told. */
        LOW,
        /** Below the reserve. House-banked games are closed. */
        CLOSED
    }

    public HouseBankroll {
        if (balance < 0) {
            throw new IllegalArgumentException("Negative house balance: " + balance);
        }
        if (exposurePercent < 1 || exposurePercent > 100) {
            throw new IllegalArgumentException(
                    "Exposure percent must be 1..100, was " + exposurePercent);
        }
        if (minimumReserve < 0) {
            throw new IllegalArgumentException("Negative reserve: " + minimumReserve);
        }
    }

    /** A bankroll with the standard limits. */
    public static HouseBankroll of(long balance) {
        return new HouseBankroll(balance, DEFAULT_EXPOSURE_PERCENT, DEFAULT_MINIMUM_RESERVE);
    }

    /** The most a single payout may take from the bankroll. */
    public long maximumExposure() {
        return balance / 100 * exposurePercent;
    }

    /**
     * The largest wager allowed at a game whose best payout is
     * {@code payoutRatio} to one.
     * <p>
     * A winning bet returns the stake plus the profit, so the house is on the
     * hook for {@code stake * (ratio + 1)}. Roulette's straight-up pays 35 to
     * 1, meaning a bet of 100 costs the house 3,600 when it lands.
     *
     * @return zero when the bankroll cannot safely support any bet at all
     */
    public long maximumBet(int payoutRatio) {
        if (payoutRatio < 0) {
            throw new IllegalArgumentException("Negative payout ratio: " + payoutRatio);
        }
        if (!isOpen()) {
            return 0;
        }
        return maximumExposure() / (payoutRatio + 1L);
    }

    /** Whether house-banked games may run at all. */
    public boolean isOpen() {
        return balance >= minimumReserve;
    }

    /**
     * Whether the house could actually pay this out.
     * <p>
     * The last line of defense, checked before settling rather than before
     * betting. Paying from a balance that cannot cover it would create
     * credits from nothing, which is the one failure this whole system exists
     * to prevent.
     */
    public boolean canCover(long payout) {
        return payout >= 0 && balance >= payout;
    }

    public Status status() {
        if (!isOpen()) {
            return Status.CLOSED;
        }
        return balance < minimumReserve * 4 ? Status.LOW : Status.HEALTHY;
    }

    /**
     * The bankroll needed to allow a given maximum bet.
     * <p>
     * The inverse of {@link #maximumBet}, so an operator asking why the limit
     * is low can be told what it would take to raise it.
     */
    public long bankrollNeededFor(long desiredMaximumBet, int payoutRatio) {
        if (desiredMaximumBet <= 0) {
            return minimumReserve;
        }
        long exposure = desiredMaximumBet * (payoutRatio + 1L);
        return Math.max(minimumReserve, exposure * 100 / exposurePercent);
    }

    /**
     * The same bankroll, with the reserve raised to cover what is promised.
     * <p>
     * Called with the exposure, the tables are holding right now, so the
     * reserve stops being a number somebody typed once and becomes a
     * statement about the current moment: a house whose balance no longer
     * covers the rounds already in flight has no business opening another
     * one. It rises as the room fills and falls back to the operator's floor
     * when the last table settles, the same way the table limits already
     * follow the balance.
     * <p>
     * This bites exactly when it should. The exposure a table may commit is
     * itself a slice of the balance, so the two only cross when the balance
     * falls after the promises were made — which is to say, when the house
     * has just been paid out.
     *
     * @param committed what every table has riding on unsettled rounds
     */
    public HouseBankroll withReserveAtLeast(long committed) {
        if (committed <= minimumReserve) {
            return this;
        }
        return new HouseBankroll(balance, exposurePercent, committed);
    }

    /** The same bankroll with a different balance. */
    public HouseBankroll withBalance(long newBalance) {
        return new HouseBankroll(newBalance, exposurePercent, minimumReserve);
    }
}
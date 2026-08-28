package com.github.arrivedbog593.tablegames.engine.session;

/**
 * The standard wagering moves, shared by poker, dompe and blackjack.
 * <p>
 * Sealed on purpose: unlike {@link Action} as a whole, this set is fixed by
 * the rules of betting itself, so exhaustive switches over it are safe and
 * desirable.
 * <p>
 * Amounts are always absolute credit values, never deltas. {@code Raise(500)}
 * means "make my total wager this round 500", not "add 500 to it". Deltas are
 * where off-by-one bugs and exploits hide.
 */
public sealed interface BettingAction extends Action {

    /** Give up the hand, forfeiting anything already wagered. */
    record Fold() implements BettingAction {
        @Override
        public String translationKey() {
            return "tablegames.action.fold";
        }
    }

    /** Stay in without wagering more. Only legal when nothing is owed. */
    record Check() implements BettingAction {
        @Override
        public String translationKey() {
            return "tablegames.action.check";
        }
    }

    /** Match the current highest wager. */
    record Call() implements BettingAction {
        @Override
        public String translationKey() {
            return "tablegames.action.call";
        }
    }

    /**
     * Open the betting at {@code amount}, or raise the current wager to it.
     *
     * @param amount the player's total wager for this round, absolute
     */
    record Raise(long amount) implements BettingAction {
        public Raise {
            if (amount <= 0) {
                throw new IllegalArgumentException("Raise must be positive: " + amount);
            }
        }

        @Override
        public String translationKey() {
            return "tablegames.action.raise";
        }
    }

    /** Wager the entire remaining stack. */
    record AllIn() implements BettingAction {
        @Override
        public String translationKey() {
            return "tablegames.action.all_in";
        }
    }
}
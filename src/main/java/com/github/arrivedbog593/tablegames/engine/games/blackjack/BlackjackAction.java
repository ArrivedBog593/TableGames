package com.github.arrivedbog593.tablegames.engine.games.blackjack;

import com.github.arrivedbog593.tablegames.engine.session.Action;

/**
 * Moves available at a blackjack table.
 * <p>
 * Each applies to the hand on the clock, so none names one. Like roulette,
 * blackjack is played against the dealer and not against the other seats,
 * so it has no use for {@code BettingAction}.
 */
public sealed interface BlackjackAction extends Action {

    /** One more card. */
    record Hit() implements BlackjackAction {
        @Override
        public String translationKey() {
            return "tablegames.action.hit";
        }
    }

    /** No more cards for this hand. */
    record Stand() implements BlackjackAction {
        @Override
        public String translationKey() {
            return "tablegames.action.stand";
        }
    }

    /** Matches the wager, takes exactly one more card, and stands. */
    record DoubleDown() implements BlackjackAction {
        @Override
        public String translationKey() {
            return "tablegames.action.double_down";
        }
    }

    /** Splits a pair into two hands, matching the wager on the second. */
    record Split() implements BlackjackAction {
        @Override
        public String translationKey() {
            return "tablegames.action.split";
        }
    }

    /**
     * Takes or declines insurance while the dealer shows an ace: half the
     * opening wager, paying two to one if the dealer has blackjack.
     */
    record Insurance(boolean take) implements BlackjackAction {
        @Override
        public String translationKey() {
            return take ? "tablegames.action.insurance" : "tablegames.action.no_insurance";
        }
    }
}

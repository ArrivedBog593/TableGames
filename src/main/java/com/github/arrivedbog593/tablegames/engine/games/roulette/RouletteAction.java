package com.github.arrivedbog593.tablegames.engine.games.roulette;

import com.github.arrivedbog593.tablegames.engine.session.Action;

import java.util.Objects;

/**
 * Moves available at a roulette table.
 * <p>
 * Roulette does not use {@code BettingAction}: there is nothing to call,
 * raise or fold against, because players wager against the wheel rather than
 * against each other. This is exactly the case {@link Action} was left
 * unsealed for.
 */
public sealed interface RouletteAction extends Action {

    /** Puts chips on the layout. Legal repeatedly while betting is open. */
    record Place(RouletteBet bet) implements RouletteAction {
        public Place {
            Objects.requireNonNull(bet, "bet");
        }

        @Override
        public String translationKey() {
            return "tablegames.action.place_bet";
        }
    }

    /** Takes back every chip this player has on the layout. */
    record ClearBets() implements RouletteAction {
        @Override
        public String translationKey() {
            return "tablegames.action.clear_bets";
        }
    }

    /** Declares this player done betting. The wheelspins once all are done. */
    record Done() implements RouletteAction {
        @Override
        public String translationKey() {
            return "tablegames.action.done_betting";
        }
    }
}
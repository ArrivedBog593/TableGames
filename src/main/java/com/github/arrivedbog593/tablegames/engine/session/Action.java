package com.github.arrivedbog593.tablegames.engine.session;

/**
 * Something a player asks to do on their turn.
 * <p>
 * Deliberately NOT a sealed interface. Sealing it would mean every new game
 * has to edit this file to add its own moves, which defeats the goal of
 * "adding a game is one class, not a redesign". Uno's color choice and
 * roulette's bet placement have nothing to do with each other and should not
 * live in a shared hierarchy.
 * <p>
 * Games that involve wagering should use {@link BettingAction} instead of
 * reinventing fold, call and raise.
 * <p>
 * Implementations must be immutable. An action is a request, not a state: the
 * session validates it and decides what happens.
 */
public interface Action {

    /** Translation key naming this action, e.g., for a button label. */
    String translationKey();

    /** Universal move: give up the seat and leave the table. */
    record Leave() implements Action {
        @Override
        public String translationKey() {
            return "tablegames.action.leave";
        }
    }

    /** Universal move: signal readiness so a waiting table can start. */
    record Ready() implements Action {
        @Override
        public String translationKey() {
            return "tablegames.action.ready";
        }
    }
}
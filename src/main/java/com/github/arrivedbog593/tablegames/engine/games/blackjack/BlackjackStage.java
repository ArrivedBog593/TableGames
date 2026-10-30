package com.github.arrivedbog593.tablegames.engine.games.blackjack;

/**
 * Where a dealt hand of blackjack stands.
 * <p>
 * Finer than {@code GameState}, which only says a hand is in progress: the
 * table needs to know whether it is waiting on everybody at once, on one seat,
 * or on nobody, because each is a different clock.
 */
public enum BlackjackStage {

    /** The dealer shows an ace and every seat may take insurance at once. */
    INSURANCE,

    /** The seats play in order, one hand at a time. */
    PLAYERS,

    /** Every seat is done; the dealer turns the hole card and draws. */
    DEALER,

    /** Paid. */
    SETTLED
}

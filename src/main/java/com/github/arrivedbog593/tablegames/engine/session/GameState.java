package com.github.arrivedbog593.tablegames.engine.session;

/**
 * Lifecycle of a single hand or round at a table.
 * <p>
 * Not every game visits every state: roulette never reaches {@link #SHOWDOWN},
 * and Uno never visits {@link #BETTING}. Games skip what they do not need
 * but must never move backwards except through {@link #FINISHED}.
 */
public enum GameState {

    /** Seats are open; not enough players have readied up yet. */
    WAITING,

    /** Antes or opening bets are being collected. */
    BETTING,

    /** Cards are being dealt with. Usually instantaneous. */
    DEALING,

    /** Players are taking turns. */
    IN_PROGRESS,

    /** Hands are revealed and compared. */
    SHOWDOWN,

    /** Payouts are settled. The session is done and can be discarded. */
    FINISHED,

    /** Aborted before completion; every wager must be refunded. */
    CANCELED;

    /** No further actions are accepted in this state. */
    public boolean isTerminal() {
        return this == FINISHED || this == CANCELED;
    }


    /** Players may submit actions in this state. */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean acceptsActions() {
        return this == BETTING || this == IN_PROGRESS;
    }
}
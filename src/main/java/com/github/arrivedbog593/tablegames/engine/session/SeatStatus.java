package com.github.arrivedbog593.tablegames.engine.session;

/**
 * Whether a seated player is still contesting the current hand.
 */
public enum SeatStatus {

    /** In the hand and able to act. */
    ACTIVE,

    /** Gave up this hand but keeps the seat. */
    FOLDED,

    /** Done acting this hand, but still contests the showdown. */
    DONE,

    /** Holds the seat but is skipped until the next hand. */
    SITTING_OUT,

    /**
     * Lost connection mid-hand. Treated as unable to act, but the seat is
     * held so the player can reclaim it on reconnect. The platform layer
     * decides how long to wait before converting this to SITTING_OUT.
     */
    DISCONNECTED;

    /** May be asked to act right now. */
    public boolean canAct() {
        return this == ACTIVE;
    }

    /** Still eligible to win the pot at showdown. */
    public boolean contestsPot() {
        return this == ACTIVE || this == DONE;
    }
}
package com.github.arrivedbog593.tablegames.engine.games.blackjack;

/**
 * Who a blackjack table plays against, and for what.
 * <p>
 * Three games on the same felt, registered separately the way the two
 * roulette wheels are, because they differ in who may set them up as much
 * as in how they play: only the house may stake its own bankroll, and
 * anybody may host a game between friends.
 */
public enum BlackjackFormat {

    /** Every seat against the casino's dealer, paid from the house's bank. */
    HOUSE,

    /**
     * Every seat against a dealer who stakes nothing, with tournament chips.
     * Each player pays the same entry once; whoever ends with the most chips
     * takes the entries.
     */
    TOURNAMENT,

    /**
     * The kitchen-table game: one of the players is the bank and pays or
     * takes from each of the others, and the bank passes round the table
     * every hand.
     */
    BANKER;

    public boolean isHouse() {
        return this == HOUSE;
    }
}

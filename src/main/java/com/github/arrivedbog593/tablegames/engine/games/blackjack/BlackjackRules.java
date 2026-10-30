package com.github.arrivedbog593.tablegames.engine.games.blackjack;

import java.util.Objects;

/**
 * The rules one blackjack table plays by.
 * <p>
 * Only what a house may choose. Everything else is fixed and the same at
 * every table, because it is the game as most people know it: the dealer
 * peeks for blackjack under an ace or a ten, insurance is offered under an
 * ace, any first two cards may be doubled, doubling after a split is
 * allowed, a pair splits into as many as {@link #MAX_HANDS} hands, and split
 * aces get one card each and are done.
 *
 * @param decks            how many decks the shoe holds
 * @param dealerHitsSoft17 whether the dealer draws to a soft seventeen; worth
 *                         about two tenths of a percent to the house
 * @param naturalPayout    what a blackjack pays
 */
public record BlackjackRules(int decks, boolean dealerHitsSoft17, NaturalPayout naturalPayout) {

    /** The most hands one seat can be playing after splitting. */
    public static final int MAX_HANDS = 4;

    /** The most decks a shoe may hold. Eight is as many as any casino deals from. */
    public static final int MAX_DECKS = 8;

    /** Six decks, the dealer standing on all seventeens, blackjack paying three to two. */
    public static final BlackjackRules STANDARD =
            new BlackjackRules(6, false, NaturalPayout.THREE_TO_TWO);

    public BlackjackRules {
        if (decks < 1 || decks > MAX_DECKS) {
            throw new IllegalArgumentException("A shoe holds 1 to " + MAX_DECKS + " decks, not " + decks);
        }
        Objects.requireNonNull(naturalPayout, "naturalPayout");
    }

    /**
     * The most the house can lose on a wager this size before a card is
     * dealt: the blackjack payout, or even money when that pays less.
     * <p>
     * Not the most it can lose by the end of the hand. A double or a split
     * puts another wager down, and the table checks the house can cover
     * that one when it is asked for, not on the off chance.
     */
    public long worstCaseOn(long bet) {
        return Math.max(bet, naturalPayout.winOn(bet));
    }
}

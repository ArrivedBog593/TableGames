package com.github.arrivedbog593.tablegames.engine.games.blackjack;

import com.github.arrivedbog593.tablegames.engine.card.Card;
import com.github.arrivedbog593.tablegames.engine.card.Rank;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One hand in front of one seat, with the wager riding on it.
 * <p>
 * A seat starts with one and may end with several: a split takes the second
 * card away to start another, each with a wager of its own. The dealer's
 * cards are not a hand in this sense — nothing rides on them — and only
 * borrow the arithmetic in {@link #totalOf} and {@link #isSoft}.
 * <p>
 * Mutable and owned by its session. Not thread-safe.
 */
public final class BlackjackHand {

    private final List<Card> cards = new ArrayList<>();
    private long bet;
    private boolean doubled;
    private boolean split;
    private boolean splitAces;
    private boolean finished;

    BlackjackHand(long bet) {
        if (bet < 1) {
            throw new IllegalArgumentException("A hand needs a wager: " + bet);
        }
        this.bet = bet;
    }

    // --- Card arithmetic, for hands and the dealer alike ------------------------------

    /** What a card counts for, with an ace as eleven. */
    public static int valueOf(Card card) {
        Rank rank = card.rank();
        if (rank == Rank.ACE) {
            return 11;
        }
        return rank.isFace() ? 10 : rank.strength();
    }

    /**
     * The best total these cards make: every ace counted as eleven until that
     * would bust, and as one from there.
     */
    public static int totalOf(List<Card> cards) {
        int total = 0;
        int elevens = 0;
        for (Card card : cards) {
            total += valueOf(card);
            if (card.rank() == Rank.ACE) {
                elevens++;
            }
        }
        while (total > 21 && elevens > 0) {
            total -= 10;
            elevens--;
        }
        return total;
    }

    /** Whether an ace is still counting as eleven, so one more card cannot bust it. */
    public static boolean isSoft(List<Card> cards) {
        int hard = 0;
        boolean ace = false;
        for (Card card : cards) {
            hard += card.rank() == Rank.ACE ? 1 : valueOf(card);
            ace |= card.rank() == Rank.ACE;
        }
        return ace && hard + 10 <= 21;
    }

    /** An ace and a ten-value card as the first two, and nothing else. */
    public static boolean isNatural(List<Card> cards) {
        return cards.size() == 2 && totalOf(cards) == 21;
    }

    // --- Reading it ---------------------------------------------------------------------

    public List<Card> cards() {
        return List.copyOf(cards);
    }

    public long bet() {
        return bet;
    }

    public int total() {
        return totalOf(cards);
    }

    public boolean soft() {
        return isSoft(cards);
    }

    public boolean busted() {
        return total() > 21;
    }

    /**
     * A blackjack. Twenty-one in two cards after a split is not one: it pays
     * even money, because the hand did not come out of the shoe that way.
     */
    public boolean natural() {
        return !split && isNatural(cards);
    }

    public boolean doubled() {
        return doubled;
    }

    /** Whether this hand came out of a split. */
    public boolean fromSplit() {
        return split;
    }

    /** Whether nothing more can happen to it until the dealer plays. */
    public boolean finished() {
        return finished;
    }

    /** Whether the two cards held could be split, setting aside whether the seat can afford it. */
    public boolean pair() {
        return cards.size() == 2 && valueOf(cards.get(0)) == valueOf(cards.get(1));
    }

    /** Whether the next move may be a double. Split aces are finished and never can. */
    public boolean canDouble() {
        return !finished && cards.size() == 2;
    }

    /**
     * Whether this hand may be split, given how many the seat is already
     * playing. Split aces are finished, which is what stops them being split
     * again.
     */
    public boolean canSplit(int handsAtSeat) {
        return !finished && pair() && handsAtSeat < BlackjackRules.MAX_HANDS;
    }

    // --- Changing it --------------------------------------------------------------------

    void add(Card card) {
        cards.add(Objects.requireNonNull(card, "card"));
    }

    void finish() {
        finished = true;
    }

    /** Matches the wager, takes one card, and stops. */
    void doubleDown(Card card) {
        bet *= 2;
        doubled = true;
        add(card);
        finish();
    }

    /**
     * Gives up the second card to start a new hand beside this one, with the
     * same wager on it.
     *
     * @return the new hand, holding only the card it was given
     */
    BlackjackHand splitOff() {
        Card moved = cards.removeLast();
        boolean aces = moved.rank() == Rank.ACE;
        this.split = true;
        this.splitAces = aces;
        BlackjackHand other = new BlackjackHand(bet);
        other.split = true;
        other.splitAces = aces;
        other.add(moved);
        return other;
    }

    /** Whether this is half of a pair of aces, which takes one card and no more. */
    boolean splitAces() {
        return splitAces;
    }
}

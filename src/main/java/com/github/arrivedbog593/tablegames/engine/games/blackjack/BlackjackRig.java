package com.github.arrivedbog593.tablegames.engine.games.blackjack;

import com.github.arrivedbog593.tablegames.engine.card.Card;
import com.github.arrivedbog593.tablegames.engine.card.Rank;
import com.github.arrivedbog593.tablegames.engine.card.Suit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Hands a test table can be told to deal, each one the situation it is named
 * after.
 * <p>
 * Every seat gets the same two cards, so the situation lands on whoever is
 * playing. The cards are put on top of the shoe in dealing order — a card to
 * each seat, one to the dealer, then again — followed by a few more for
 * whatever the hand draws next. Nothing else about the hand is touched: it is
 * played and paid exactly as if the shoe had dealt it.
 */
public enum BlackjackRig {

    /** Every seat is dealt blackjack against a dealer's sixteen. */
    PLAYER_BLACKJACK(card(Rank.ACE), card(Rank.KING), card(Rank.NINE), card(Rank.SEVEN)),

    /** The dealer shows an ace, offers insurance, and has blackjack under it. */
    DEALER_BLACKJACK(card(Rank.TEN), card(Rank.SEVEN), card(Rank.ACE), card(Rank.KING)),

    /** The dealer shows an ace and does not have it: insurance is lost. */
    INSURANCE_LOST(card(Rank.TEN), card(Rank.NINE), card(Rank.ACE), card(Rank.SEVEN)),

    /** A pair of eights against a six, to split; the shoe then runs tens. */
    PAIR(card(Rank.EIGHT), card(Rank.EIGHT), card(Rank.SIX), card(Rank.TEN),
            card(Rank.THREE), card(Rank.TEN), card(Rank.TEN), card(Rank.TEN), card(Rank.TEN)),

    /** Eleven against a six, to double; a ten comes next, and the dealer busts. */
    DOUBLE(card(Rank.SIX), card(Rank.FIVE), card(Rank.SIX), card(Rank.TEN),
            card(Rank.TEN), card(Rank.TEN), card(Rank.TEN), card(Rank.TEN)),

    /** Eighteen against a dealer who draws from sixteen to twenty-six. */
    DEALER_BUSTS(card(Rank.TEN), card(Rank.EIGHT), card(Rank.SIX), card(Rank.TEN),
            card(Rank.TEN), card(Rank.TEN), card(Rank.TEN));

    private final Card first;
    private final Card second;
    private final Card upcard;
    private final Card hole;
    private final List<Card> then;

    BlackjackRig(Card first, Card second, Card upcard, Card hole, Card... then) {
        this.first = first;
        this.second = second;
        this.upcard = upcard;
        this.hole = hole;
        this.then = List.of(then);
    }

    /** What a panel lists it by. Persisted nowhere, but sent to clients. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String translationKey() {
        return "tablegames.debug.rig.blackjack." + id();
    }

    /** The top of the shoe for a hand dealt to this many seats, in the order it comes out. */
    public List<Card> cardsFor(int seats) {
        List<Card> cards = new ArrayList<>();
        for (int i = 0; i < seats; i++) {
            cards.add(first);
        }
        cards.add(upcard);
        for (int i = 0; i < seats; i++) {
            cards.add(second);
        }
        cards.add(hole);
        // Extra draws: enough for every seat to play the situation out, and
        // the dealer after them.
        for (int i = 0; i < seats; i++) {
            cards.addAll(then);
        }
        return cards;
    }

    private static Card card(Rank rank) {
        // The suit is decoration. Spread them so a screen shows a real hand.
        return new Card(rank, Suit.values()[rank.ordinal() % Suit.values().length]);
    }
}

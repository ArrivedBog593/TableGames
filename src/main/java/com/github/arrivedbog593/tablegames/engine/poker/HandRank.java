package com.github.arrivedbog593.tablegames.engine.poker;

import com.github.arrivedbog593.tablegames.engine.card.Rank;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/**
 * The result of evaluating a hand: its category plus tiebreakers.
 * <p>
 * {@code tiebreakers} holds rank strengths in descending order of
 * importance. Their meaning depends on the category:
 * <ul>
 *   <li>{@code FOUR_OF_A_KIND} &rarr; [quad rank, kicker]</li>
 *   <li>{@code FULL_HOUSE} &rarr; [trips rank, pair rank]</li>
 *   <li>{@code TWO_PAIR} &rarr; [high pair, low pair, kicker]</li>
 *   <li>{@code FLUSH} and {@code HIGH_CARD} &rarr; all five cards, high to low</li>
 *   <li>{@code STRAIGHT} and {@code STRAIGHT_FLUSH} &rarr; [high card]</li>
 * </ul>
 * Two hands of the same category always carry lists of equal length, so
 *  the element-wise comparison is exact.
 * <p>
 * Important: {@code compareTo() == 0} means a genuine tie. Suits NEVER break
 * ties in poker; when two players tie, the pot is split (or whatever house
 * rule the table defines).
 */
public record HandRank(HandCategory category, List<Integer> tiebreakers)
        implements Comparable<HandRank> {

    public HandRank {
        Objects.requireNonNull(category, "category");
        tiebreakers = List.copyOf(Objects.requireNonNull(tiebreakers, "tiebreakers"));
    }

    @Override
    public int compareTo(HandRank other) {
        int byCategory = Integer.compare(category.ordinal(), other.category.ordinal());
        if (byCategory != 0) {
            return byCategory;
        }
        int shared = Math.min(tiebreakers.size(), other.tiebreakers.size());
        for (int i = 0; i < shared; i++) {
            int cmp = Integer.compare(tiebreakers.get(i), other.tiebreakers.get(i));
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(tiebreakers.size(), other.tiebreakers.size());
    }

    public boolean beats(HandRank other) {
        return compareTo(other) > 0;
    }

    public boolean ties(HandRank other) {
        return compareTo(other) == 0;
    }

    /**
     * A royal flush: an Ace-high straight flush.
     * <p>
     * Not a category of its own because comparison does not need it, but the
     * UI and video poker pay-tables do distinguish it.
     */
    public boolean isRoyalFlush() {
        return category == HandCategory.STRAIGHT_FLUSH
                && !tiebreakers.isEmpty()
                && tiebreakers.getFirst() == Rank.ACE.strength();
    }

    /**
     * Translation key for display, accounting for named special hands.
     * Resolved to text by the platform layer.
     */
    public String translationKey() {
        return isRoyalFlush() ? "tablegames.hand.royal_flush" : category.translationKey();
    }

    @Override
    public @NotNull String toString() {
        return category + tiebreakers.toString();
    }
}
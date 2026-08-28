package com.github.arrivedbog593.tablegames.engine.poker;

import com.github.arrivedbog593.tablegames.engine.card.Card;
import com.github.arrivedbog593.tablegames.engine.card.Rank;
import com.github.arrivedbog593.tablegames.engine.card.Suit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Five-card poker hand evaluator.
 * <p>
 * Stateless and free of Minecraft dependencies. Shared by poker, dompe and
 * video poker. If Texas Hold'em is ever added, {@link #best(List)} already
 * solves "best five of seven".
 */
public final class HandEvaluator {

    /** Every poker hand is scored over exactly five cards. */
    public static final int HAND_SIZE = 5;

    private HandEvaluator() {
    }

    /**
     * The best hand available from a set of cards, together with the five
     * cards that form it.
     */
    public record BestHand(HandRank rank, List<Card> cards) {
        public BestHand {
            cards = List.copyOf(cards);
        }
    }

    /**
     * Evaluates exactly five cards.
     *
     * @throws IllegalArgumentException if the hand is not five cards
     */
    public static HandRank evaluate(List<Card> hand) {
        Objects.requireNonNull(hand, "hand");
        if (hand.size() != HAND_SIZE) {
            throw new IllegalArgumentException(
                    "Expected " + HAND_SIZE + " cards, got " + hand.size());
        }

        // countByStrength[s] = how many cards have strength s. Indices 2..14.
        int[] countByStrength = new int[Rank.ACE.strength() + 1];
        for (Card card : hand) {
            countByStrength[card.strength()]++;
        }

        boolean flush = isFlush(hand);
        int straightHigh = straightHigh(countByStrength);

        if (straightHigh > 0 && flush) {
            return new HandRank(HandCategory.STRAIGHT_FLUSH, List.of(straightHigh));
        }

        // Groups of [count, strength], sorted by count desc then strength desc.
        // That ordering is exactly the tiebreak ordering for nearly every category.
        List<int[]> groups = new ArrayList<>();
        for (int strength = Rank.ACE.strength(); strength >= Rank.TWO.strength(); strength--) {
            if (countByStrength[strength] > 0) {
                groups.add(new int[]{countByStrength[strength], strength});
            }
        }
        groups.sort(Comparator
                .<int[]>comparingInt(g -> g[0]).reversed()
                .thenComparing(Comparator.<int[]>comparingInt(g -> g[1]).reversed()));

        int topCount = groups.get(0)[0];
        int secondCount = groups.size() > 1 ? groups.get(1)[0] : 0;

        if (topCount == 4) {
            return new HandRank(HandCategory.FOUR_OF_A_KIND, strengthsOf(groups));
        }
        if (topCount == 3 && secondCount == 2) {
            return new HandRank(HandCategory.FULL_HOUSE, strengthsOf(groups));
        }
        if (flush) {
            return new HandRank(HandCategory.FLUSH, strengthsOf(groups));
        }
        if (straightHigh > 0) {
            return new HandRank(HandCategory.STRAIGHT, List.of(straightHigh));
        }
        if (topCount == 3) {
            return new HandRank(HandCategory.THREE_OF_A_KIND, strengthsOf(groups));
        }
        if (topCount == 2 && secondCount == 2) {
            return new HandRank(HandCategory.TWO_PAIR, strengthsOf(groups));
        }
        if (topCount == 2) {
            return new HandRank(HandCategory.PAIR, strengthsOf(groups));
        }
        return new HandRank(HandCategory.HIGH_CARD, strengthsOf(groups));
    }

    /**
     * The best five-card hand that can be formed from the given cards. With
     * five cards this is equivalent to {@link #evaluate}; with seven it
     * checks all twenty-one combinations.
     *
     * @throws IllegalArgumentException if fewer than five cards are given
     */
    public static BestHand best(List<Card> cards) {
        Objects.requireNonNull(cards, "cards");
        if (cards.size() < HAND_SIZE) {
            throw new IllegalArgumentException(
                    "Need at least " + HAND_SIZE + " cards, got " + cards.size());
        }
        if (cards.size() == HAND_SIZE) {
            return new BestHand(evaluate(cards), cards);
        }

        BestHand best = null;
        for (List<Card> combo : combinations(cards, HAND_SIZE)) {
            HandRank rank = evaluate(combo);
            if (best == null || rank.beats(best.rank())) {
                best = new BestHand(rank, combo);
            }
        }
        return best;
    }

    /** Shortcut when only the strength matters, not which cards form it. */
    public static HandRank bestRank(List<Card> cards) {
        return best(cards).rank();
    }

    private static boolean isFlush(List<Card> hand) {
        Suit first = hand.getFirst().suit();
        for (Card card : hand) {
            if (card.suit() != first) {
                return false;
            }
        }
        return true;
    }

    /**
     * The high card of the straight, or 0 if there is none.
     * <p>
     * The wheel A-2-3-4-5 is treated as a straight with high-card five, which
     * is correct: it loses to 2-3-4-5-6. This is the special case everyone
     * gets wrong.
     */
    private static int straightHigh(int[] countByStrength) {
        for (int high = Rank.ACE.strength(); high >= Rank.SIX.strength(); high--) {
            boolean run = true;
            for (int offset = 0; offset < HAND_SIZE; offset++) {
                if (countByStrength[high - offset] == 0) {
                    run = false;
                    break;
                }
            }
            if (run) {
                return high;
            }
        }
        boolean wheel = countByStrength[Rank.ACE.strength()] > 0
                && countByStrength[Rank.FIVE.strength()] > 0
                && countByStrength[Rank.FOUR.strength()] > 0
                && countByStrength[Rank.THREE.strength()] > 0
                && countByStrength[Rank.TWO.strength()] > 0;
        return wheel ? Rank.FIVE.strength() : 0;
    }

    private static List<Integer> strengthsOf(List<int[]> groups) {
        List<Integer> out = new ArrayList<>(groups.size());
        for (int[] group : groups) {
            out.add(group[1]);
        }
        return out;
    }

    /** Every combination of {@code size} cards, without repetition. */
    private static List<List<Card>> combinations(List<Card> source, int size) {
        List<List<Card>> result = new ArrayList<>();
        buildCombinations(source, size, 0, new ArrayList<>(size), result);
        return result;
    }

    private static void buildCombinations(List<Card> source, int size, int start,
                                          List<Card> current, List<List<Card>> result) {
        if (current.size() == size) {
            result.add(List.copyOf(current));
            return;
        }
        int remaining = size - current.size();
        for (int i = start; i <= source.size() - remaining; i++) {
            current.add(source.get(i));
            buildCombinations(source, size, i + 1, current, result);
            current.removeLast();
        }
    }
}
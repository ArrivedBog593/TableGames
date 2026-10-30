package com.github.arrivedbog593.tablegames.engine.games.blackjack;

import com.github.arrivedbog593.tablegames.engine.session.Outcome;
import com.github.arrivedbog593.tablegames.engine.session.Payout;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A blackjack tournament, from the first hand to the prizes.
 * <p>
 * Run the way a casino runs one. Everybody pays the same entry, which makes
 * the pot, and gets the same tournament chips, which are worth nothing
 * outside it. They play a fixed number of hands against a dealer who stakes
 * nothing, and the chips they end with decide who takes the pot. Doubles
 * and splits are paid in tournament chips, so the entry is all anybody ever
 * pays.
 * <ul>
 *   <li>A player with no chips left is out, and watches.</li>
 *   <li>The tournament ends when the hands run out, or at once when only one
 *       player is still in: nobody is left to beat.</li>
 *   <li>Players out on the same hand are ranked by what they had when that
 *       hand began, so the pot always goes to somebody, even when the last
 *       hand takes everybody. It never goes back to the house.</li>
 *   <li>Players still level after that share the prizes of the places they
 *       cover; what does not divide goes to whichever of them sits nearest
 *       the dealer's left.</li>
 *   <li>Somebody who withdraws keeps no claim on the pot and ranks last.</li>
 * </ul>
 * This keeps the score. The hands themselves are {@link BlackjackSession}s,
 * dealt by the table from each player's chips here.
 */
public final class BlackjackTournament {

    /** One finisher: where they placed and what they won. */
    public record Standing(UUID playerId, int place, long chips, long prize) {
    }

    private final List<UUID> entrants;
    private final long entry;
    private final int hands;
    private final PrizeSplit split;

    private final Map<UUID, Long> chips = new LinkedHashMap<>();
    private final Map<UUID, Long> chipsAtHandStart = new HashMap<>();

    /** The hand a player went out on, counted from one. */
    private final Map<UUID, Integer> outOnHand = new HashMap<>();

    /** What a player held when the hand they went out on began. */
    private final Map<UUID, Long> heldGoingOut = new HashMap<>();

    private final Set<UUID> withdrawn = new LinkedHashSet<>();

    private int handsPlayed;

    /**
     * @param entrants      who is playing, in seat order from the dealer's left
     * @param entry         what each paid; zero for a tournament played for fun
     * @param startingChips the tournament chips each starts with
     * @param hands         how many hands it lasts
     */
    public BlackjackTournament(List<UUID> entrants, long entry, long startingChips, int hands,
                               PrizeSplit split) {
        this.entrants = List.copyOf(entrants);
        if (this.entrants.size() < 2) {
            throw new IllegalArgumentException("A tournament needs two players");
        }
        if (entry < 0 || startingChips < 1 || hands < 1) {
            throw new IllegalArgumentException("Bad tournament: entry " + entry
                    + ", chips " + startingChips + ", hands " + hands);
        }
        this.entry = entry;
        this.hands = hands;
        this.split = Objects.requireNonNull(split, "split");
        for (UUID entrant : this.entrants) {
            chips.put(entrant, startingChips);
        }
    }

    // --- Playing it -------------------------------------------------------------------------

    /** Notes what everybody still in holds as a hand is dealt; the tiebreak for going out on it. */
    public void startHand() {
        for (UUID player : stillIn()) {
            chipsAtHandStart.put(player, chips.get(player));
        }
    }

    /**
     * Applies what one hand won and lost, puts out whoever has nothing left,
     * and counts the hand.
     *
     * @param deltas tournament chips won, or lost when negative, by each player dealt in
     */
    public void recordHand(Map<UUID, Long> deltas) {
        handsPlayed++;
        for (Map.Entry<UUID, Long> delta : deltas.entrySet()) {
            UUID player = delta.getKey();
            if (!isIn(player)) {
                continue;
            }
            long now = Math.max(0, chips.get(player) + delta.getValue());
            chips.put(player, now);
            if (now == 0) {
                outOnHand.put(player, handsPlayed);
                heldGoingOut.put(player, chipsAtHandStart.getOrDefault(player, 0L));
            }
        }
    }

    /** Gives up a place in the tournament. The entry stays in the pot. */
    public void withdraw(UUID player) {
        if (isIn(player)) {
            withdrawn.add(player);
            chips.put(player, 0L);
        }
    }

    /** Whether the tournament is decided: the hands are played, or one player is left. */
    public boolean isOver() {
        return handsPlayed >= hands || stillIn().size() <= 1;
    }

    // --- Reading it ---------------------------------------------------------------------------

    public List<UUID> entrants() {
        return entrants;
    }

    public boolean isEntrant(UUID player) {
        return chips.containsKey(player);
    }

    /** Whether this player is still playing: entered, not withdrawn, chips left. */
    public boolean isIn(UUID player) {
        return isEntrant(player) && !withdrawn.contains(player) && chips.get(player) > 0;
    }

    public boolean hasWithdrawn(UUID player) {
        return withdrawn.contains(player);
    }

    /** Everybody still playing, in seat order. */
    public List<UUID> stillIn() {
        return entrants.stream().filter(this::isIn).toList();
    }

    public long chipsOf(UUID player) {
        return chips.getOrDefault(player, 0L);
    }

    public long entry() {
        return entry;
    }

    public long pot() {
        return entry * entrants.size();
    }

    public int hands() {
        return hands;
    }

    public int handsPlayed() {
        return handsPlayed;
    }

    public PrizeSplit split() {
        return split;
    }

    // --- The result ---------------------------------------------------------------------------

    /**
     * Everybody, best first, with what they won.
     * <p>
     * Meaningful once {@link #isOver()}; before then it is the table as it
     * stands, which is what the felt shows between hands.
     */
    public List<Standing> standings() {
        List<UUID> order = new ArrayList<>(entrants);
        order.sort(ranking());

        List<Long> prizes = split.prizesFor(pot());
        List<Standing> standings = new ArrayList<>();
        int place = 0;
        while (place < order.size()) {
            // Everybody level with this player shares the places they cover.
            int end = place + 1;
            while (end < order.size() && ranking().compare(order.get(place), order.get(end)) == 0) {
                end++;
            }
            long shared = 0;
            for (int covered = place; covered < end; covered++) {
                shared += covered < prizes.size() ? prizes.get(covered) : 0;
            }
            int sharers = end - place;
            long each = shared / sharers;
            long left = shared % sharers;
            // The group is in seat order already: the sort is stable and the
            // entrants started in seat order. The first of it sits nearest
            // the dealer's left, and takes what does not divide.
            for (int i = place; i < end; i++) {
                UUID player = order.get(i);
                standings.add(new Standing(player, place + 1, chips.get(player),
                        each + (i == place ? left : 0)));
            }
            place = end;
        }
        return List.copyOf(standings);
    }

    /**
     * The credits that move: each entrant's prize less the entry they paid.
     * Zero-sum by construction, since the prizes are the pot and the pot is
     * the entries.
     */
    public Outcome outcome() {
        List<Payout> payouts = new ArrayList<>();
        List<UUID> winners = new ArrayList<>();
        for (Standing standing : standings()) {
            payouts.add(new Payout(standing.playerId(), standing.prize() - entry));
            if (standing.prize() > 0) {
                winners.add(standing.playerId());
            }
        }
        return new Outcome(payouts, winners, 0, "tablegames.summary.blackjack.tournament");
    }

    /**
     * Best first: still in, by chips; then out, later before earlier, and on
     * the same hand by what they held going into it; withdrawn last.
     */
    private Comparator<UUID> ranking() {
        return Comparator.comparingInt(this::tier).reversed()
                .thenComparing(Comparator.comparingLong(this::primary).reversed())
                .thenComparing(Comparator.comparingLong(this::secondary).reversed());
    }

    private int tier(UUID player) {
        if (withdrawn.contains(player)) {
            return 0;
        }
        return isIn(player) ? 2 : 1;
    }

    private long primary(UUID player) {
        return isIn(player) ? chips.get(player) : outOnHand.getOrDefault(player, 0);
    }

    private long secondary(UUID player) {
        return isIn(player) ? 0 : heldGoingOut.getOrDefault(player, 0L);
    }
}

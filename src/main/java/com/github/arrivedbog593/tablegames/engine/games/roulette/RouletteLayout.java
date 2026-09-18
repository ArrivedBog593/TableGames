package com.github.arrivedbog593.tablegames.engine.games.roulette;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * What is on the layout right now: every wager, whoever placed it.
 * <p>
 * A table accumulates bets across the whole betting window before any
 * session exists to hold them — roulette settles everyone at once, so there
 * is nothing to replay a wager into until the window closes and the wheel is
 * about to turn. This is that accumulation, pulled out of the block entity
 * so the arithmetic that decides "how much is already on this position" can
 * be tested without a running server.
 * <p>
 * Pure bookkeeping. Legality — minimums, maximums, house exposure, whether a
 * player can afford it — is the caller's business; this class only tracks
 * what has already been accepted onto the felt.
 * <p>
 * Mutable and not thread-safe, like the block entity that owns one.
 */
public final class RouletteLayout {

    private final Map<UUID, List<RouletteBet>> bets = new LinkedHashMap<>();

    /** Adds a wager to what this player already has down. */
    public void place(UUID playerId, RouletteBet bet) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(bet, "bet");
        bets.computeIfAbsent(playerId, key -> new ArrayList<>()).add(bet);
    }

    /**
     * Takes every chip this player has down off the layout.
     *
     * @return false if they had nothing there to begin with
     */
    public boolean clear(UUID playerId) {
        return bets.remove(playerId) != null;
    }

    /** Clears the whole layout: every player, every chip. */
    public void clearAll() {
        bets.clear();
    }

    /** What this player has on the layout right now, never null. */
    public List<RouletteBet> betsOf(UUID playerId) {
        return List.copyOf(bets.getOrDefault(playerId, List.of()));
    }

    /** How much this player has wagered in total this round. */
    public long wageredBy(UUID playerId) {
        long total = 0;
        for (RouletteBet bet : betsOf(playerId)) {
            total += bet.amount();
        }
        return total;
    }

    /**
     * What is riding on the same position as this wager.
     * <p>
     * The same position means the same bet type and the same target: two
     * chips on red are one stake, a chip on red and one on 17 are two.
     *
     * @param playerId whose chips to count, or null for the whole table
     */
    public long stakedOn(UUID playerId, RouletteBet bet) {
        Objects.requireNonNull(bet, "bet");
        long total = 0;
        for (Map.Entry<UUID, List<RouletteBet>> entry : bets.entrySet()) {
            if (playerId != null && !playerId.equals(entry.getKey())) {
                continue;
            }
            for (RouletteBet placed : entry.getValue()) {
                if (placed.type() == bet.type()
                        && Objects.equals(placed.target(), bet.target())) {
                    total += placed.amount();
                }
            }
        }
        return total;
    }

    /** Every wager on the layout, whoever placed it. */
    public List<RouletteBet> allBets() {
        List<RouletteBet> all = new ArrayList<>();
        for (List<RouletteBet> placed : bets.values()) {
            all.addAll(placed);
        }
        return all;
    }

    /** Everyone with at least one chip down, in the order they first bet. */
    public List<UUID> players() {
        return List.copyOf(bets.keySet());
    }

    public boolean isEmpty() {
        return bets.isEmpty();
    }
}

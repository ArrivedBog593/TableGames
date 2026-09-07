package com.github.arrivedbog593.tablegames.engine.games.roulette;

import com.github.arrivedbog593.tablegames.engine.game.Game;
import com.github.arrivedbog593.tablegames.engine.session.GameSession;
import com.github.arrivedbog593.tablegames.engine.session.Seat;

import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * Roulette, in whichever wheel variant it was configured with.
 * <p>
 * European and American are the same game with a different wheel, so they are
 * registered as two instances rather than two classes. A server can run both
 * at once: the table block stores which game id it hosts, so one table can be
 * European and the table beside it American.
 * <p>
 * A record because a game is configuration and a factory, never a hand in
 * progress. Everything that changes during a spin lives in
 * {@link RouletteSession}, and having no room to store it here is the point.
 *
 * @param id     registry id, persisted in table block entities
 * @param wheel  which pockets the ball can land in
 * @param limits what a table hosting this game takes when nobody configured
 *               it: a small minimum and no ceiling of the game's own. The
 *               maximum used to be a flat ten thousand, which nobody chose
 *               for any reason and which quietly overrode the limit derived
 *               from the bankroll — a house with a hundred million could
 *               afford a hundred and thirty-eight thousand on a single number
 *               and would still refuse anything over ten. A game does not
 *               know how much the house has, so it is in no position to set
 *               the number: the bankroll caps what can be paid, and the
 *               table's own limits cap what it chooses to take
 */
public record RouletteGame(String id, RouletteWheel wheel, BetLimits limits)
        implements Game {

    private static final int MAX_SEATS = 8;

    public RouletteGame {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(wheel, "wheel");
        Objects.requireNonNull(limits, "limits");
    }

    /** Single zero, 2.70% house edge. The friendlier default. */
    public static RouletteGame european() {
        return new RouletteGame("roulette", RouletteWheel.EUROPEAN, BetLimits.DEFAULT);
    }

    /** Zero and double zero, 5.26% house edge. */
    public static RouletteGame american() {
        return new RouletteGame("american_roulette", RouletteWheel.AMERICAN,
                BetLimits.DEFAULT);
    }

    @Override
    public int minPlayers() {
        return 1;
    }

    @Override
    public int maxPlayers() {
        return MAX_SEATS;
    }

    @Override
    public boolean usesBetting() {
        return true;
    }

    @Override
    public boolean isHouseBanked() {
        return true;
    }

    /**
     * The smallest wager the game takes anywhere on the layout.
     * <p>
     * Inside and outside bets have their own minimums, so a single figure can
     * only be the lower of the two. Anything that needs to know what a
     * particular bet costs must ask {@link BetLimits#minimumFor} instead.
     */
    @Override
    public long minimumBet() {
        return Math.min(limits.insideMinimum(), limits.outsideMinimum());
    }

    @Override
    public GameSession createSession(List<Seat> seats, RandomGenerator random) {
        return createSession(seats, random, limits);
    }

    /**
     * Creates a session bound to one table's limits.
     * <p>
     * The platform layer calls this, because the numbers a table posts are
     * the numbers the rules have to enforce. Building the session from the
     * game's own defaults instead left the two layers disagreeing about what
     * was legal: the block took a wager under its configured minimum, and the
     * session refused the same wager at the spin, which drops a stake that a
     * player watched being accepted.
     */
    public GameSession createSession(List<Seat> seats, RandomGenerator random,
                                     BetLimits tableLimits) {
        if (!canStartWith(seats.size())) {
            throw new IllegalArgumentException(
                    "Roulette needs " + minPlayers() + " to " + maxPlayers()
                            + " players, got " + seats.size());
        }
        return new RouletteSession(seats, random, wheel, tableLimits);
    }
}
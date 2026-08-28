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
 */
public final class RouletteGame implements Game {

    private static final int MAX_SEATS = 8;
    private static final long DEFAULT_MINIMUM_BET = 10;
    private static final long DEFAULT_MAXIMUM_BET = 10_000;

    private final String id;
    private final RouletteWheel wheel;
    private final long minimumBet;
    private final long maximumBet;

    public RouletteGame(String id, RouletteWheel wheel, long minimumBet, long maximumBet) {
        this.id = Objects.requireNonNull(id, "id");
        this.wheel = Objects.requireNonNull(wheel, "wheel");
        if (minimumBet <= 0 || maximumBet < minimumBet) {
            throw new IllegalArgumentException(
                    "Invalid bet limits: " + minimumBet + ".." + maximumBet);
        }
        this.minimumBet = minimumBet;
        this.maximumBet = maximumBet;
    }

    /** Single zero, 2.70% house edge. The friendlier default. */
    public static RouletteGame european() {
        return new RouletteGame("roulette", RouletteWheel.EUROPEAN,
                DEFAULT_MINIMUM_BET, DEFAULT_MAXIMUM_BET);
    }

    /** Zero and double zero, 5.26% house edge. */
    public static RouletteGame american() {
        return new RouletteGame("american_roulette", RouletteWheel.AMERICAN,
                DEFAULT_MINIMUM_BET, DEFAULT_MAXIMUM_BET);
    }

    @Override
    public String id() {
        return id;
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

    @Override
    public long minimumBet() {
        return minimumBet;
    }

    /**
     * The table maximum. Essential for a house-banked game: without a cap, a
     * single lucky straight-up bet can drain the house balance and mint
     * credits out of nothing.
     */
    public long maximumBet() {
        return maximumBet;
    }

    public RouletteWheel wheel() {
        return wheel;
    }

    /**
     * The worst case this table can cost the house on one spin, per seat.
     * The platform layer should refuse to open a table whose house balance
     * cannot cover this.
     */
    public long maximumExposurePerSeat() {
        return maximumBet * (BetType.STRAIGHT_UP.payoutRatio() + 1L);
    }

    @Override
    public GameSession createSession(List<Seat> seats, RandomGenerator random) {
        if (!canStartWith(seats.size())) {
            throw new IllegalArgumentException(
                    "Roulette needs " + minPlayers() + " to " + maxPlayers()
                            + " players, got " + seats.size());
        }
        return new RouletteSession(seats, random, wheel, minimumBet, maximumBet);
    }
}
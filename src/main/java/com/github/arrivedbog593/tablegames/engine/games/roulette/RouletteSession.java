package com.github.arrivedbog593.tablegames.engine.games.roulette;

import com.github.arrivedbog593.tablegames.engine.session.Action;
import com.github.arrivedbog593.tablegames.engine.session.ActionResult;
import com.github.arrivedbog593.tablegames.engine.session.GameSession;
import com.github.arrivedbog593.tablegames.engine.session.GameState;
import com.github.arrivedbog593.tablegames.engine.session.Outcome;
import com.github.arrivedbog593.tablegames.engine.session.Payout;
import com.github.arrivedbog593.tablegames.engine.session.Seat;
import com.github.arrivedbog593.tablegames.engine.session.SeatStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * One spin of a roulette table.
 * <p>
 * Turn-less: everyone bets simultaneously during {@link GameState#BETTING},
 * then the wheelspins once. The turn clock is never engaged, which is why
 * this class is a good stress test of the session machinery — it exercises
 * the path where {@code currentTurn()} stays empty throughout.
 * <p>
 * House-banked, so the outcome is deliberately not a zero-sum: on a lucky spin,
 * the table pays out more than it took in. The platform layer must check the
 * house balance and enforce a table maximum before letting a spin start.
 */
public final class RouletteSession extends GameSession {

    private final RouletteWheel wheel;
    private final long minimumBet;
    private final long maximumBet;

    private final Map<UUID, List<RouletteBet>> bets = new LinkedHashMap<>();
    private final Set<UUID> doneBetting = new LinkedHashSet<>();

    private Pocket result;

    public RouletteSession(List<Seat> seats, RandomGenerator random,
                           RouletteWheel wheel, long minimumBet, long maximumBet) {
        super(seats, random);
        this.wheel = Objects.requireNonNull(wheel, "wheel");
        if (minimumBet <= 0 || maximumBet < minimumBet) {
            throw new IllegalArgumentException(
                    "Invalid bet limits: " + minimumBet + ".." + maximumBet);
        }
        this.minimumBet = minimumBet;
        this.maximumBet = maximumBet;
    }

    @Override
    protected void onBegin() {
        for (Seat seat : seats()) {
            seat.setStatus(SeatStatus.ACTIVE);
        }
        clearTurn();
        setState(GameState.BETTING);
    }

    @Override
    public List<Action> legalActions(UUID playerId) {
        if (state() != GameState.BETTING || seatOf(playerId).isEmpty()) {
            return List.of();
        }
        List<Action> actions = new ArrayList<>();
        actions.add(new RouletteAction.Done());
        if (!betsOf(playerId).isEmpty()) {
            actions.add(new RouletteAction.ClearBets());
        }
        // Place is always offered; the concrete bet is chosen in the UI.
        return List.copyOf(actions);
    }

    @Override
    protected ActionResult onAction(Seat seat, Action action) {
        if (state() != GameState.BETTING) {
            return ActionResult.wrongState();
        }
        if (action instanceof RouletteAction.Place(RouletteBet bet)) {
            return placeBet(seat, bet);
        }
        if (action instanceof RouletteAction.ClearBets) {
            return clearBets(seat);
        }
        if (action instanceof RouletteAction.Done) {
            doneBetting.add(seat.playerId());
            return ActionResult.ok();
        }
        return ActionResult.illegalAction();
    }

    private ActionResult placeBet(Seat seat, RouletteBet bet) {
        if (bet.amount() < minimumBet) {
            return ActionResult.rejected("tablegames.reject.below_minimum_bet");
        }
        if (bet.amount() > maximumBet) {
            return ActionResult.rejected("tablegames.reject.above_maximum_bet");
        }
        if (bet.type().requiresTarget() && !wheel.pockets().contains(bet.target())) {
            // Guards against betting 00 on a European wheel.
            return ActionResult.rejected("tablegames.reject.no_such_pocket");
        }
        if (seat.credits() < bet.amount()) {
            return ActionResult.insufficientCredits();
        }
        seat.wager(bet.amount());
        bets.computeIfAbsent(seat.playerId(), key -> new ArrayList<>()).add(bet);
        doneBetting.remove(seat.playerId());
        return ActionResult.ok();
    }

    private ActionResult clearBets(Seat seat) {
        List<RouletteBet> placed = bets.remove(seat.playerId());
        if (placed == null || placed.isEmpty()) {
            return ActionResult.illegalAction();
        }
        // collectBet zeroes the running wager; hand it straight back.
        seat.award(seat.collectBet());
        doneBetting.remove(seat.playerId());
        return ActionResult.ok();
    }

    /** Bets currently on the layout for a player, never null. */
    public List<RouletteBet> betsOf(UUID playerId) {
        return Collections.unmodifiableList(
                bets.getOrDefault(playerId, List.of()));
    }

    /** Whether every seated player has declared themselves done. */
    public boolean allDoneBetting() {
        for (Seat seat : seats()) {
            if (!doneBetting.contains(seat.playerId())) {
                return false;
            }
        }
        return true;
    }

    /** The winning pocket, present only after the wheel has been spun. */
    public java.util.Optional<Pocket> result() {
        return java.util.Optional.ofNullable(result);
    }

    public RouletteWheel wheel() {
        return wheel;
    }

    /**
     * Closes betting, spins the ball, and settles every wager.
     * <p>
     * Called by the platform layer when the betting window closes, either
     * because the timer ran out or because everyone declared done. Players
     * never trigger this directly.
     */
    public void spin() {
        if (state() != GameState.BETTING) {
            throw new IllegalStateException("Cannot spin while " + state());
        }
        setState(GameState.IN_PROGRESS);
        result = wheel.spin(random());

        long staked = collectBets();
        addToPot(0);

        List<Payout> payouts = new ArrayList<>();
        List<UUID> winners = new ArrayList<>();
        long returned = 0;

        for (Seat seat : seats()) {
            List<RouletteBet> placed = bets.getOrDefault(seat.playerId(), List.of());
            long wagered = 0;
            long won = 0;
            for (RouletteBet bet : placed) {
                wagered += bet.amount();
                won += bet.payout(result);
            }
            if (won > 0) {
                seat.award(won);
                winners.add(seat.playerId());
            }
            returned += won;
            payouts.add(new Payout(seat.playerId(), won - wagered));
        }

        // Whatever the house keeps stays in the pot as its take; a losing
        // spin for the house leaves the pot at zero, and the difference is
        // covered from the house balance by the platform layer.
        takePot();

        finish(new Outcome(payouts, winners, 0, summaryKeyFor(staked, returned)));
    }

    private String summaryKeyFor(long staked, long returned) {
        if (returned > staked) {
            return "tablegames.summary.roulette.players_won";
        }
        if (returned == staked) {
            return "tablegames.summary.roulette.even";
        }
        return "tablegames.summary.roulette.house_won";
    }

    @Override
    public int turnTimeoutSeconds() {
        // The betting window, not a per-player clock.
        return 45;
    }
}
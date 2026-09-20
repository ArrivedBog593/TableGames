package com.github.arrivedbog593.tablegames.engine.games.slots;

import com.github.arrivedbog593.tablegames.engine.session.Action;
import com.github.arrivedbog593.tablegames.engine.session.ActionResult;
import com.github.arrivedbog593.tablegames.engine.session.GameSession;
import com.github.arrivedbog593.tablegames.engine.session.GameState;
import com.github.arrivedbog593.tablegames.engine.session.Outcome;
import com.github.arrivedbog593.tablegames.engine.session.Payout;
import com.github.arrivedbog593.tablegames.engine.session.Seat;
import com.github.arrivedbog593.tablegames.engine.session.SeatStatus;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * A single spin: one player, one pull of the lever, one outcome.
 * <p>
 * Built fresh for each spin, the same way a roulette round is built for each
 * turn of the wheel, so the platform settles a slot machine exactly as it
 * settles every other game — from the {@link Outcome} a session finishes with.
 */
public final class SlotsSession extends GameSession {

    private final SlotMachine machine;
    private SlotMachine.SpinResult result;

    public SlotsSession(List<Seat> seats, RandomGenerator random, SlotMachine machine) {
        super(seats, random);
        this.machine = Objects.requireNonNull(machine, "machine");
        if (seats.size() != 1) {
            throw new IllegalArgumentException("A slot machine seats one player");
        }
    }

    @Override
    protected void onBegin() {
        seats().getFirst().setStatus(SeatStatus.ACTIVE);
        clearTurn();
        setState(GameState.BETTING);
    }

    @Override
    public List<Action> legalActions(UUID playerId) {
        // Spin is the only move, and its lines and stake are chosen in the UI.
        return List.of();
    }

    @Override
    protected ActionResult onAction(Seat seat, Action action) {
        if (!(action instanceof SlotAction.Spin spin)) {
            return ActionResult.illegalAction();
        }
        long cost = spin.cost();
        if (cost > seat.credits()) {
            return ActionResult.insufficientCredits();
        }
        setState(GameState.IN_PROGRESS);
        seat.wager(cost);
        collectBets();
        takePot();

        result = machine.spin(random(), spin.lines());
        long won = Math.multiplyExact(spin.perLine(), (long) result.totalMultiple());
        if (won > 0) {
            seat.award(won);
        }
        finish(new Outcome(
                List.of(new Payout(seat.playerId(), won - cost)),
                won > 0 ? List.of(seat.playerId()) : List.of(),
                0,
                won > cost ? "tablegames.summary.slots.won" : "tablegames.summary.slots.lost"));
        return ActionResult.ok();
    }

    /** How the reels landed, once the spin has run. */
    public Optional<SlotMachine.SpinResult> result() {
        return Optional.ofNullable(result);
    }
}

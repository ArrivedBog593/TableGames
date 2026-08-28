package com.github.arrivedbog593.tablegames.engine.session;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * One hand in progress at one table.
 * <p>
 * Holds the machinery every game shares — seats, state, turn order, pot — and
 * leaves the rules to subclasses. A concrete game implements four hooks:
 * {@link #onBegin()}, {@link #legalActions(UUID)}, {@link #onAction(Seat,
 * Action)} and {@link #onTurnTimeout(Seat)}.
 * <p>
 * Not thread-safe. Every call must come from the server thread.
 * <p>
 * Security note: this object holds hidden information — other players' hands.
 * It must never be sent to a client wholesale. The platform layer is
 * responsible for building a per-player view that redacts what that player
 * is not entitled to see. A client that can read the deck can read the game.
 */
public abstract class GameSession {

    private final List<Seat> seats;
    private final RandomGenerator random;

    private GameState state = GameState.WAITING;
    private int turnIndex = -1;
    private long pot;
    private Outcome outcome;

    protected GameSession(List<Seat> seats, RandomGenerator random) {
        this.seats = List.copyOf(Objects.requireNonNull(seats, "seats"));
        this.random = Objects.requireNonNull(random, "random");
        if (this.seats.isEmpty()) {
            throw new IllegalArgumentException("A session needs at least one seat");
        }
    }

    // --- Hooks implemented by each game ---------------------------------

    /**
     * Sets up the hand: shuffle, deal, collect antes, and move the state out
     * of {@link GameState#WAITING}.
     */
    protected abstract void onBegin();

    /**
     * Which actions this player may legally submit right now. Drives the
     * buttons the UI offers, and is the authoritative allowlist: an action
     * absent from this list must be rejected by {@link #onAction}.
     */
    public abstract List<Action> legalActions(UUID playerId);

    /**
     * Applies a validated-as-seated, validated-as-in-turn action. The
     * subclass still has to check that the action is legal for the current
     * position; the base class only guarantees the player exists, the state
     * accepts actions, and it is their turn.
     */
    protected abstract ActionResult onAction(Seat seat, Action action);

    /**
     * What to do when a player runs out the clock. The default folds them if
     * the game supports folding; games where folding makes no sense should
     * override.
     */
    protected void onTurnTimeout(Seat seat) {
        seat.setStatus(SeatStatus.FOLDED);
        advanceTurn();
    }

    // --- Driven by the platform layer -------------------------------------

    /** Starts the hand. Callable once. */
    public final void begin() {
        if (state != GameState.WAITING) {
            throw new IllegalStateException("Session already began, state is " + state);
        }
        onBegin();
    }

    /**
     * Submits an action on a player's behalf. Never throws for bad input;
     * illegal requests come back as a rejected {@link ActionResult}.
     */
    public final ActionResult submit(UUID playerId, Action action) {
        Objects.requireNonNull(action, "action");
        if (!state.acceptsActions()) {
            return ActionResult.wrongState();
        }
        Optional<Seat> found = seatOf(playerId);
        if (found.isEmpty()) {
            return ActionResult.notSeated();
        }
        Seat seat = found.get();
        if (turnIndex >= 0 && currentSeat() != seat) {
            return ActionResult.notYourTurn();
        }
        if (!seat.status().canAct()) {
            return ActionResult.illegalAction();
        }
        return onAction(seat, action);
    }

    /** Called by the platform layer when a turn clock expires. */
    public final void timeOutCurrentTurn() {
        if (!state.acceptsActions() || turnIndex < 0) {
            return;
        }
        onTurnTimeout(currentSeat());
    }

    /**
     * How long a player gets to act, in seconds. Zero disables the clock.
     * Override for games that need more or less thinking time.
     */
    public int turnTimeoutSeconds() {
        return 30;
    }

    // --- State accessible to platform and subclasses -----------------------

    public final GameState state() {
        return state;
    }

    protected final void setState(GameState next) {
        this.state = Objects.requireNonNull(next, "next");
    }

    public final List<Seat> seats() {
        return seats;
    }

    protected final RandomGenerator random() {
        return random;
    }

    public final long pot() {
        return pot;
    }

    /** The player to act, or empty when no one is on the clock. */
    public final Optional<UUID> currentTurn() {
        return turnIndex < 0 ? Optional.empty() : Optional.of(currentSeat().playerId());
    }

    /** The settled result, present only once the hand is finished. */
    public final Optional<Outcome> outcome() {
        return Optional.ofNullable(outcome);
    }

    public final Optional<Seat> seatOf(UUID playerId) {
        for (Seat seat : seats) {
            if (seat.playerId().equals(playerId)) {
                return Optional.of(seat);
            }
        }
        return Optional.empty();
    }

    /** Seats still able to act. */
    public final List<Seat> activeSeats() {
        List<Seat> active = new ArrayList<>();
        for (Seat seat : seats) {
            if (seat.status().canAct()) {
                active.add(seat);
            }
        }
        return active;
    }

    /** Seats still eligible to win, including all-in players who cannot act. */
    public final List<Seat> contendingSeats() {
        List<Seat> contending = new ArrayList<>();
        for (Seat seat : seats) {
            if (seat.status().contestsPot()) {
                contending.add(seat);
            }
        }
        return contending;
    }

    // --- Turn machinery ---------------------------------------------------

    /** Puts the clock on the given seat. */
    protected final void setTurn(Seat seat) {
        this.turnIndex = seats.indexOf(seat);
    }

    /** Takes the clock off everyone, e.g., during dealing or showdown. */
    protected final void clearTurn() {
        this.turnIndex = -1;
    }

    protected final Seat currentSeat() {
        return seats.get(turnIndex);
    }

    /**
     * Passes the clock to the next seat that can act, wrapping around.
     * Does nothing if nobody can act.
     */
    protected final void advanceTurn() {
        int size = seats.size();
        for (int step = 1; step <= size; step++) {
            int candidate = Math.floorMod(turnIndex + step, size);
            if (seats.get(candidate).status().canAct()) {
                turnIndex = candidate;
                return;
            }
        }
        clearTurn();
    }

    // --- Pot machinery -----------------------------------------------------

    /**
     * Sweeps every seat's current bet into the pot. Call at the end of a
     * betting round.
     *
     * @return how much was swept
     */
    protected final long collectBets() {
        long collected = 0;
        for (Seat seat : seats) {
            collected += seat.collectBet();
        }
        pot += collected;
        return collected;
    }

    /** The largest wager anyone has committed this round. */
    protected final long highestBet() {
        long highest = 0;
        for (Seat seat : seats) {
            highest = Math.max(highest, seat.currentBet());
        }
        return highest;
    }

    /** Adds credits to the pot from outside the seats, e.g., a house ante. */
    protected final void addToPot(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Negative pot addition: " + amount);
        }
        pot += amount;
    }

    /** Empties the pot and returns what was in it. */
    protected final long takePot() {
        long taken = pot;
        pot = 0;
        return taken;
    }

    // --- Finishing ----------------------------------------------------------

    /**
     * Settles the hand. Moves to {@link GameState#FINISHED} and publishes the
     * outcome for the platform layer to apply against real balances.
     */
    protected final void finish(Outcome result) {
        this.outcome = Objects.requireNonNull(result, "result");
        clearTurn();
        setState(GameState.FINISHED);
    }

    /**
     * Aborts the hand, refunding every wager. Used when the table is broken
     * up mid-hand, such as a server shutdown.
     */
    public final void cancel(String reasonKey) {
        if (state.isTerminal()) {
            return;
        }
        List<Payout> refunds = new ArrayList<>();
        for (Seat seat : seats) {
            refunds.add(new Payout(seat.playerId(), 0));
            seat.award(seat.collectBet());
        }
        long remaining = takePot();
        if (remaining > 0 && !seats.isEmpty()) {
            // Anything already swept goes back to the seats it came from is not
            // recoverable here, so split it evenly rather than destroy it.
            long share = remaining / seats.size();
            for (Seat seat : seats) {
                seat.award(share);
            }
        }
        this.outcome = new Outcome(refunds, List.of(), 0, reasonKey);
        clearTurn();
        setState(GameState.CANCELLED);
    }
}
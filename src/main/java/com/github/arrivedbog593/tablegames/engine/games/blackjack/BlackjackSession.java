package com.github.arrivedbog593.tablegames.engine.games.blackjack;

import com.github.arrivedbog593.tablegames.engine.card.Card;
import com.github.arrivedbog593.tablegames.engine.card.Rank;
import com.github.arrivedbog593.tablegames.engine.card.Shoe;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * One hand of blackjack at one table, from the deal to the payout.
 * <p>
 * The wagers are down before this exists: the table takes them during its
 * betting window and hands them over with the seats, and {@link #begin()}
 * deals. From there it runs the way a dealer does —
 * <ol>
 *   <li>two cards to every seat and two to the dealer, the second face down;</li>
 *   <li>under an ace, insurance for everybody at once ({@link BlackjackStage#INSURANCE});</li>
 *   <li>under an ace or a ten, a peek, and if the dealer has blackjack the
 *       hand ends there, before anybody has doubled or split into it;</li>
 *   <li>each seat in order, each of its hands in order ({@link BlackjackStage#PLAYERS});</li>
 *   <li>the dealer, one card at a time ({@link BlackjackStage#DEALER});</li>
 *   <li>every hand paid against the dealer's.</li>
 * </ol>
 * Steps 2 and 5 are driven from outside, by {@link #closeInsurance()} and
 * {@link #dealerStep()}, because how long they take is the table's business:
 * a dealer turning cards over one at a time is pacing, not a rule.
 * <p>
 * House-banked, so the outcome is not zero-sum. Wagers are taken from each
 * seat's credits as they go down — the opening wager at the deal, a double or
 * split when asked for, insurance when taken — and what comes back is awarded
 * at the end.
 * <p>
 * The hole card is in here, and so is the shoe. Nothing in this class may be
 * sent to a client as it is; see {@link #dealerShown()}.
 */
public final class BlackjackSession extends GameSession {

    private final BlackjackRules rules;
    private final Shoe shoe;
    private final Map<UUID, Long> openingBets;

    private final Map<UUID, List<BlackjackHand>> hands = new LinkedHashMap<>();
    private final Map<UUID, Integer> handOnClock = new HashMap<>();
    private final List<Card> dealer = new ArrayList<>();
    private boolean holeShown;

    private final Map<UUID, Long> insurance = new LinkedHashMap<>();
    private final Set<UUID> insuranceAnswered = new LinkedHashSet<>();

    private BlackjackStage stage;

    /**
     * @param seats       the players dealt in, in the order they act
     * @param shoe        the table's shoe, which this hand deals from and leaves
     *                    the poorer; never reshuffled in here
     * @param openingBets each seat's wager, which its credits must cover
     */
    public BlackjackSession(List<Seat> seats, RandomGenerator random, BlackjackRules rules,
                            Shoe shoe, Map<UUID, Long> openingBets) {
        super(seats, random);
        this.rules = Objects.requireNonNull(rules, "rules");
        this.shoe = Objects.requireNonNull(shoe, "shoe");
        this.openingBets = Map.copyOf(openingBets);
        for (Seat seat : seats) {
            Long bet = this.openingBets.get(seat.playerId());
            if (bet == null || bet < 1) {
                throw new IllegalArgumentException("Seat " + seat.index() + " has no wager");
            }
            if (bet > seat.credits()) {
                throw new IllegalArgumentException("Seat " + seat.index() + " cannot cover " + bet);
            }
        }
    }

    // --- The deal -----------------------------------------------------------------------

    @Override
    protected void onBegin() {
        for (Seat seat : seats()) {
            long bet = openingBets.get(seat.playerId());
            seat.wager(bet);
            seat.setStatus(SeatStatus.ACTIVE);
            hands.put(seat.playerId(), new ArrayList<>(List.of(new BlackjackHand(bet))));
            handOnClock.put(seat.playerId(), 0);
        }
        // One card to everybody, the dealer last; then a second round the
        // same way, the dealer's face down.
        for (int round = 0; round < 2; round++) {
            for (Seat seat : seats()) {
                hands.get(seat.playerId()).getFirst().add(shoe.draw());
            }
            dealer.add(shoe.draw());
        }
        setState(GameState.IN_PROGRESS);
        clearTurn();

        if (upcard().rank() == Rank.ACE) {
            stage = BlackjackStage.INSURANCE;
            // Nobody is kept waiting on a question they cannot answer.
            for (Seat seat : seats()) {
                if (!canAffordInsurance(seat)) {
                    insuranceAnswered.add(seat.playerId());
                }
            }
            if (allInsuranceAnswered()) {
                closeInsurance();
            }
            return;
        }
        peek();
    }

    /** The dealer's face-up card. */
    public Card upcard() {
        return dealer.getFirst();
    }

    /**
     * Ends insurance, declining it for anybody who has not answered, and
     * peeks.
     * <p>
     * The table calls this when its insurance clock runs out, or when
     * {@link #allInsuranceAnswered()} says there is nobody left to wait for.
     */
    public void closeInsurance() {
        if (stage != BlackjackStage.INSURANCE) {
            return;
        }
        for (Seat seat : seats()) {
            insuranceAnswered.add(seat.playerId());
        }
        peek();
    }

    /**
     * Looks under an ace or a ten. A dealer blackjack ends the hand on the
     * spot: everybody has only their opening wager down, which is all they
     * can lose to it.
     */
    private void peek() {
        if (BlackjackHand.isNatural(dealer)) {
            holeShown = true;
            settle();
            return;
        }
        stage = BlackjackStage.PLAYERS;
        // A blackjack has nothing left to decide.
        for (Seat seat : seats()) {
            BlackjackHand first = hands.get(seat.playerId()).getFirst();
            if (first.natural()) {
                first.finish();
                seat.setStatus(SeatStatus.DONE);
            }
        }
        advanceTurn();
        if (currentTurn().isEmpty()) {
            beginDealer();
        }
    }

    // --- Moves --------------------------------------------------------------------------

    @Override
    public List<Action> legalActions(UUID playerId) {
        Optional<Seat> found = seatOf(playerId);
        if (found.isEmpty() || state() != GameState.IN_PROGRESS) {
            return List.of();
        }
        Seat seat = found.get();
        if (stage == BlackjackStage.INSURANCE) {
            if (insuranceAnswered.contains(playerId)) {
                return List.of();
            }
            return List.of(new BlackjackAction.Insurance(true), new BlackjackAction.Insurance(false));
        }
        if (stage != BlackjackStage.PLAYERS || !currentTurn().map(playerId::equals).orElse(false)) {
            return List.of();
        }
        BlackjackHand hand = handOnClock(playerId);
        List<Action> actions = new ArrayList<>();
        actions.add(new BlackjackAction.Hit());
        actions.add(new BlackjackAction.Stand());
        if (hand.canDouble() && seat.credits() >= hand.bet()) {
            actions.add(new BlackjackAction.DoubleDown());
        }
        if (hand.canSplit(hands.get(playerId).size()) && seat.credits() >= hand.bet()) {
            actions.add(new BlackjackAction.Split());
        }
        return List.copyOf(actions);
    }

    @Override
    protected ActionResult onAction(Seat seat, Action action) {
        if (!(action instanceof BlackjackAction move)) {
            return ActionResult.illegalAction();
        }
        if (stage == BlackjackStage.INSURANCE) {
            return move instanceof BlackjackAction.Insurance(boolean take)
                    ? answerInsurance(seat, take)
                    : ActionResult.wrongState();
        }
        if (stage != BlackjackStage.PLAYERS) {
            return ActionResult.wrongState();
        }
        BlackjackHand hand = handOnClock(seat.playerId());
        return switch (move) {
            case BlackjackAction.Hit hit -> hit(seat, hand);
            case BlackjackAction.Stand stand -> {
                hand.finish();
                moveOn(seat);
                yield ActionResult.ok();
            }
            case BlackjackAction.DoubleDown doubleDown -> doubleDown(seat, hand);
            case BlackjackAction.Split split -> split(seat, hand);
            case BlackjackAction.Insurance insurance -> ActionResult.wrongState();
        };
    }

    private ActionResult answerInsurance(Seat seat, boolean take) {
        if (!insuranceAnswered.add(seat.playerId())) {
            return ActionResult.rejected("tablegames.reject.blackjack.insurance_answered");
        }
        if (take) {
            if (!canAffordInsurance(seat)) {
                insuranceAnswered.remove(seat.playerId());
                return ActionResult.insufficientCredits();
            }
            long cost = insuranceCost(seat.playerId());
            seat.wager(cost);
            insurance.put(seat.playerId(), cost);
        }
        return ActionResult.ok();
    }

    private ActionResult hit(Seat seat, BlackjackHand hand) {
        hand.add(shoe.draw());
        // Twenty-one stands on its own: there is nothing better to draw to,
        // and asking would only be a way to bust it by misclicking.
        if (hand.total() >= 21) {
            hand.finish();
            moveOn(seat);
        }
        return ActionResult.ok();
    }

    private ActionResult doubleDown(Seat seat, BlackjackHand hand) {
        if (!hand.canDouble()) {
            return ActionResult.illegalAction();
        }
        if (seat.credits() < hand.bet()) {
            return ActionResult.insufficientCredits();
        }
        seat.wager(hand.bet());
        hand.doubleDown(shoe.draw());
        moveOn(seat);
        return ActionResult.ok();
    }

    private ActionResult split(Seat seat, BlackjackHand hand) {
        List<BlackjackHand> theirs = hands.get(seat.playerId());
        if (!hand.canSplit(theirs.size())) {
            return ActionResult.illegalAction();
        }
        if (seat.credits() < hand.bet()) {
            return ActionResult.insufficientCredits();
        }
        seat.wager(hand.bet());
        int at = handOnClock.get(seat.playerId());
        theirs.add(at + 1, hand.splitOff());
        // The hand on the clock gets its second card now; the new one gets
        // its own when play reaches it, as a dealer would deal them.
        dealSecondCard(hand);
        if (hand.finished()) {
            moveOn(seat);
        }
        return ActionResult.ok();
    }

    /**
     * Brings a split hand up to two cards, and finishes it when that is all
     * it may have: split aces, or twenty-one.
     */
    private void dealSecondCard(BlackjackHand hand) {
        hand.add(shoe.draw());
        if (hand.splitAces() || hand.total() == 21) {
            hand.finish();
        }
    }

    /**
     * Moves the clock to this seat's next unfinished hand, or past the seat
     * when it has none, or to the dealer when nobody has any.
     */
    private void moveOn(Seat seat) {
        List<BlackjackHand> theirs = hands.get(seat.playerId());
        for (int next = handOnClock.get(seat.playerId()) + 1; next < theirs.size(); next++) {
            BlackjackHand hand = theirs.get(next);
            if (hand.cards().size() == 1) {
                dealSecondCard(hand);
            }
            if (!hand.finished()) {
                handOnClock.put(seat.playerId(), next);
                return;
            }
        }
        seat.setStatus(SeatStatus.DONE);
        advanceTurn();
        if (currentTurn().isEmpty()) {
            beginDealer();
        }
    }

    /** Running out the clock stands the hand, which is what a dealer does with a silent player. */
    @Override
    protected void onTurnTimeout(Seat seat) {
        if (stage != BlackjackStage.PLAYERS) {
            return;
        }
        handOnClock(seat.playerId()).finish();
        moveOn(seat);
    }

    // --- The dealer ---------------------------------------------------------------------

    private void beginDealer() {
        clearTurn();
        stage = BlackjackStage.DEALER;
    }

    /**
     * The dealer's next move: turn the hole card, draw one, or — when there is
     * nothing left to do — pay everybody.
     * <p>
     * One card per call, so the table can show each before the next comes. The
     * dealer stops drawing at seventeen, or at a hard one when the table hits
     * soft seventeens, and does not draw at all once every hand is busted or a
     * blackjack: there is nothing left for it to beat.
     */
    public void dealerStep() {
        if (stage != BlackjackStage.DEALER) {
            return;
        }
        if (!holeShown) {
            holeShown = true;
            return;
        }
        if (dealerDraws()) {
            dealer.add(shoe.draw());
            return;
        }
        settle();
    }

    /** Plays the dealer to the end at once. For tests, and for a table in a hurry. */
    public void playDealerOut() {
        while (stage == BlackjackStage.DEALER) {
            dealerStep();
        }
    }

    private boolean dealerDraws() {
        if (!anyHandStillLive()) {
            return false;
        }
        int total = BlackjackHand.totalOf(dealer);
        if (total < 17) {
            return true;
        }
        return total == 17 && rules.dealerHitsSoft17() && BlackjackHand.isSoft(dealer);
    }

    private boolean anyHandStillLive() {
        for (List<BlackjackHand> theirs : hands.values()) {
            for (BlackjackHand hand : theirs) {
                if (!hand.busted() && !hand.natural()) {
                    return true;
                }
            }
        }
        return false;
    }

    // --- Paying -------------------------------------------------------------------------

    private void settle() {
        boolean dealerNatural = BlackjackHand.isNatural(dealer);
        int dealerTotal = BlackjackHand.totalOf(dealer);
        stage = BlackjackStage.SETTLED;

        List<Payout> payouts = new ArrayList<>();
        List<UUID> winners = new ArrayList<>();
        for (Seat seat : seats()) {
            UUID playerId = seat.playerId();
            long wagered = seat.collectBet();
            long returned = 0;
            for (BlackjackHand hand : hands.get(playerId)) {
                returned += returnOn(hand, dealerNatural, dealerTotal);
            }
            long insured = insurance.getOrDefault(playerId, 0L);
            if (dealerNatural && insured > 0) {
                // Two to one, and the stake back.
                returned += insured * 3;
            }
            if (returned > 0) {
                seat.award(returned);
            }
            long delta = returned - wagered;
            payouts.add(new Payout(playerId, delta));
            if (delta > 0) {
                winners.add(playerId);
            }
            seat.setStatus(SeatStatus.DONE);
        }
        finish(new Outcome(payouts, winners, 0, summaryKey(dealerNatural, dealerTotal)));
    }

    /** What one hand gets back, its own wager included. */
    private long returnOn(BlackjackHand hand, boolean dealerNatural, int dealerTotal) {
        long bet = hand.bet();
        if (hand.busted()) {
            return 0;
        }
        if (hand.natural()) {
            return dealerNatural ? bet : bet + rules.naturalPayout().winOn(bet);
        }
        if (dealerNatural) {
            return 0;
        }
        if (dealerTotal > 21 || hand.total() > dealerTotal) {
            return bet * 2;
        }
        return hand.total() == dealerTotal ? bet : 0;
    }

    private static String summaryKey(boolean dealerNatural, int dealerTotal) {
        if (dealerNatural) {
            return "tablegames.summary.blackjack.dealer_blackjack";
        }
        if (dealerTotal > 21) {
            return "tablegames.summary.blackjack.dealer_bust";
        }
        return "tablegames.summary.blackjack.dealer_stands";
    }

    // --- Insurance ----------------------------------------------------------------------

    /** Half the opening wager, rounded down; zero means it cannot be offered. */
    public long insuranceCost(UUID playerId) {
        return openingBets.getOrDefault(playerId, 0L) / 2;
    }

    private boolean canAffordInsurance(Seat seat) {
        long cost = insuranceCost(seat.playerId());
        return cost > 0 && seat.credits() >= cost;
    }

    public boolean allInsuranceAnswered() {
        for (Seat seat : seats()) {
            if (!insuranceAnswered.contains(seat.playerId())) {
                return false;
            }
        }
        return true;
    }

    public boolean answeredInsurance(UUID playerId) {
        return insuranceAnswered.contains(playerId);
    }

    /** What this player put on insurance, zero when they did not take it. */
    public long insuranceOf(UUID playerId) {
        return insurance.getOrDefault(playerId, 0L);
    }

    // --- Reading it ---------------------------------------------------------------------

    public BlackjackStage stage() {
        return stage;
    }

    public BlackjackRules rules() {
        return rules;
    }

    /**
     * The dealer's cards anybody may see: the upcard alone until the hole
     * card is turned, then all of them.
     * <p>
     * The only way the dealer's hand leaves this class. A client that is sent
     * the hole card can read it, whatever the screen draws.
     */
    public List<Card> dealerShown() {
        return holeShown ? List.copyOf(dealer) : List.of(upcard());
    }

    /** Whether a card is lying face down in front of the dealer. */
    public boolean holeHidden() {
        return !holeShown;
    }

    /** This player's hands, in the order they are played. Empty for nobody dealt in. */
    public List<BlackjackHand> handsOf(UUID playerId) {
        return Collections.unmodifiableList(hands.getOrDefault(playerId, List.of()));
    }

    /** Which of this player's hands is on the clock, or is next to be. */
    public int handOnClockIndex(UUID playerId) {
        return handOnClock.getOrDefault(playerId, 0);
    }

    private BlackjackHand handOnClock(UUID playerId) {
        return hands.get(playerId).get(handOnClock.get(playerId));
    }

    /**
     * What the house stands to lose on the hand as it lies: every live hand
     * paid, blackjacks at their rate, and insurance at two to one.
     * <p>
     * Grows when a double or a split puts another wager down. The table asks
     * it before letting either happen, adding {@link #nextWagerOnClock()}.
     */
    public long worstCaseHouseCost() {
        if (stage == BlackjackStage.SETTLED) {
            return 0;
        }
        long worst = 0;
        for (Map.Entry<UUID, List<BlackjackHand>> entry : hands.entrySet()) {
            for (BlackjackHand hand : entry.getValue()) {
                if (hand.busted()) {
                    continue;
                }
                worst += hand.natural() ? rules.worstCaseOn(hand.bet()) : hand.bet();
            }
            worst += insurance.getOrDefault(entry.getKey(), 0L) * 2;
        }
        return worst;
    }

    /** What a double or a split by whoever is on the clock would put down; zero for nobody. */
    public long nextWagerOnClock() {
        if (stage != BlackjackStage.PLAYERS) {
            return 0;
        }
        return currentTurn().map(this::handOnClock).map(BlackjackHand::bet).orElse(0L);
    }

    /** What this player has down on the hand: every wager and insurance together. */
    public long wageredBy(UUID playerId) {
        long total = insurance.getOrDefault(playerId, 0L);
        for (BlackjackHand hand : hands.getOrDefault(playerId, List.of())) {
            total += hand.bet();
        }
        return total;
    }
}

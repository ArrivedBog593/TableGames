package com.github.arrivedbog593.tablegames.client;

import com.github.arrivedbog593.tablegames.engine.games.blackjack.BlackjackFormat;
import com.github.arrivedbog593.tablegames.platform.network.BlackjackStatePayload;
import com.github.arrivedbog593.tablegames.platform.network.PlayerFunds;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The blackjack table the server last described, for the screen to draw.
 * <p>
 * Client only and without authority, like every other game's. What it adds is
 * timing: the server sends a deal as one packet and then waits for it to be
 * seen, so this decides when each card of it lands — one every quarter of a
 * second, in the order a dealer would deal them — and the screen draws only
 * the cards that have. A card that arrives later, from a hit or from the
 * dealer, lands as it arrives; the server has already spaced those out.
 * <p>
 * The credits are held while the deal is still landing. A dealer blackjack
 * is settled the moment the dealer peeks, and a stack that dropped before the
 * hole card was on screen would say so first.
 */
public final class ClientBlackjackState {

    /** Between two cards of the deal, matching the server's pause for it. */
    static final long DEAL_CARD_MILLIS = 250;

    private static BlackjackStatePayload state = BlackjackStatePayload.idle();

    /** When each card on the felt lands, by where it lies. */
    private static final Map<String, Long> landsAt = new HashMap<>();

    /** When the last card of the deal lands; the funds are held until then. */
    private static long dealLandsAt;

    private static PlayerFunds fundsBeforeDeal = BlackjackStatePayload.idle().funds();

    /**
     * What the viewer opened with on their last hand, for "repeat", by
     * format: tournament chips are not credits, and a wager remembered from
     * one must not be offered at the other.
     */
    private static final Map<BlackjackFormat, Long> lastBets = new EnumMap<>(BlackjackFormat.class);

    private ClientBlackjackState() {
    }

    /**
     * Forgets the last table, for a screen opening on one. Whatever it shows
     * first comes from that table's own packet, never from another's.
     * "Repeat" survives: it is what this player bet at any table of the format.
     */
    static void reset() {
        state = BlackjackStatePayload.idle();
        landsAt.clear();
        dealLandsAt = 0;
    }

    public static void accept(BlackjackStatePayload payload) {
        long now = System.currentTimeMillis();
        BlackjackStatePayload.DealView deal = payload.deal();
        // A table seen for the first time — just opened, or a different one —
        // with a hand already out is not a deal anybody watched happen. It
        // is shown as it lies.
        boolean sameTable = state.table().maxSeats() > 0;
        if (!deal.hasHand()) {
            landsAt.clear();
        } else if (!sameTable) {
            landsAt.clear();
            dealLandsAt = 0;
        } else if (!state.deal().hasHand()) {
            // A fresh deal: every card on it lands in dealing order. A player
            // holding the bank sits at the table but is dealt the dealer's
            // cards, not a seat's.
            fundsBeforeDeal = state.funds();
            List<BlackjackStatePayload.SeatHands> dealtIn = deal.seats().stream()
                    .filter(seat -> !seat.hands().isEmpty()).toList();
            int seats = dealtIn.size();
            for (int order = 0; order < seats; order++) {
                int seatIndex = dealtIn.get(order).seatIndex();
                landsAt.put(key(seatIndex, 0, 0), now + order * DEAL_CARD_MILLIS);
                landsAt.put(key(seatIndex, 0, 1), now + (seats + 1 + order) * DEAL_CARD_MILLIS);
            }
            landsAt.put(dealerKey(0), now + seats * DEAL_CARD_MILLIS);
            landsAt.put(dealerKey(1), now + (2L * seats + 1) * DEAL_CARD_MILLIS);
            dealLandsAt = now + (2L * seats + 2) * DEAL_CARD_MILLIS;
            payload.mySpot().filter(spot -> !spot.hands().isEmpty()).ifPresent(spot ->
                    lastBets.put(payload.blackjackFormat(), spot.hands().getFirst().bet()));
        }
        // Anything not yet timed lands now: a hit, a split card, the dealer's.
        if (deal.hasHand()) {
            for (int i = 0; i < deal.dealer().size(); i++) {
                landsAt.putIfAbsent(dealerKey(i), now);
            }
            for (BlackjackStatePayload.SeatHands seat : deal.seats()) {
                List<BlackjackStatePayload.HandView> hands = seat.hands();
                for (int hand = 0; hand < hands.size(); hand++) {
                    for (int card = 0; card < hands.get(hand).cards().size(); card++) {
                        landsAt.putIfAbsent(key(seat.seatIndex(), hand, card), now);
                    }
                }
            }
        }
        state = payload;
    }

    private static String key(int seat, int hand, int card) {
        return seat + ":" + hand + ":" + card;
    }

    private static String dealerKey(int card) {
        return "dealer:" + card;
    }

    /** Whether this seat's card has landed and may be drawn. */
    public static boolean landed(int seat, int hand, int card) {
        return landsAt.getOrDefault(key(seat, hand, card), 0L) <= System.currentTimeMillis();
    }

    /** Whether the dealer's card in this position — the hole card is 1 — has landed. */
    public static boolean dealerLanded(int card) {
        return landsAt.getOrDefault(dealerKey(card), 0L) <= System.currentTimeMillis();
    }

    /** Whether the deal is still going out on screen. */
    public static boolean dealing() {
        return state.deal().hasHand() && System.currentTimeMillis() < dealLandsAt;
    }

    public static BlackjackStatePayload state() {
        return state;
    }

    /** The viewer's credits as the header should print them: held while the deal lands. */
    public static PlayerFunds funds() {
        return dealing() ? fundsBeforeDeal : state.funds();
    }

    public static long lastBet(BlackjackFormat format) {
        return lastBets.getOrDefault(format, 0L);
    }
}

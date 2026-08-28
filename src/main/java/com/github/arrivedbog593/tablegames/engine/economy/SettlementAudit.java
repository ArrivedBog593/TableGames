package com.github.arrivedbog593.tablegames.engine.economy;

import com.github.arrivedbog593.tablegames.engine.session.Outcome;
import com.github.arrivedbog593.tablegames.engine.session.Payout;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The gate every finished hand passes through before a single credit moves.
 * <p>
 * A game engine produces an {@link Outcome}: who gained, who lost, what the
 * house kept. Nothing stops that outcome from being wrong — a rules bug, a
 * bad payout table, an arithmetic slip — and a wrong outcome applied to real
 * balances is credits invented from nothing. This checks the arithmetic
 * before it becomes money.
 * <p>
 * Two rules do the work:
 * <ul>
 *   <li>A player-versus-player hand must be exactly zero sum. Chips only
 *       change owner; the house takes the declared rake and not one credit
 *       more. Anything else means value appeared or vanished.</li>
 *   <li>A house-banked hand may pay out, but only what the bankroll actually
 *       holds. Paying from a balance that cannot cover it is the same
 *       invention wearing a different hat.</li>
 * </ul>
 * Pure arithmetic with no dependencies, so both rules are unit tested rather
 * than hoped for.
 */
public final class SettlementAudit {

    private SettlementAudit() {
    }

    /**
     * The ruling on one outcome.
     *
     * @param approved   whether credits may move
     * @param reasonKey  translation key explaining a refusal, null when approved
     * @param houseDelta what the house gains, or loses when negative
     * @param shortfall  credits the house was short, zero unless that was the reason
     */
    public record Verdict(boolean approved, String reasonKey, long houseDelta, long shortfall) {

        static Verdict approve(long houseDelta) {
            return new Verdict(true, null, houseDelta, 0);
        }

        static Verdict reject(String reasonKey) {
            return new Verdict(false, reasonKey, 0, 0);
        }

        static Verdict rejectShort(long shortfall) {
            return new Verdict(false, "tablegames.settle.house_cannot_cover", 0, shortfall);
        }

        /** True when the house has to pay out of its own funds. */
        public boolean housePays() {
            return houseDelta < 0;
        }
    }

    /**
     * Checks an outcome.
     *
     * @param outcome     what the game decided
     * @param houseBanked whether this game is allowed to move the house's own money
     * @param bankroll    the house's funds as they stand right now
     */
    public static Verdict audit(Outcome outcome, boolean houseBanked, HouseBankroll bankroll) {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(bankroll, "bankroll");

        Set<UUID> paid = new HashSet<>();
        for (Payout payout : outcome.payouts()) {
            if (!paid.add(payout.playerId())) {
                // Two entries for one player would apply twice, or silently
                // drop one depending on how the settler iterates.
                return Verdict.reject("tablegames.settle.duplicate_payout");
            }
        }

        for (UUID winner : outcome.winners()) {
            if (!paid.contains(winner)) {
                return Verdict.reject("tablegames.settle.winner_not_paid");
            }
        }

        long playerSum;
        try {
            playerSum = 0;
            for (Payout payout : outcome.payouts()) {
                playerSum = Math.addExact(playerSum, payout.delta());
            }
        } catch (ArithmeticException overflow) {
            // Numbers this large mean something is badly wrong upstream, and a
            // wrapped total would look perfectly reasonable.
            return Verdict.reject("tablegames.settle.arithmetic_overflow");
        }

        // Whatever the players did not keep, the house did. This identity is
        // what makes the whole settlement conservative by construction.
        long houseDelta = -playerSum;

        if (!houseBanked && outcome.netCreditChange() != 0) {
            return Verdict.reject("tablegames.settle.not_zero_sum");
        }

        if (houseDelta < 0 && !bankroll.canCover(-houseDelta)) {
            return Verdict.rejectShort(-houseDelta - bankroll.balance());
        }

        return Verdict.approve(houseDelta);
    }
}
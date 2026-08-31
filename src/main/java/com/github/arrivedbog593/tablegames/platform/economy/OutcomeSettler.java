package com.github.arrivedbog593.tablegames.platform.economy;

import com.github.arrivedbog593.tablegames.engine.economy.CreditAccount;
import com.github.arrivedbog593.tablegames.engine.economy.HouseBankroll;
import com.github.arrivedbog593.tablegames.engine.economy.HouseExposure;
import com.github.arrivedbog593.tablegames.engine.economy.SettlementAudit;
import com.github.arrivedbog593.tablegames.engine.economy.TransactionType;
import com.github.arrivedbog593.tablegames.engine.game.Game;
import com.github.arrivedbog593.tablegames.engine.session.Outcome;
import com.github.arrivedbog593.tablegames.engine.session.Payout;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a finished hand into real credits.
 * <p>
 * The one place where a game's arithmetic becomes money, and therefore the
 * one place worth being paranoid. Every settlement is audited first, checked
 * against real balances second, and only then applied — and applied all at
 * once, so no hand can end with half the pot distributed.
 * <p>
 * A refused settlement is not a crash. The caller cancels the hand and
 * refunds instead, which leaves everyone exactly as they started. Losing a
 * hand to a bug is annoying; losing credits to one is unforgivable.
 * <p>
 * Server thread only.
 */
public final class OutcomeSettler {

    private static final Logger LOGGER = LogUtils.getLogger();

    private OutcomeSettler() {
    }

    /**
     * What happened when a hand was settled.
     *
     * @param applied    whether credits moved
     * @param reasonKey  translation key explaining a refusal, null on success
     * @param houseDelta what the house gained, or paid when negative
     * @param shortfall  credits the house was short, when that was the reason
     */
    public record Result(boolean applied, String reasonKey, long houseDelta, long shortfall) {

        public static Result refused(String reasonKey, long shortfall) {
            return new Result(false, reasonKey, 0, shortfall);
        }

        public static Result applied(long houseDelta) {
            return new Result(true, null, houseDelta, 0);
        }
    }

    /**
     * Settles a finished hand.
     *
     * @param game    the game that produced it, for the house-banked rule
     * @param outcome what the engine decided
     * @param detail  free text for the audit log: the table, the hand
     * @return whether it was applied; on refusal the caller must cancel and
     *         refund rather than retry
     */
    public static Result settle(MinecraftServer server, Game game,
                                Outcome outcome, String detail) {
        CreditStorage storage = CreditStorage.get(server);
        HouseBankroll bankroll = storage.bankroll(server);

        SettlementAudit.Verdict verdict =
                SettlementAudit.audit(outcome, game.isHouseBanked(), bankroll);

        if (!verdict.approved()) {
            LOGGER.error("[Economy] Refused to settle a hand of {}: {}. Nothing was moved. "
                            + "Context: {}",
                    game.id(), verdict.reasonKey(), detail);
            return Result.refused(verdict.reasonKey(), verdict.shortfall());
        }

        // Balances can have moved since the hand began — a player might have
        // spent at the shop from another screen — so what the seats believed
        // is checked against what the accounts actually hold before anything
        // is applied.
        List<Payout> debits = new ArrayList<>();
        List<Payout> credits = new ArrayList<>();
        for (Payout payout : outcome.payouts()) {
            if (payout.delta() < 0) {
                long owed = -payout.delta();
                if (storage.balanceOf(payout.playerId()) < owed) {
                    LOGGER.error("[Economy] Refused to settle a hand of {}: {} owes {} but "
                                    + "holds {}. Nothing was moved.",
                            game.id(), payout.playerId(), owed,
                            storage.balanceOf(payout.playerId()));
                    return Result.refused("tablegames.settle.player_cannot_cover", owed);
                }
                debits.add(payout);
            } else if (payout.delta() > 0) {
                // The balance cap is checked here rather than at the moment of
                // paying, because by then the losers have already been debited
                // and there is no clean way back. A deposit that would breach
                // the cap does nothing and returns false, so a winner who
                // cannot be paid has to stop the whole settlement, not just
                // their own line of it.
                if (!CreditAccount.canHold(storage.balanceOf(payout.playerId()),
                        payout.delta())) {
                    LOGGER.error("[Economy] Refused to settle a hand of {}: paying {} would "
                                    + "breach the balance cap. Nothing was moved.",
                            game.id(), payout.playerId());
                    return Result.refused("tablegames.settle.player_cap_reached", 0);
                }
                credits.add(payout);
            }
        }

        // Take before giving. If the order were reversed, a winner could be
        // paid from credits a loser turns out not to have.
        for (Payout payout : debits) {
            storage.withdraw(payout.playerId(), -payout.delta());
        }

        if (verdict.housePays() && !storage.debitHouse(-verdict.houseDelta())) {
            // The audit already checked this, so reaching here means the
            // bankroll moved underneath us. Undo the debits and refuse.
            for (Payout payout : debits) {
                storage.deposit(payout.playerId(), -payout.delta());
            }
            LOGGER.error("[Economy] The bankroll moved mid-settlement for a hand of {}. "
                    + "Rolled back.", game.id());
            return Result.refused("tablegames.settle.house_cannot_cover", 0);
        }

        for (Payout payout : credits) {
            if (!storage.deposit(payout.playerId(), payout.delta())) {
                // Pre-checked above, so this is unreachable unless something
                // moved the balance from another thread. Loud, because a
                // winner silently not paid is the worst failure this class has.
                LOGGER.error("[Economy] Paid nothing to {} in a hand of {}: the deposit was "
                                + "refused after the cap check passed. Balance {}, owed {}.",
                        payout.playerId(), game.id(),
                        storage.balanceOf(payout.playerId()), payout.delta());
            }
        }

        if (verdict.houseDelta() > 0) {
            storage.creditHouse(verdict.houseDelta());
        }

        // Every credit has moved. Only now does any of it reach the log.
        //
        // Writing a line the moment a balance changes looks safer and is the
        // opposite: EconomyEvents.record also advances lastSequence, and BET
        // is a recoverable type. A settlement that wrote its debits and then
        // rolled them back would leave the log claiming movements that never
        // happened, and startup recovery would faithfully reapply them. The
        // log describes settlements that are completed, or it describes nothing.
        String context = game.id() + " " + detail;
        for (Payout payout : debits) {
            EconomyEvents.record(storage, TransactionType.BET, payout.playerId(),
                    payout.delta(), storage.balanceOf(payout.playerId()), context);
        }
        for (Payout payout : credits) {
            EconomyEvents.record(storage, TransactionType.PAYOUT, payout.playerId(),
                    payout.delta(), storage.balanceOf(payout.playerId()), context);
        }
        if (verdict.houseDelta() > 0) {
            EconomyEvents.recordHouse(storage,
                    outcome.rake() > 0 ? TransactionType.RAKE : TransactionType.PAYOUT,
                    verdict.houseDelta(), storage.houseBalance(), context);
        } else if (verdict.housePays()) {
            EconomyEvents.recordHouse(storage, TransactionType.HOUSE_PAYOUT,
                    verdict.houseDelta(), storage.houseBalance(), context);
        }

        return Result.applied(verdict.houseDelta());
    }

    /**
     * Whether a house-banked table may open at all.
     * <p>
     * Checked before dealing rather than before paying: telling somebody the
     * casino is closed costs nothing, telling them their winnings cannot be
     * paid costs a player.
     */
    public static boolean canOpen(MinecraftServer server, Game game) {
        if (!game.isHouseBanked()) {
            return true;
        }
        return CreditStorage.get(server).bankroll(server).isOpen();
    }

    /**
     * What the house is currently on the hook for, across every open table.
     * <p>
     * Static and in memory, like the rest of the round state it mirrors.
     * Commitments cannot outlive the rounds that made them, and rounds are
     * never saved, so a registry that starts empty after a restart is always
     * telling the truth.
     */
    private static final HouseExposure EXPOSURE = new HouseExposure();

    public static HouseExposure exposure() {
        return EXPOSURE;
    }

    /**
     * Whether a table may take on a given worst-case liability.
     * <p>
     * The check {@link #tableMaximum} cannot make. That one caps a single
     * position on a single table; this one is the total, and without it eight
     * tables each staying under their own limit could together commit eight
     * times what the bankroll was ever meant to risk.
     */
    public static boolean withinExposure(MinecraftServer server, Game game,
                                         String tableKey, long worstCase) {
        if (!game.isHouseBanked()) {
            return true;
        }
        HouseBankroll bankroll = CreditStorage.get(server).bankroll(server);
        return EXPOSURE.fits(tableKey, worstCase, bankroll.maximumExposure());
    }

    /** Records what a table now stands to lose. */
    public static void commitExposure(String tableKey, long worstCase) {
        EXPOSURE.commit(tableKey, worstCase);
    }

    /** Frees a table's share of the exposure when its round ends. */
    public static void releaseExposure(String tableKey) {
        EXPOSURE.release(tableKey);
    }

    /**
     * The largest wager a table may accept right now.
     * <p>
     * Derived from the bankroll, so a casino that is losing tightens its own
     * limits without anyone having to notice.
     *
     * @param bestPayoutRatio the game's most generous payout, to one
     */
    public static long tableMaximum(MinecraftServer server, Game game, int bestPayoutRatio) {
        if (!game.isHouseBanked()) {
            // Nothing to protect: player-versus-player wagers only move chips
            // between seats.
            return Long.MAX_VALUE;
        }
        return CreditStorage.get(server).bankroll(server).maximumBet(bestPayoutRatio);
    }
}
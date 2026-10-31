package com.github.arrivedbog593.tablegames.platform.house;

import com.github.arrivedbog593.economycore.api.Bank;
import com.github.arrivedbog593.economycore.api.Credits;
import com.github.arrivedbog593.economycore.api.PlayerCommitments;
import com.github.arrivedbog593.economycore.api.Settlement;
import com.github.arrivedbog593.tablegames.engine.game.Game;
import com.github.arrivedbog593.tablegames.engine.house.HouseBankroll;
import com.github.arrivedbog593.tablegames.engine.house.HouseExposure;
import com.github.arrivedbog593.tablegames.engine.house.HouseTransactions;
import com.github.arrivedbog593.tablegames.engine.house.SettlementAudit;
import com.github.arrivedbog593.tablegames.engine.session.Outcome;
import com.github.arrivedbog593.tablegames.engine.session.Payout;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.util.UUID;

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
        HouseBankroll bankroll = bankroll(server);

        SettlementAudit.Verdict verdict =
                SettlementAudit.audit(outcome, game.isHouseBanked(), bankroll);

        if (!verdict.approved()) {
            LOGGER.error("[Economy] Refused to settle a hand of {}: {}. Nothing was moved. "
                            + "Context: {}",
                    game.id(), verdict.reasonKey(), detail);
            return Result.refused(verdict.reasonKey(), verdict.shortfall());
        }

        // The hand as the economy's movements: what each player lost, what
        // each won, and the house's side of it. Balances can have moved since
        // the hand began — a player might have spent at the shop from another
        // screen — and the settlement checks every account against what it
        // actually holds before anything moves, takes before it pays, and
        // logs only once all of it has moved.
        Settlement settlement = Settlement.create();
        for (Payout payout : outcome.payouts()) {
            if (payout.delta() < 0) {
                settlement.move(payout.playerId(), payout.delta(), HouseTransactions.BET);
            }
        }
        for (Payout payout : outcome.payouts()) {
            if (payout.delta() > 0) {
                settlement.move(payout.playerId(), payout.delta(), HouseTransactions.PAYOUT);
            }
        }
        if (verdict.houseDelta() > 0) {
            settlement.move(Bank.ACCOUNT, verdict.houseDelta(),
                    outcome.rake() > 0 ? HouseTransactions.RAKE : HouseTransactions.PAYOUT);
        } else if (verdict.housePays()) {
            settlement.move(Bank.ACCOUNT, verdict.houseDelta(), HouseTransactions.HOUSE_PAYOUT);
        }

        Settlement.Result moved = settlement.apply(server, game.id() + " " + detail);
        if (!moved.applied()) {
            LOGGER.error("[Economy] Refused to settle a hand of {}: {} for {} (short {}). "
                            + "Nothing was moved. Context: {}",
                    game.id(), moved.refusal(), moved.account(), moved.shortfall(), detail);
            return Result.refused(refusalKey(moved), moved.shortfall());
        }
        return Result.applied(verdict.houseDelta());
    }

    /** Why the economy refused, in the words a table shows its players. */
    private static String refusalKey(Settlement.Result refused) {
        if (refused.refusal() == Settlement.Refusal.BALANCE_CAP) {
            return "tablegames.settle.player_cap_reached";
        }
        return Bank.ACCOUNT.equals(refused.account())
                ? "tablegames.settle.house_cannot_cover"
                : "tablegames.settle.player_cannot_cover";
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
        return bankroll(server).isOpen();
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
     * The house's bank, with the risk settings this server set for it, which
     * is what every table limit is derived from.
     * <p>
     * The money is the economy's; the rules for risking it are the casino's.
     * The configured reserve is a floor, not the final figure: it is raised
     * here to whatever the tables have riding on rounds that have not settled
     * yet. A house that cannot cover the promises already made should not be
     * taking new ones, and asking the live exposure every time means the
     * answer is about this minute rather than about whatever number somebody
     * typed when the casino opened.
     */
    public static HouseBankroll bankroll(MinecraftServer server) {
        HouseSettings settings = HouseSettings.get(server);
        return new HouseBankroll(Bank.balance(server),
                settings.exposurePercent(), settings.minimumReserve())
                .withReserveAtLeast(EXPOSURE.total());
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
        HouseBankroll bankroll = bankroll(server);
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
     * What every player has riding on a round that has not settled yet.
     * <p>
     * The mirror of {@link #EXPOSURE}: that one keeps the tables from
     * promising the same bankroll twice, this one keeps a player from
     * promising the same credits twice. It is the economy's registry, not
     * one of the casino's own, because the cashier and the shop have to
     * honor it too: a stake is one kind of promise among others.
     */
    public static PlayerCommitments stakes() {
        return Credits.commitments();
    }

    /** Records what one player now has on one table. Zero releases it. */
    public static void commitStake(String tableKey, UUID playerId, long staked) {
        stakes().commit(playerId, tableKey, staked);
    }

    /** Frees what everybody had on a table when its round ends. */
    public static void releaseStakes(String tableKey) {
        stakes().release(tableKey);
    }

    /**
     * What this player may spend without touching a live wager.
     * <p>
     * What every spender asks instead of asking for the balance. Wagers are
     * not debited when they are placed, so the balance still counts credits
     * that are already promised to a spin.
     */
    public static long spendable(MinecraftServer server, UUID playerId) {
        return Credits.spendable(server, playerId);
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
        return bankroll(server).maximumBet(bestPayoutRatio);
    }
}

package com.github.arrivedbog593.tablegames.platform.economy;

import com.github.arrivedbog593.tablegames.engine.economy.HouseBankroll;
import com.github.arrivedbog593.tablegames.engine.economy.PlayerCommitments;
import com.github.arrivedbog593.tablegames.engine.economy.SandboxLedger;
import com.github.arrivedbog593.tablegames.engine.economy.SettlementAudit;
import com.github.arrivedbog593.tablegames.engine.game.Game;
import com.github.arrivedbog593.tablegames.engine.session.Outcome;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;

/**
 * One test table's pretend economy.
 * <p>
 * Answers every question a table asks from its own {@link SandboxLedger}:
 * its own bank, its own balances, its own record of what is committed. The
 * one thing it borrows from the real house is the policy — how much of a
 * bank may be at risk, what reserve it keeps — so that ten million in a
 * test bank sets exactly the limits ten million in the real one would.
 * Borrowed by reading; nothing here ever writes to the real economy.
 * <p>
 * Never persisted. A test table that survived a restart would be a table
 * nobody remembered setting up, so a reload always brings it back real.
 */
public final class SandboxFunds implements TableFunds {

    private final SandboxLedger ledger;

    public SandboxFunds(long bank, long startingBalance) {
        this.ledger = new SandboxLedger(bank, startingBalance);
    }

    /** What the pretend house holds now. */
    public long bank() {
        return ledger.bank();
    }

    /** What each player started with. */
    public long startingBalance() {
        return ledger.startingBalance();
    }

    private HouseBankroll policy(MinecraftServer server) {
        return CreditStorage.get(server).bankroll(server);
    }

    private HouseBankroll bankroll(MinecraftServer server) {
        return ledger.bankroll(policy(server));
    }

    @Override
    public boolean isSandbox() {
        return true;
    }

    @Override
    public long balanceOf(MinecraftServer server, UUID playerId) {
        return ledger.balanceOf(playerId);
    }

    @Override
    public PlayerCommitments stakes() {
        return ledger.stakes();
    }

    @Override
    public boolean canOpen(MinecraftServer server, Game game) {
        return !game.isHouseBanked() || bankroll(server).isOpen();
    }

    @Override
    public long tableMaximum(MinecraftServer server, Game game, int bestPayoutRatio) {
        return game.isHouseBanked() ? bankroll(server).maximumBet(bestPayoutRatio) : Long.MAX_VALUE;
    }

    @Override
    public boolean withinExposure(MinecraftServer server, Game game, String tableKey, long worstCase) {
        return !game.isHouseBanked()
                || ledger.exposure().fits(tableKey, worstCase, bankroll(server).maximumExposure());
    }

    @Override
    public void commitExposure(String tableKey, long worstCase) {
        ledger.exposure().commit(tableKey, worstCase);
    }

    @Override
    public void releaseExposure(String tableKey) {
        ledger.exposure().release(tableKey);
    }

    @Override
    public void commitStake(String tableKey, UUID playerId, long staked) {
        ledger.stakes().commit(playerId, tableKey, staked);
    }

    @Override
    public void releaseStakes(String tableKey) {
        ledger.stakes().release(tableKey);
    }

    @Override
    public OutcomeSettler.Result settle(MinecraftServer server, Game game, Outcome outcome, String detail) {
        SettlementAudit.Verdict verdict = ledger.settle(outcome, game.isHouseBanked(), policy(server));
        return verdict.approved()
                ? OutcomeSettler.Result.applied(verdict.houseDelta())
                : OutcomeSettler.Result.refused(verdict.reasonKey(), verdict.shortfall());
    }
}

package com.github.arrivedbog593.tablegames.platform.economy;

import com.github.arrivedbog593.tablegames.engine.economy.PlayerCommitments;
import com.github.arrivedbog593.tablegames.engine.game.Game;
import com.github.arrivedbog593.tablegames.engine.session.Outcome;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;

/**
 * The real economy: the server's bankroll, the players' balances, the shared
 * registries, the transaction log.
 * <p>
 * Nothing but a pass-through. Every table used to ask these things of
 * {@link OutcomeSettler} and {@link CreditStorage} directly, and still gets
 * exactly the same answers, from exactly the same places.
 */
public enum RealFunds implements TableFunds {

    INSTANCE;

    @Override
    public boolean isSandbox() {
        return false;
    }

    @Override
    public long balanceOf(MinecraftServer server, UUID playerId) {
        return CreditStorage.get(server).balanceOf(playerId);
    }

    @Override
    public PlayerCommitments stakes() {
        return OutcomeSettler.stakes();
    }

    @Override
    public boolean canOpen(MinecraftServer server, Game game) {
        return OutcomeSettler.canOpen(server, game);
    }

    @Override
    public long tableMaximum(MinecraftServer server, Game game, int bestPayoutRatio) {
        return OutcomeSettler.tableMaximum(server, game, bestPayoutRatio);
    }

    @Override
    public boolean withinExposure(MinecraftServer server, Game game, String tableKey, long worstCase) {
        return OutcomeSettler.withinExposure(server, game, tableKey, worstCase);
    }

    @Override
    public void commitExposure(String tableKey, long worstCase) {
        OutcomeSettler.commitExposure(tableKey, worstCase);
    }

    @Override
    public void releaseExposure(String tableKey) {
        OutcomeSettler.releaseExposure(tableKey);
    }

    @Override
    public void commitStake(String tableKey, UUID playerId, long staked) {
        OutcomeSettler.commitStake(tableKey, playerId, staked);
    }

    @Override
    public void releaseStakes(String tableKey) {
        OutcomeSettler.releaseStakes(tableKey);
    }

    @Override
    public OutcomeSettler.Result settle(MinecraftServer server, Game game, Outcome outcome, String detail) {
        return OutcomeSettler.settle(server, game, outcome, detail);
    }
}

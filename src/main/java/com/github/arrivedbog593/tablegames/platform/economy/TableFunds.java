package com.github.arrivedbog593.tablegames.platform.economy;

import com.github.arrivedbog593.tablegames.engine.economy.PlayerCommitments;
import com.github.arrivedbog593.tablegames.engine.game.Game;
import com.github.arrivedbog593.tablegames.engine.session.Outcome;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;

/**
 * Where a game block's money comes from and goes to.
 * <p>
 * Everything a table or a machine asks of the economy, and nothing else:
 * what a player holds, how far the house may be exposed, the largest wager
 * the bankroll allows, and settling a hand. Asked through this rather than
 * of {@link OutcomeSettler} directly so that a test table can answer every
 * one of those questions from a pretend economy — {@link SandboxFunds} —
 * while every other table goes on answering them exactly as before, through
 * {@link RealFunds}.
 * <p>
 * The line it draws is the whole guarantee a test table gives: nothing a
 * table does reaches the real bankroll, the real balances, the shared
 * registries of what is committed, or the transaction log, except through
 * here.
 */
public interface TableFunds {

    /** Whether this is a pretend economy. */
    boolean isSandbox();

    /** What this player holds, as far as this table is concerned. */
    long balanceOf(MinecraftServer server, UUID playerId);

    /** What players have promised, so a table can ask what they promised elsewhere. */
    PlayerCommitments stakes();

    /** Whether a house-banked game may open at all. */
    boolean canOpen(MinecraftServer server, Game game);

    /** The largest wager the bankroll allows on a bet paying this much to one. */
    long tableMaximum(MinecraftServer server, Game game, int bestPayoutRatio);

    /** Whether a table may take on this much worst-case liability. */
    boolean withinExposure(MinecraftServer server, Game game, String tableKey, long worstCase);

    /**
     * The largest worst-case liability this table could take on right now,
     * or {@link Long#MAX_VALUE} for a game that does not play the house.
     * The same limit {@link #withinExposure} checks, asked as a figure, so a
     * game can offer a player the most it would accept instead of refusing
     * what they offered.
     */
    long exposureHeadroom(MinecraftServer server, Game game, String tableKey);

    /** Records what a table now stands to lose. */
    void commitExposure(String tableKey, long worstCase);

    /** Frees a table's share of the exposure. */
    void releaseExposure(String tableKey);

    /** Records what one player now has on one table. */
    void commitStake(String tableKey, UUID playerId, long staked);

    /** Frees everything players had on a table. */
    void releaseStakes(String tableKey);

    /**
     * Settles a finished hand; all or nothing. On refusal the caller cancels
     * and refunds rather than retrying.
     */
    OutcomeSettler.Result settle(MinecraftServer server, Game game, Outcome outcome, String detail);
}

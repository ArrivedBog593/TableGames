package com.github.arrivedbog593.tablegames.economy.platform;

import com.github.arrivedbog593.tablegames.economy.engine.PlayerCommitments;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;

/**
 * What every player has promised and not yet paid, server-wide.
 * <p>
 * A wager on a table that has not settled is one such promise; a bid in an
 * auction that has not closed would be another. Nothing is debited when the
 * promise is made, so the balance still counts those credits, and anything
 * that spends has to ask what is {@link #spendable} instead — otherwise a
 * player could stake a balance at a table and spend it again at the shop
 * before the round settles.
 * <p>
 * Static and in memory. A promise cannot outlive whatever made it, and the
 * things that make them — rounds, auctions — are not saved, so a registry
 * that starts empty after a restart is telling the truth. It is cleared on
 * start and on stop for the same reason.
 */
public final class Commitments {

    private static final PlayerCommitments PLAYERS = new PlayerCommitments();

    private Commitments() {
    }

    public static PlayerCommitments players() {
        return PLAYERS;
    }

    /** What this player may spend without touching anything they promised. */
    public static long spendable(MinecraftServer server, UUID playerId) {
        return PLAYERS.spendable(playerId, CreditStorage.get(server).balanceOf(playerId));
    }
}

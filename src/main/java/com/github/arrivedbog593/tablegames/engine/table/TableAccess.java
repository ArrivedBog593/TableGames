package com.github.arrivedbog593.tablegames.engine.table;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Who a table belongs to and who else may set it up.
 * <p>
 * Knows nothing about players, permissions systems or blocks: it answers
 * questions about ids, and the caller says whether the person asking runs the
 * server. That is what lets these rules be tested — a permission check nobody
 * can exercise without launching a game is a permission check nobody
 * exercises.
 * <p>
 * Mutable, because a table is a block that gets placed, shared and handed
 * around for as long as it stands.
 */
public final class TableAccess {

    /**
     * How many guests one table holds.
     * <p>
     * A ceiling so that a shared list cannot grow without bound in a block
     * entity that is written to disk on every save.
     */
    public static final int MAX_TRUSTED = 32;

    private UUID owner;

    private final Set<UUID> trusted = new LinkedHashSet<>();

    /** Whose table this is, if anybody's. */
    public Optional<UUID> owner() {
        return Optional.ofNullable(owner);
    }

    /**
     * Records who placed it and drops whatever sharing came with it.
     * <p>
     * The list goes because a copied table carries the original's guests in
     * its data: control clicking somebody's table in creative and putting it
     * down would otherwise hand their friends the run of a table that is now
     * yours.
     */
    public void claim(UUID owner) {
        this.owner = owner;
        trusted.clear();
    }

    /** Everyone the owner has shared this table with. */
    public Set<UUID> trusted() {
        return Set.copyOf(trusted);
    }

    /**
     * Lets somebody else configure this table.
     *
     * @return false if they were already on the list, or it is full
     */
    public boolean trust(UUID playerId) {
        return trusted.size() < MAX_TRUSTED && trusted.add(playerId);
    }

    /** @return false if they were not on the list to begin with */
    public boolean untrust(UUID playerId) {
        return trusted.remove(playerId);
    }

    /**
     * Whether this player decides who may configure the table.
     * <p>
     * The owner and whoever has authority over every table, and deliberately
     * not the people on the list. Sharing the sharing would be an escalation
     * with no way back: anyone added could add anybody, or drop the owner's
     * other guests.
     *
     * @param authority whether the server lets this player manage any table:
     *                  an operator, or staff of a rank that reaches tables
     */
    public boolean mayShare(UUID playerId, boolean authority) {
        return authority || playerId.equals(owner);
    }

    /**
     * Whether this player may change what the table hosts and what it takes.
     * <p>
     * Three ways in. Authority over every table, which the server grants. The
     * owner placed this particular block. The rest are people the owner
     * shared it with, which is what makes a table in a shared base usable by
     * the people who share it.
     * <p>
     * A table with no owner — one that predates ownership, or that something
     * other than a player put down — needs that authority. It does not become
     * unowned property that the next passer-by may reconfigure.
     *
     * @param authority as for {@link #mayShare}
     */
    public boolean mayConfigure(UUID playerId, boolean authority) {
        return authority || playerId.equals(owner) || trusted.contains(playerId);
    }

    /**
     * Whether this player may host, or set up, a game of this kind here.
     * <p>
     * A game played against the house is the house's money, not the table
     * owner's: whoever sets its limits, its buy-in or its return decides how
     * much the bankroll can lose. So only that authority may, even on a table
     * somebody placed in their own base. A game played between players moves
     * nothing of the house's, and stays with whoever may configure the table.
     *
     * @param authority   as for {@link #mayShare}
     * @param houseBanked whether the game pays out of the house bankroll
     */
    public boolean mayConfigure(UUID playerId, boolean authority, boolean houseBanked) {
        return houseBanked ? authority : mayConfigure(playerId, authority);
    }

    /** Whether there is nothing here worth writing to disk. */
    public boolean isUnclaimed() {
        return owner == null && trusted.isEmpty();
    }

    /**
     * Puts back what was read from disk, keeping the guest ceiling.
     * <p>
     * Anything past the ceiling is dropped rather than refused: a stored list
     * longer than the rules now allow should shrink to something legal, not
     * stop the table from loading.
     */
    public void restore(UUID owner, Collection<UUID> guests) {
        this.owner = owner;
        trusted.clear();
        for (UUID guest : guests) {
            if (trusted.size() >= MAX_TRUSTED) {
                return;
            }
            trusted.add(guest);
        }
    }
}

package com.github.arrivedbog593.tablegames.platform.economy;

import java.util.Optional;

/**
 * What a listed member of the casino staff may do, short of being an operator.
 * <p>
 * Two ranks because the casino and the tables are different kinds of
 * authority. The shop, the cashier and the bankroll belong to the server; a
 * table is a block somebody put down, possibly in their own base. A moderator
 * runs the first. An administrator runs the first and may also step in at
 * any table, the way an operator can.
 */
public enum StaffRank {

    MODERATOR("moderator"),
    ADMIN("admin");

    private final String id;

    StaffRank(String id) {
        this.id = id;
    }

    /** Persisted and typed in commands, so it never changes. */
    public String id() {
        return id;
    }

    public String translationKey() {
        return "tablegames.staff.rank." + id;
    }

    /** Whether this rank may configure, and share, a table it does not own. */
    public boolean managesEveryTable() {
        return this == ADMIN;
    }

    public static Optional<StaffRank> byId(String id) {
        for (StaffRank rank : values()) {
            if (rank.id.equals(id)) {
                return Optional.of(rank);
            }
        }
        return Optional.empty();
    }
}

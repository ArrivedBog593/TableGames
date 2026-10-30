package com.github.arrivedbog593.tablegames.economy.platform;

import java.util.Optional;

/**
 * How far a listed member of the staff may go, short of being an operator.
 * <p>
 * Two ranks because there are two kinds of authority. The shop, the cashier,
 * the conversion values and the house's money belong to the server, and both
 * ranks run them. What the server does not own — a block a player put down,
 * possibly in their own base — is for an administrator only, and only as far
 * as the mod that block belongs to says: the economy grants nothing there
 * itself. TableGames, for one, lets an administrator step in at any table,
 * the way an operator can.
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

    public static Optional<StaffRank> byId(String id) {
        for (StaffRank rank : values()) {
            if (rank.id.equals(id)) {
                return Optional.of(rank);
            }
        }
        return Optional.empty();
    }
}

package com.github.arrivedbog593.tablegames.engine.table;

/**
 * What happened when somebody tried to sit down or stand up.
 * <p>
 * Returned rather than thrown, for the same reason illegal actions are: on a
 * public server people click buttons that stopped being valid a tick ago, and
 * lag is not an exceptional condition.
 */
public enum SeatChange {

    /** They are now seated. */
    SEATED(true, "tablegames.seat.sat_down"),

    /** They are now standing, watching. */
    STOOD(true, "tablegames.seat.stood_up"),

    /** Nothing to do: they were already seated. */
    ALREADY_SEATED(false, "tablegames.seat.already_seated"),

    /** Nothing to do: they were not seated to begin with. */
    NOT_SEATED(false, "tablegames.seat.not_seated"),

    /** Every seat is taken. They stay a spectator. */
    TABLE_FULL(false, "tablegames.seat.table_full"),

    /**
     * Betting has closed, so the seating is fixed until the round resolves.
     * Covers both directions: nobody joins a round already turning, and
     * nobody walks out of one carrying a stake that is about to lose.
     */
    ROUND_LOCKED(false, "tablegames.seat.round_locked"),

    /** They do not have the table open at all. */
    NOT_AT_TABLE(false, "tablegames.seat.not_at_table");

    private final boolean changed;
    private final String translationKey;

    SeatChange(boolean changed, String translationKey) {
        this.changed = changed;
        this.translationKey = translationKey;
    }

    /** Whether the occupancy actually moved. */
    public boolean changed() {
        return changed;
    }

    public String translationKey() {
        return translationKey;
    }
}

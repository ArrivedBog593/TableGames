package com.github.arrivedbog593.tablegames.engine.table;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What one table has been set to, as plain numbers keyed by setting id.
 * <p>
 * Deliberately ignorant of what the numbers mean. The table holds this; the
 * game holds the {@link SettingSpec}s that give it sense. A value with no spec
 * to read it is not an error — it is what a roulette table's limits look like
 * while it is temporarily hosting blackjack, and keeping it is what lets the
 * table go back to being what it was.
 * <p>
 * Immutable, like the limits it replaces. Changing a setting produces a new
 * instance, so nothing can be half applied while a round reads it.
 */
public final class TableSettings {

    private static final TableSettings EMPTY = new TableSettings(Map.of());

    private final Map<String, Long> values;

    private TableSettings(Map<String, Long> values) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /** Nothing configured: every setting answers with its own default. */
    public static TableSettings empty() {
        return EMPTY;
    }

    /**
     * Rebuilds from stored numbers, as read back from a save or a packet.
     * <p>
     * Values are taken as they come, without a spec to judge them against.
     * The specs of whatever game is assigned may not cover all of them, and
     * the ones they do cover are validated when they are read.
     */
    public static TableSettings of(Map<String, Long> stored) {
        Objects.requireNonNull(stored, "stored");
        return stored.isEmpty() ? EMPTY : new TableSettings(stored);
    }

    /**
     * What this setting is worth here, or its default when it was never set.
     * <p>
     * A stored value the spec would refuse is treated as absent rather than
     * returned. Bounds can tighten between versions, and a table that has
     * been sitting on a number the game no longer accepts should fall back to
     * something legal instead of handing it to a session.
     */
    public long get(SettingSpec spec) {
        Objects.requireNonNull(spec, "spec");
        Long stored = values.get(spec.id());
        if (stored == null || !spec.accepts(stored)) {
            return spec.defaultValue();
        }
        return stored;
    }

    /** Whether this setting has been given a value of its own. */
    public boolean isSet(SettingSpec spec) {
        Long stored = values.get(spec.id());
        return stored != null && spec.accepts(stored);
    }

    /**
     * A copy with one setting changed.
     *
     * @throws IllegalArgumentException if the spec does not accept the value
     */
    public TableSettings with(SettingSpec spec, long value) {
        Objects.requireNonNull(spec, "spec");
        if (!spec.accepts(value)) {
            throw new IllegalArgumentException(
                    "Not a legal value for " + spec.id() + ": " + value);
        }
        Map<String, Long> changed = new LinkedHashMap<>(values);
        changed.put(spec.id(), value);
        return new TableSettings(changed);
    }

    /** A copy with one setting back at its default. */
    public TableSettings without(SettingSpec spec) {
        Objects.requireNonNull(spec, "spec");
        if (!values.containsKey(spec.id())) {
            return this;
        }
        Map<String, Long> changed = new LinkedHashMap<>(values);
        changed.remove(spec.id());
        return new TableSettings(changed);
    }

    /** Every stored value, for saving. Unmodifiable. */
    public Map<String, Long> values() {
        return values;
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TableSettings settings && values.equals(settings.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return "TableSettings" + values;
    }
}

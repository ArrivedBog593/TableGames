package com.github.arrivedbog593.tablegames.engine.table;

import java.util.List;
import java.util.Objects;

/**
 * One thing a table can be configured with, described by the game that offers
 * it.
 * <p>
 * A game says what may be set and within what bounds; the table stores the
 * numbers and knows nothing about what they mean. That split is the whole
 * point: adding blinds to poker or a rule toggle to Uno is a change to one
 * game, not to the block, the screen, the packet or the save format.
 * <p>
 * Every value is stored as a {@code long}, whatever shape it wears. A flag is
 * zero or one and a choice is a position in its option list, so persistence
 * and the wire have one form to handle instead of three. The shape lives here
 * rather than in the stored value, which is what lets a screen draw a text
 * box, a toggle or a cycling button from the same underlying number.
 * <p>
 * Ids are prefixed with the id of the game that declares them, e.g.
 * {@code "roulette.inside_min"}. Two games that both want a minimum bet then
 * keep their own, and a table that changes game and changes back finds its
 * old values where it left them.
 */
public sealed interface SettingSpec {

    /** Prefixed with the declaring game's id. Persisted, so renaming resets it. */
    String id();

    /** What the setting is worth until somebody changes it. */
    long defaultValue();

    /** Whether this value is one the setting can hold at all. */
    boolean accepts(long value);

    /** Translation key for the label a screen puts beside the control. */
    default String translationKey() {
        return "tablegames.setting." + id();
    }

    /**
     * A whole number within bounds: a minimum bet, a blind, a countdown.
     * <p>
     * The bounds are what the setting can hold in isolation. Rules that tie
     * two settings together — a maximum that may not sit below its own
     * minimum — cannot be expressed here and belong to the game, which is the
     * only thing that knows the pair means one idea.
     *
     * @param minimum smallest accepted value, inclusive
     * @param maximum largest accepted value, inclusive
     */
    record Amount(String id, long defaultValue, long minimum, long maximum)
            implements SettingSpec {

        public Amount {
            Objects.requireNonNull(id, "id");
            if (minimum > maximum) {
                throw new IllegalArgumentException(
                        "Bounds are inverted: " + minimum + " > " + maximum);
            }
            if (defaultValue < minimum || defaultValue > maximum) {
                throw new IllegalArgumentException(
                        "Default outside its own bounds: " + defaultValue);
            }
        }

        @Override
        public boolean accepts(long value) {
            return value >= minimum && value <= maximum;
        }
    }

    /** On or off, stored as one or zero. */
    record Flag(String id, boolean on) implements SettingSpec {

        public Flag {
            Objects.requireNonNull(id, "id");
        }

        @Override
        public long defaultValue() {
            return on ? 1 : 0;
        }

        @Override
        public boolean accepts(long value) {
            return value == 0 || value == 1;
        }

        /** Reads a stored number back as the flag it stands for. */
        public boolean isOn(long value) {
            return value == 1;
        }
    }

    /**
     * One of a fixed list, stored as its position.
     * <p>
     * Position rather than name because the wire and the save file stay
     * numbers like every other setting. The cost is that reordering the list
     * silently changes what saved tables are set to, so options are appended,
     * never rearranged.
     *
     * @param options   option names, in the order they are offered
     * @param initial   position selected until somebody chooses otherwise
     */
    record Choice(String id, List<String> options, int initial) implements SettingSpec {

        public Choice {
            Objects.requireNonNull(id, "id");
            options = List.copyOf(options);
            if (options.isEmpty()) {
                throw new IllegalArgumentException("A choice needs options: " + id);
            }
            if (initial < 0 || initial >= options.size()) {
                throw new IllegalArgumentException(
                        "No such option to start on: " + initial);
            }
        }

        @Override
        public long defaultValue() {
            return initial;
        }

        @Override
        public boolean accepts(long value) {
            return value >= 0 && value < options.size();
        }

        /** The option a stored number stands for. */
        public String optionAt(long value) {
            if (!accepts(value)) {
                throw new IllegalArgumentException("No such option: " + value);
            }
            return options.get((int) value);
        }

        /** Translation key for one option's label. */
        public String translationKeyFor(long value) {
            return translationKey() + "." + optionAt(value);
        }
    }
}

package com.github.arrivedbog593.tablegames.engine.games.slots;

import com.github.arrivedbog593.tablegames.engine.session.Action;

/**
 * Moves at a slot machine. There is only one.
 */
public sealed interface SlotAction extends Action {

    /**
     * Pulls the lever.
     *
     * @param lines   how many lines to play, one to {@link Payline#MAX}
     * @param perLine the stake on each line
     * @param free    whether a replay pays for this spin, so it costs nothing
     */
    record Spin(int lines, long perLine, boolean free) implements SlotAction {

        public Spin {
            if (lines < 1 || lines > Payline.MAX) {
                throw new IllegalArgumentException("Between 1 and " + Payline.MAX + " lines");
            }
            if (perLine < 1) {
                throw new IllegalArgumentException("A stake per line of at least one");
            }
        }

        /** What the spin costs: every line's stake, or nothing when it is free. */
        public long cost() {
            return free ? 0 : Math.multiplyExact(perLine, lines);
        }

        @Override
        public String translationKey() {
            return "tablegames.action.spin";
        }
    }
}

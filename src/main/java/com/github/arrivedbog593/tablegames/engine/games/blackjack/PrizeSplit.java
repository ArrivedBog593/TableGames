package com.github.arrivedbog593.tablegames.engine.games.blackjack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * How a tournament's entries are shared out among its finishers.
 * <p>
 * Appended to, never rearranged: a table stores its choice as a position.
 */
public enum PrizeSplit {

    /** The whole pot to first place. */
    WINNER_TAKES_ALL(100),

    /** Seventy percent to first, thirty to second, so a close second is not empty-handed. */
    SEVENTY_THIRTY(70, 30);

    private final int[] percents;

    PrizeSplit(int... percents) {
        this.percents = percents;
    }

    /** The lowercase name a setting stores and a translation key ends in. */
    public String optionName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * What each place takes from a pot this size, first place first. Every
     * credit is handed out: what the percentages round away goes to the last
     * paid place, so the prizes always add up to the pot.
     */
    public List<Long> prizesFor(long pot) {
        List<Long> prizes = new ArrayList<>();
        long handedOut = 0;
        for (int place = 0; place < percents.length; place++) {
            long prize = place == percents.length - 1
                    ? pot - handedOut
                    : pot / 100 * percents[place] + pot % 100 * percents[place] / 100;
            prizes.add(prize);
            handedOut += prize;
        }
        return List.copyOf(prizes);
    }
}

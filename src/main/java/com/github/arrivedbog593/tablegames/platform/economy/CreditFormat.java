package com.github.arrivedbog593.tablegames.platform.economy;

import java.util.Locale;

/**
 * How a credit amount is written for a person to read.
 * <p>
 * Shared because it was already duplicated in the commands and in the screens,
 * and a third copy had just been about to appear in a place that showed a bare
 * {@code 138888} next to a properly grouped balance.
 * <p>
 * Fixed to {@link Locale#ROOT} rather than the default. The default is the
 * server's, not the reader's, so a server hosted in one country would group
 * digits one way for every player on it regardless of where they are — an
 * inconsistency that looks like a bug and cannot be fixed by the player.
 * One predictable format everywhere is the lesser evil, and the separator carries
 * no meaning here beyond making long numbers readable.
 */
public final class CreditFormat {

    private CreditFormat() {
    }

    /** Groups thousands: {@code 138888} becomes {@code 138,888}. */
    public static String of(long credits) {
        return String.format(Locale.ROOT, "%,d", credits);
    }
}

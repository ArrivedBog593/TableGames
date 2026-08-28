package com.github.arrivedbog593.tablegames.engine.games.roulette;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * One numbered slot on the wheel.
 * <p>
 * Double zero is modeled as {@code number == 0} with {@code doubleZero ==
 * true} rather than as the number 37, so that arithmetic on the number field
 * never accidentally treats it as a real value. A bet on 0 and a bet on 00
 * are different bets, which record equality handles for free.
 *
 * @param number     0 to 36; 0 for both green pockets
 * @param doubleZero true only for the American 00
 * @param color      green for the zeros, red or black otherwise
 */
public record Pocket(int number, boolean doubleZero, PocketColor color) {

    public Pocket {
        if (number < 0 || number > 36) {
            throw new IllegalArgumentException("Pocket number out of range: " + number);
        }
        if (doubleZero && number != 0) {
            throw new IllegalArgumentException("Double zero must carry number 0");
        }
        Objects.requireNonNull(color, "color");
        boolean green = color == PocketColor.GREEN;
        if (green != (number == 0)) {
            throw new IllegalArgumentException(
                    "Only the zeros are green; pocket " + number + " claims " + color);
        }
    }

    public static Pocket of(int number, PocketColor color) {
        return new Pocket(number, false, color);
    }

    public static Pocket zero() {
        return new Pocket(0, false, PocketColor.GREEN);
    }

    public static Pocket doubleZeroPocket() {
        return new Pocket(0, true, PocketColor.GREEN);
    }

    /**
     * Either green pocket. Zeros lose every outside bet, which is the entire
     * source of the house edge.
     */
    public boolean isZero() {
        return color == PocketColor.GREEN;
    }

    /** Display label: "0", "00", "17". */
    public String label() {
        if (doubleZero) {
            return "00";
        }
        return Integer.toString(number);
    }

    @Override
    public @NotNull String toString() {
        return label() + "/" + color;
    }
}
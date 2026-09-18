package com.github.arrivedbog593.tablegames.engine.table;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingSpecTest {

    @Test
    void anAmountAcceptsItsBoundsAndNothingBeyond() {
        SettingSpec.Amount spec = new SettingSpec.Amount("test.bet", 10, 1, 100);
        assertTrue(spec.accepts(1));
        assertTrue(spec.accepts(100));
        assertFalse(spec.accepts(0));
        assertFalse(spec.accepts(101));
    }

    @Test
    void anAmountCannotDefaultToSomethingItWouldRefuse() {
        assertThrows(IllegalArgumentException.class,
                () -> new SettingSpec.Amount("test.bet", 500, 1, 100));
        assertThrows(IllegalArgumentException.class,
                () -> new SettingSpec.Amount("test.bet", 10, 100, 1));
    }

    @Test
    void aFlagIsOneOrZeroAndNothingElse() {
        SettingSpec.Flag spec = new SettingSpec.Flag("test.stacking", true);
        assertEquals(1, spec.defaultValue());
        assertTrue(spec.isOn(1));
        assertFalse(spec.isOn(0));
        assertFalse(spec.accepts(2));
        assertFalse(spec.accepts(-1));
    }

    @Test
    void aChoiceIsAPositionInItsOwnList() {
        SettingSpec.Choice spec = new SettingSpec.Choice(
                "test.variant", List.of("classic", "flip", "no_mercy"), 1);

        assertEquals(1, spec.defaultValue());
        assertEquals("classic", spec.optionAt(0));
        assertEquals("no_mercy", spec.optionAt(2));
        assertFalse(spec.accepts(3));
        assertThrows(IllegalArgumentException.class, () -> spec.optionAt(3));
    }

    @Test
    void aChoiceNeedsOptionsAndAStartingOneAmongThem() {
        assertThrows(IllegalArgumentException.class,
                () -> new SettingSpec.Choice("test.variant", List.of(), 0));
        assertThrows(IllegalArgumentException.class,
                () -> new SettingSpec.Choice("test.variant", List.of("only"), 1));
    }

    @Test
    void aChoiceKeepsItsOwnCopyOfTheOptions() {
        List<String> options = new ArrayList<>(List.of("classic", "flip"));
        SettingSpec.Choice spec = new SettingSpec.Choice("test.variant", options, 0);
        options.clear();

        assertEquals(2, spec.options().size(), "a spec must not change underneath a table");
    }

    @Test
    void labelsAreDerivedFromTheIdRatherThanStoredTwice() {
        SettingSpec.Amount amount = new SettingSpec.Amount("roulette.inside_min", 10, 1, 100);
        assertEquals("tablegames.setting.roulette.inside_min", amount.translationKey());

        SettingSpec.Choice choice = new SettingSpec.Choice(
                "uno.variant", List.of("classic", "flip"), 0);
        assertEquals("tablegames.setting.uno.variant.flip", choice.translationKeyFor(1));
    }
}

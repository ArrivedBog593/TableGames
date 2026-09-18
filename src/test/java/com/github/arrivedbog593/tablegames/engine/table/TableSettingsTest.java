package com.github.arrivedbog593.tablegames.engine.table;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableSettingsTest {

    private static final SettingSpec.Amount MINIMUM =
            new SettingSpec.Amount("test.minimum", 10, 1, 1_000);
    private static final SettingSpec.Flag DOUBLING =
            new SettingSpec.Flag("test.doubling", true);
    private static final SettingSpec.Choice VARIANT =
            new SettingSpec.Choice("test.variant", List.of("classic", "flip"), 0);

    @Test
    void anUntouchedSettingAnswersWithItsDefault() {
        TableSettings settings = TableSettings.empty();
        assertEquals(10, settings.get(MINIMUM));
        assertEquals(1, settings.get(DOUBLING));
        assertEquals(0, settings.get(VARIANT));
        assertFalse(settings.isSet(MINIMUM));
    }

    @Test
    void changingOneLeavesTheOriginalAlone() {
        TableSettings original = TableSettings.empty();
        TableSettings changed = original.with(MINIMUM, 50);

        assertEquals(10, original.get(MINIMUM), "settings must be immutable");
        assertEquals(50, changed.get(MINIMUM));
        assertTrue(changed.isSet(MINIMUM));
    }

    @Test
    void valuesOutsideTheBoundsAreRefused() {
        TableSettings settings = TableSettings.empty();
        assertThrows(IllegalArgumentException.class, () -> settings.with(MINIMUM, 0));
        assertThrows(IllegalArgumentException.class, () -> settings.with(MINIMUM, 1_001));
        assertThrows(IllegalArgumentException.class, () -> settings.with(DOUBLING, 2));
        assertThrows(IllegalArgumentException.class, () -> settings.with(VARIANT, 2));
    }

    @Test
    void aStoredValueTheSpecWouldRefuseFallsBackToTheDefault() {
        // Bounds can tighten between versions. A table sitting on a number
        // the game no longer accepts must not hand it to a session.
        TableSettings settings = TableSettings.of(Map.of("test.minimum", 9_999L));
        assertEquals(10, settings.get(MINIMUM));
        assertFalse(settings.isSet(MINIMUM));
    }

    @Test
    void valuesOfOtherGamesSurviveUntouched() {
        // What a roulette table's limits look like while it hosts blackjack.
        TableSettings settings = TableSettings.of(Map.of("roulette.inside_min", 25L))
                .with(MINIMUM, 50);

        assertEquals(25L, settings.values().get("roulette.inside_min"));
        assertEquals(2, settings.values().size());
    }

    @Test
    void clearingOneSendsItBackToItsDefault() {
        TableSettings settings = TableSettings.empty().with(MINIMUM, 50).without(MINIMUM);
        assertEquals(10, settings.get(MINIMUM));
        assertTrue(settings.isEmpty());
    }

    @Test
    void theStoredMapCannotBeEditedFromOutside() {
        TableSettings settings = TableSettings.empty().with(MINIMUM, 50);
        assertThrows(UnsupportedOperationException.class,
                () -> settings.values().put("test.minimum", 1L));
    }

    @Test
    void twoTablesSetTheSameWayAreEqual() {
        assertEquals(TableSettings.empty().with(MINIMUM, 50),
                TableSettings.of(Map.of("test.minimum", 50L)));
    }
}

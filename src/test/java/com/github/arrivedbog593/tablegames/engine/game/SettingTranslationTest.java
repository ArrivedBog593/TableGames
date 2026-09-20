package com.github.arrivedbog593.tablegames.engine.game;

import com.github.arrivedbog593.tablegames.engine.games.roulette.RouletteGame;
import com.github.arrivedbog593.tablegames.engine.games.slots.SlotsGame;
import com.github.arrivedbog593.tablegames.engine.table.SettingSpec;
import com.github.arrivedbog593.tablegames.engine.table.TableSettings;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every key a game's setup screen can ask for is a key every language has.
 * <p>
 * Worth a test because the failure is silent and ugly: a missing key does
 * not throw, it draws its own name at the player, and only in the language
 * nobody on the development machine is using. A game declaring a new
 * setting, or a new option on one, is exactly when this gets forgotten.
 * <p>
 * The files are read as text rather than parsed. The question is only
 * whether a key is quoted somewhere in them, and a parser would be a
 * dependency bought for nothing.
 */
class SettingTranslationTest {

    private static final List<String> LANGUAGES = List.of("en_us", "es_es", "es_mx");

    /** Everything the engine can build, and so everything a table can post. */
    private static List<Game> everyGame() {
        return List.of(RouletteGame.european(), RouletteGame.american(), SlotsGame.standard());
    }

    @Test
    void everySettingIsNamedInEveryLanguage() {
        Set<String> keys = new LinkedHashSet<>();
        for (Game game : everyGame()) {
            for (SettingSpec spec : game.settings()) {
                keys.add(spec.translationKey());
                if (spec instanceof SettingSpec.Choice choice) {
                    for (int option = 0; option < choice.options().size(); option++) {
                        keys.add(choice.translationKeyFor(option));
                    }
                }
            }
        }
        assertAllTranslated(keys);
    }

    /**
     * The refusals a slot machine's settings can earn.
     * <p>
     * Collected by handing the game configurations it should refuse, because
     * a problem key exists nowhere else: it is returned and never declared,
     * so nothing but calling the rule can enumerate them.
     */
    @Test
    void everyRefusalASlotMachineCanGiveIsWorded() {
        SlotsGame slots = SlotsGame.standard();
        Set<String> keys = new LinkedHashSet<>();

        // A line maximum below its own minimum.
        keys.add(problemWith(slots, TableSettings.empty()
                .with(slots.betMinimum(), 50)
                .with(slots.betMaximum(), 10)));
        // Credits capped below what it takes to sit down.
        keys.add(problemWith(slots, TableSettings.empty()
                .with(slots.buyInMinimum(), 500)
                .with(slots.buyInMaximum(), 100)));
        // A stack that cannot pay for a single line.
        keys.add(problemWith(slots, TableSettings.empty()
                .with(slots.betMinimum(), 100)
                .with(slots.buyInMinimum(), 10)));

        assertAllTranslated(keys);
    }

    private static String problemWith(SlotsGame game, TableSettings settings) {
        Optional<String> problem = game.settingsProblem(settings);
        assertTrue(problem.isPresent(), "these settings should have been refused");
        return problem.get();
    }

    private static void assertAllTranslated(Set<String> keys) {
        List<String> missing = new ArrayList<>();
        for (String language : LANGUAGES) {
            String contents = read(language);
            for (String key : keys) {
                if (!contents.contains('"' + key + '"')) {
                    missing.add(language + ": " + key);
                }
            }
        }
        assertTrue(missing.isEmpty(), "untranslated keys: " + missing);
    }

    private static String read(String language) {
        String path = "/assets/tablegames/lang/" + language + ".json";
        try (InputStream in = SettingTranslationTest.class.getResourceAsStream(path)) {
            assertTrue(in != null, "no language file at " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}

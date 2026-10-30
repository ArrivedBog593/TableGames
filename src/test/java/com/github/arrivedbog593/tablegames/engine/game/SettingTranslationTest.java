package com.github.arrivedbog593.tablegames.engine.game;

import com.github.arrivedbog593.tablegames.engine.games.blackjack.BlackjackGame;
import com.github.arrivedbog593.tablegames.engine.games.blackjack.BlackjackRig;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
        return List.of(RouletteGame.european(), RouletteGame.american(), SlotsGame.standard(),
                BlackjackGame.standard(), BlackjackGame.tournament(), BlackjackGame.banker());
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
     * A family of several games is a menu of its own, named, with each game
     * in it named again as a mode.
     */
    @Test
    void everyFamilyAndItsModesAreNamedInEveryLanguage() {
        Map<String, List<Game>> families = new LinkedHashMap<>();
        for (Game game : everyGame()) {
            families.computeIfAbsent(game.family(), family -> new ArrayList<>()).add(game);
        }
        Set<String> keys = new LinkedHashSet<>();
        for (List<Game> games : families.values()) {
            if (games.size() > 1) {
                keys.add(games.getFirst().familyTranslationKey());
                games.forEach(game -> keys.add(game.modeTranslationKey()));
            }
        }
        assertTrue(keys.size() > 2, "roulette and blackjack should both be families");
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

    /** The same for a blackjack table, and the hands a test table can be told to deal. */
    @Test
    void everyRefusalAndRiggedHandABlackjackTableCanShowIsWorded() {
        BlackjackGame blackjack = BlackjackGame.standard();
        Set<String> keys = new LinkedHashSet<>();
        keys.add(problemWith(blackjack, TableSettings.empty()
                .with(blackjack.minimumWager(), 50)
                .with(blackjack.maximumWager(), 10)));
        keys.add(problemWith(blackjack, TableSettings.empty()
                .with(blackjack.buyInMinimum(), 500)
                .with(blackjack.buyInMaximum(), 100)));
        keys.add(problemWith(blackjack, TableSettings.empty()
                .with(blackjack.minimumWager(), 100)
                .with(blackjack.buyInMinimum(), 10)));
        for (BlackjackRig rig : BlackjackRig.values()) {
            keys.add(rig.translationKey());
        }
        keys.add("tablegames.reject.blackjack.insurance_answered");
        keys.add("tablegames.reject.blackjack.bank_cannot_cover");
        for (String summary : List.of("dealer_blackjack", "dealer_bust", "dealer_stands", "tournament")) {
            keys.add("tablegames.summary.blackjack." + summary);
        }

        // A tournament nobody could place the first bet in.
        BlackjackGame tournament = BlackjackGame.tournament();
        keys.add(problemWith(tournament, TableSettings.empty()
                .with(tournament.startingChips(), 50)
                .with(tournament.minimumWager(), 100)));
        // A player bank whose buy-in cannot cover the wager it plays for.
        BlackjackGame banker = BlackjackGame.banker();
        keys.add(problemWith(banker, TableSettings.empty()
                .with(banker.fixedBet(), 500)
                .with(banker.buyInMinimum(), 100)));
        assertAllTranslated(keys);
    }

    private static String problemWith(Game game, TableSettings settings) {
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

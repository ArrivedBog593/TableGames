package com.github.arrivedbog593.tablegames.engine.games.roulette;

import com.github.arrivedbog593.tablegames.engine.table.SettingSpec;
import com.github.arrivedbog593.tablegames.engine.table.TableSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouletteSettingsTest {

    @Test
    void anUnconfiguredTablePostsTheGameDefaults() {
        RouletteGame game = RouletteGame.european();
        assertEquals(BetLimits.DEFAULT, game.limitsFrom(TableSettings.empty()));
    }

    @Test
    void configuredValuesBecomeTheLimitsTheSessionEnforces() {
        RouletteGame game = RouletteGame.european();
        TableSettings settings = TableSettings.empty()
                .with(game.insideMinimum(), 25)
                .with(game.insideMaximum(), 500);

        BetLimits limits = game.limitsFrom(settings);
        assertEquals(25, limits.insideMinimum());
        assertEquals(500, limits.insideMaximum());
        assertEquals(BetLimits.DEFAULT_MINIMUM, limits.outsideMinimum(),
                "settings nobody touched must keep the game's own figure");
    }

    @Test
    void theTwoWheelsKeepTheirSettingsApart() {
        // Registered as two games, so a European table and an American one
        // beside it must not share the numbers they post.
        assertEquals("roulette.inside_min", RouletteGame.european().insideMinimum().id());
        assertEquals("american_roulette.inside_min",
                RouletteGame.american().insideMinimum().id());
    }

    @Test
    void everySettingIsPrefixedWithTheGameItBelongsTo() {
        for (RouletteGame game : new RouletteGame[]{
                RouletteGame.european(), RouletteGame.american()}) {
            for (SettingSpec spec : game.settings()) {
                assertTrue(spec.id().startsWith(game.id() + "."),
                        "settings must be namespaced: " + spec.id());
            }
        }
    }

    @Test
    void aCeilingUnderItsOwnFloorIsRefused() {
        RouletteGame game = RouletteGame.european();
        TableSettings inverted = TableSettings.empty()
                .with(game.insideMinimum(), 100)
                .with(game.insideMaximum(), 50);

        assertTrue(game.settingsProblem(inverted).isPresent(),
                "a table that takes nothing at all must not be storable");
    }

    @Test
    void noCeilingAtAllIsNotAnInvertedPair() {
        // Zero means the bankroll alone decides, not a ceiling of zero.
        RouletteGame game = RouletteGame.european();
        TableSettings open = TableSettings.empty()
                .with(game.insideMinimum(), 100)
                .with(game.insideMaximum(), BetLimits.UNLIMITED);

        assertFalse(game.settingsProblem(open).isPresent());
        assertEquals(Long.MAX_VALUE, game.limitsFrom(open).maximumFor(BetType.STRAIGHT_UP));
    }

    @Test
    void bothHalvesOfTheLayoutAreChecked() {
        RouletteGame game = RouletteGame.european();
        TableSettings inverted = TableSettings.empty()
                .with(game.outsideMinimum(), 100)
                .with(game.outsideMaximum(), 50);

        assertTrue(game.settingsProblem(inverted).isPresent());
    }

    @Test
    void aGameWithNothingToDecideOffersNoSettings() {
        assertTrue(new SilentGame().settings().isEmpty());
        assertFalse(new SilentGame().settingsProblem(TableSettings.empty()).isPresent());
    }

    /** Stands in for a game that has not declared any settings. */
    private static final class SilentGame implements com.github.arrivedbog593.tablegames
            .engine.game.Game {

        @Override
        public String id() {
            return "silent";
        }

        @Override
        public int minPlayers() {
            return 1;
        }

        @Override
        public int maxPlayers() {
            return 1;
        }

        @Override
        public boolean usesBetting() {
            return false;
        }

        @Override
        public boolean isHouseBanked() {
            return false;
        }

        @Override
        public com.github.arrivedbog593.tablegames.engine.session.GameSession createSession(
                java.util.List<com.github.arrivedbog593.tablegames.engine.session.Seat> seats,
                java.util.random.RandomGenerator random) {
            throw new UnsupportedOperationException("not a real game");
        }
    }
}

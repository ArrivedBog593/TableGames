package com.github.arrivedbog593.tablegames.engine.game;

import com.github.arrivedbog593.tablegames.engine.session.GameSession;
import com.github.arrivedbog593.tablegames.engine.session.Seat;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameRegistryTest {

    private record StubGame(String id, int minPlayers, int maxPlayers) implements Game {
        @Override
        public boolean usesBetting() {
            return false;
        }

        @Override
        public boolean isHouseBanked() {
            return false;
        }

        @Override
        public GameSession createSession(List<Seat> seats, RandomGenerator random) {
            throw new UnsupportedOperationException("stub");
        }
    }

    @Test
    void registersAndLooksUpById() {
        GameRegistry registry = new GameRegistry();
        registry.register(new StubGame("roulette", 1, 8));

        assertTrue(registry.contains("roulette"));
        assertEquals(1, registry.size());
        assertEquals("tablegames.game.roulette",
                registry.get("roulette").orElseThrow().translationKey());
    }

    @Test
    void lookupIsCaseInsensitive() {
        GameRegistry registry = new GameRegistry();
        registry.register(new StubGame("blackjack", 1, 7));
        assertTrue(registry.get("BLACKJACK").isPresent());
    }

    @Test
    void rejectsDuplicateIds() {
        GameRegistry registry = new GameRegistry();
        registry.register(new StubGame("poker", 2, 9));
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(new StubGame("poker", 2, 6)));
    }

    @Test
    void rejectsMalformedIds() {
        GameRegistry registry = new GameRegistry();
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(new StubGame("Poker", 2, 9)));
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(new StubGame("video poker", 1, 1)));
    }

    @Test
    void rejectsImpossiblePlayerBounds() {
        GameRegistry registry = new GameRegistry();
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(new StubGame("broken", 5, 2)));
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(new StubGame("empty", 0, 4)));
    }

    @Test
    void frozenRegistryRefusesNewGames() {
        GameRegistry registry = new GameRegistry();
        registry.register(new StubGame("uno", 2, 10));
        registry.freeze();
        assertThrows(IllegalStateException.class,
                () -> registry.register(new StubGame("dompe", 2, 6)));
    }

    @Test
    void canStartWithChecksBounds() {
        Game game = new StubGame("dompe", 2, 6);
        assertFalse(game.canStartWith(1));
        assertTrue(game.canStartWith(4));
        assertFalse(game.canStartWith(7));
    }
}
package com.github.arrivedbog593.tablegames.engine.games.blackjack;

import com.github.arrivedbog593.tablegames.engine.card.Card;
import com.github.arrivedbog593.tablegames.engine.card.Deck;
import com.github.arrivedbog593.tablegames.engine.card.Rank;
import com.github.arrivedbog593.tablegames.engine.card.Shoe;
import com.github.arrivedbog593.tablegames.engine.card.Suit;
import com.github.arrivedbog593.tablegames.engine.table.BuyIn;
import com.github.arrivedbog593.tablegames.engine.table.SettingSpec;
import com.github.arrivedbog593.tablegames.engine.table.TableSettings;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlackjackGameTest {

    private static final BlackjackGame GAME = BlackjackGame.standard();

    @Test
    void anUntouchedTableDealsTheStandardGame() {
        assertEquals(BlackjackRules.STANDARD, GAME.rulesFrom(TableSettings.empty()));
        assertTrue(GAME.settingsProblem(TableSettings.empty()).isEmpty());
        BlackjackLimits limits = GAME.limitsFrom(TableSettings.empty());
        assertEquals(10, limits.minimum());
        assertEquals(BlackjackLimits.UNLIMITED, limits.maximum());
        assertEquals(new BuyIn(100, BuyIn.UNLIMITED), GAME.buyIn(TableSettings.empty()).orElseThrow());
    }

    @Test
    void theHouseChoosesDecksSoftSeventeenAndThePayout() {
        TableSettings settings = TableSettings.empty()
                .with(GAME.decks(), 0)
                .with(GAME.dealerHitsSoft17(), 1)
                .with(GAME.naturalPayout(), NaturalPayout.SIX_TO_FIVE.ordinal());
        assertEquals(new BlackjackRules(1, true, NaturalPayout.SIX_TO_FIVE), GAME.rulesFrom(settings));
    }

    @Test
    void everySettingIsPrefixedWithTheGameAndUnique() {
        Set<String> ids = new HashSet<>();
        for (SettingSpec spec : GAME.settings()) {
            assertTrue(spec.id().startsWith("blackjack."), spec.id());
            assertTrue(ids.add(spec.id()), "declared twice: " + spec.id());
        }
    }

    @Test
    void sevenSeatsAgainstTheHouse() {
        assertEquals(7, GAME.maxPlayers());
        assertTrue(GAME.isHouseBanked());
    }

    @Test
    void theWorstCaseOnAWagerIsItsBlackjack() {
        assertEquals(150, BlackjackRules.STANDARD.worstCaseOn(100));
        assertEquals(120, new BlackjackRules(1, false, NaturalPayout.SIX_TO_FIVE).worstCaseOn(100));
        assertEquals(1, BlackjackRules.STANDARD.worstCaseOn(1), "a blackjack on one credit still owes one");
    }

    // --- The shoe -----------------------------------------------------------------------

    @Test
    void aShoeHoldsEveryDeckAndPassesItsCutCardAtThreeQuarters() {
        Shoe shoe = new Shoe(2, new Random(5));
        assertEquals(2 * Deck.STANDARD_SIZE, shoe.size());
        assertEquals(shoe.size(), shoe.remaining());
        int dealt = 0;
        while (!shoe.pastCutCard()) {
            shoe.draw();
            dealt++;
        }
        assertEquals(78, dealt, "three quarters of 104");
    }

    @Test
    void aStackedShoeDealsTheStackFirst() {
        Shoe shoe = new Shoe(1, new Random(5));
        List<Card> top = List.of(new Card(Rank.ACE, Suit.values()[0]), new Card(Rank.KING, Suit.values()[1]));
        shoe.stack(top);
        assertEquals(top.get(0), shoe.draw());
        assertEquals(top.get(1), shoe.draw());
    }

    @Test
    void anEmptyShoeDealsOnFromAFreshOneRatherThanStopping() {
        Shoe shoe = new Shoe(1, new Random(5));
        for (int i = 0; i < Deck.STANDARD_SIZE; i++) {
            shoe.draw();
        }
        assertEquals(0, shoe.remaining());
        shoe.draw();
        assertEquals(Deck.STANDARD_SIZE - 1, shoe.remaining());
        assertFalse(shoe.pastCutCard());
    }
}

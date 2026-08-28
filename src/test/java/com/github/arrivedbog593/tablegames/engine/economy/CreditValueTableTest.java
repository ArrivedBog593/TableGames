package com.github.arrivedbog593.tablegames.engine.economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreditValueTableTest {

    private static final String IRON = "minecraft:iron_ingot";
    private static final String DIAMOND = "minecraft:diamond";
    private static final String DIRT = "minecraft:dirt";

    private static CreditValueTable table() {
        return CreditValueTable.builder()
                .put(IRON, 10)
                .put(DIAMOND, 110)
                .build();
    }

    @Test
    void listedItemsConvertAndUnlistedOnesDoNot() {
        CreditValueTable table = table();
        assertTrue(table.contains(IRON));
        assertFalse(table.contains(DIRT));
        assertEquals(10, table.valueOf(IRON).orElseThrow());
        assertTrue(table.valueOf(DIRT).isEmpty());
    }

    @Test
    void unlistedItemsCannotBeTradedAtAll() {
        CreditValueTable table = table();
        assertThrows(IllegalArgumentException.class, () -> table.creditsFor(DIRT, 1));
        assertThrows(IllegalArgumentException.class, () -> table.itemsFor(DIRT, 100));
    }

    @Test
    void aStackConvertsAtTheUnitRate() {
        assertEquals(640, table().creditsFor(IRON, 64));
    }

    @Test
    void conversionIsExactlyReversible() {
        CreditValueTable table = table();
        long credits = table.creditsFor(IRON, 37);
        CreditValueTable.Purchase back = table.itemsFor(IRON, credits);

        assertEquals(37, back.count(), "one to one conversion must round trip exactly");
        assertEquals(0, back.remainder());
    }

    @Test
    void leftoverCreditsStayWithThePlayer() {
        CreditValueTable.Purchase purchase = table().itemsFor(DIAMOND, 1000);
        assertEquals(9, purchase.count());
        assertEquals(10, purchase.remainder(), "the remainder must never be swallowed");
        assertEquals(1000, purchase.count() * 110 + purchase.remainder());
    }

    @Test
    void tooFewCreditsBuysNothingAndKeepsEverything() {
        CreditValueTable.Purchase purchase = table().itemsFor(DIAMOND, 50);
        assertTrue(purchase.isEmpty());
        assertEquals(50, purchase.remainder());
    }

    @Test
    void valuesMustBePositive() {
        assertThrows(IllegalArgumentException.class,
                () -> CreditValueTable.builder().put(IRON, 0));
        assertThrows(IllegalArgumentException.class,
                () -> CreditValueTable.builder().put(IRON, -5));
    }

    @Test
    void duplicateEntriesAreRejected() {
        CreditValueTable.Builder builder = CreditValueTable.builder().put(IRON, 10);
        assertThrows(IllegalArgumentException.class, () -> builder.put(IRON, 20));
    }

    @Test
    void declarationOrderIsPreserved() {
        assertEquals(java.util.List.of(IRON, DIAMOND),
                java.util.List.copyOf(table().itemIds()));
    }
}
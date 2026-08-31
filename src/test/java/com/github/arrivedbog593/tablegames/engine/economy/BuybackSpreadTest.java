package com.github.arrivedbog593.tablegames.engine.economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BuybackSpreadTest {

    private static CreditValueTable table(int spread) {
        return CreditValueTable.builder()
                .put("minecraft:diamond", 110)
                .put("minecraft:iron_ingot", 9)
                .put("minecraft:iron_nugget", 1)
                .build()
                .withSpread(spread);
    }

    @Test
    void withoutASpreadBuyingBackCostsWhatSellingPaid() {
        // The original promise: sell a diamond for 110, buy it back for 110.
        // Turning the fee off has to restore it exactly.
        CreditValueTable plain = table(CreditValueTable.NO_SPREAD);
        assertEquals(110, plain.creditsFor("minecraft:diamond", 1));
        assertEquals(110, plain.buybackUnit("minecraft:diamond"));
        assertEquals(0, plain.surchargeOn("minecraft:diamond", 10));
    }

    @Test
    void theSurchargeAppliesOnlyToBuyingBack() {
        // The item is still worth what the table says. The spread is a fee on
        // undoing the trade, not a revaluation.
        CreditValueTable withFee = table(10);
        assertEquals(110, withFee.creditsFor("minecraft:diamond", 1));
        assertEquals(121, withFee.buybackUnit("minecraft:diamond"));
    }

    @Test
    void theSurchargeIsReportedSeparatelyFromTheValue() {
        // The caller has to credit the bankroll with exactly this, or the
        // credits would cease to exist without anybody being paid.
        CreditValueTable withFee = table(10);
        assertEquals(1_210, withFee.buybackCost("minecraft:diamond", 10));
        assertEquals(110, withFee.surchargeOn("minecraft:diamond", 10));
        assertEquals(1_100 + 110, withFee.buybackCost("minecraft:diamond", 10));
    }

    @Test
    void aRoundTripCostsTheSpread() {
        // Sell ten diamonds for 1,100, buy them back for 1,210: the player is
        // 110 down and the house is 110 up. That is the whole point.
        CreditValueTable withFee = table(10);
        long sold = withFee.creditsFor("minecraft:diamond", 10);
        long bought = withFee.buybackCost("minecraft:diamond", 10);
        assertEquals(110, bought - sold);
    }

    @Test
    void aCheapItemStillCarriesAFee() {
        // Rounded up: one percent of a one-credit nugget would floor to
        // nothing, which reads as the setting being broken rather than as
        // rounding.
        CreditValueTable withFee = table(1);
        assertEquals(2, withFee.buybackUnit("minecraft:iron_nugget"));
        assertEquals(1, withFee.surchargeOn("minecraft:iron_nugget", 1));
    }

    @Test
    void buyingWithCreditsUsesTheBuybackPrice() {
        // Otherwise the count would be worked out at the old price and the
        // surcharge discovered afterward.
        CreditValueTable withFee = table(10);
        CreditValueTable.Purchase purchase =
                withFee.itemsFor("minecraft:diamond", 1_000);
        assertEquals(8, purchase.count());
        assertEquals(1_000 - 8 * 121, purchase.remainder());
    }

    @Test
    void theRemainderStillGoesBackToThePlayer() {
        CreditValueTable withFee = table(25);
        CreditValueTable.Purchase purchase =
                withFee.itemsFor("minecraft:iron_ingot", 100);
        long unit = withFee.buybackUnit("minecraft:iron_ingot");
        assertEquals(100, purchase.count() * unit + purchase.remainder());
    }

    @Test
    void aHundredPercentDoublesTheBuybackPrice() {
        CreditValueTable withFee = table(CreditValueTable.MAX_SPREAD_PERCENT);
        assertEquals(220, withFee.buybackUnit("minecraft:diamond"));
    }

    @Test
    void theSpreadDoesNotDisturbTheStoredValues() {
        CreditValueTable withFee = table(50);
        assertEquals(110, withFee.valueOf("minecraft:diamond").orElseThrow());
        assertEquals(3, withFee.size());
    }

    @Test
    void anUnpricedItemHasNoBuybackPrice() {
        assertThrows(IllegalArgumentException.class,
                () -> table(10).buybackUnit("minecraft:dirt"));
    }

    @Test
    void anAbsurdSpreadIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> table(0).withSpread(-1));
        assertThrows(IllegalArgumentException.class,
                () -> table(0).withSpread(CreditValueTable.MAX_SPREAD_PERCENT + 1));
    }
}

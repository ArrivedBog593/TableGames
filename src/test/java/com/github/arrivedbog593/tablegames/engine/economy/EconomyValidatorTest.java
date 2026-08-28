package com.github.arrivedbog593.tablegames.engine.economy;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EconomyValidatorTest {

    private static final String INGOT = "minecraft:iron_ingot";
    private static final String BLOCK = "minecraft:iron_block";
    private static final String NUGGET = "minecraft:iron_nugget";
    private static final String DIAMOND = "minecraft:diamond";
    private static final String WAND = "create:wand_of_symmetry";

    /** Nine ingots make a block; one block makes nine ingots. Both exist in vanilla. */
    private static final List<CraftingRelation> IRON_RECIPES = List.of(
            CraftingRelation.of(BLOCK, 1, INGOT, 9),
            CraftingRelation.of(INGOT, 9, BLOCK, 1),
            CraftingRelation.of(INGOT, 1, NUGGET, 9),
            CraftingRelation.of(NUGGET, 9, INGOT, 1));

    @Test
    void aCorrectlyProportionedTablePasses() {
        CreditValueTable table = CreditValueTable.builder()
                .put(NUGGET, 1)
                .put(INGOT, 9)
                .put(BLOCK, 81)
                .build();

        assertTrue(EconomyValidator.validate(table, IRON_RECIPES).isEmpty(),
                "values that follow the recipes are safe");
    }

    @Test
    void anOvervaluedBlockIsCaught() {
        // The mistake from the design discussion: 10 per ingot, 100 per block.
        CreditValueTable table = CreditValueTable.builder()
                .put(INGOT, 10)
                .put(BLOCK, 100)
                .build();

        List<EconomyIssue> issues = EconomyValidator.validate(table, IRON_RECIPES);
        assertTrue(EconomyValidator.hasErrors(issues));
        assertEquals(List.of(BLOCK), EconomyValidator.exploitableItems(issues));
        assertTrue(issues.getFirst().message().contains("mint"),
                "the message should say plainly that credits are being created");
    }

    @Test
    void anUndervaluedBlockIsCaughtByTheReverseRecipe() {
        // Crafting the block loses money, but uncrafting it prints money.
        CreditValueTable table = CreditValueTable.builder()
                .put(INGOT, 10)
                .put(BLOCK, 80)
                .build();

        List<EconomyIssue> issues = EconomyValidator.validate(table, IRON_RECIPES);
        assertTrue(EconomyValidator.hasErrors(issues));
        assertEquals(List.of(INGOT), EconomyValidator.exploitableItems(issues));
    }

    @Test
    void exactProportionsAreAcceptedInBothDirections() {
        CreditValueTable table = CreditValueTable.builder()
                .put(INGOT, 10)
                .put(BLOCK, 90)
                .build();

        assertTrue(EconomyValidator.validate(table, IRON_RECIPES).isEmpty());
    }

    @Test
    void recipesTouchingUnlistedItemsAreIgnored() {
        // Only the ingot is convertible, so no loop is possible regardless of
        // what a block would be worth.
        CreditValueTable table = CreditValueTable.builder()
                .put(INGOT, 10)
                .build();

        assertTrue(EconomyValidator.validate(table, IRON_RECIPES).isEmpty(),
                "an item with no credit value cannot be part of a credit loop");
    }

    @Test
    void multiIngredientRecipesAreSummedCorrectly() {
        CraftingRelation alloy = new CraftingRelation("mod:alloy", 1,
                Map.of(INGOT, 2, DIAMOND, 1));

        CreditValueTable safe = CreditValueTable.builder()
                .put(INGOT, 10)
                .put(DIAMOND, 110)
                .put("mod:alloy", 130)
                .build();
        assertTrue(EconomyValidator.validate(safe, List.of(alloy)).isEmpty());

        CreditValueTable leaky = CreditValueTable.builder()
                .put(INGOT, 10)
                .put(DIAMOND, 110)
                .put("mod:alloy", 131)
                .build();
        assertTrue(EconomyValidator.hasErrors(
                EconomyValidator.validate(leaky, List.of(alloy))));
    }

    @Test
    void recipesProducingSeveralItemsAreAccountedFor() {
        // One block yields nine ingots, so the comparison is against all nine.
        CreditValueTable table = CreditValueTable.builder()
                .put(INGOT, 10)
                .put(BLOCK, 90)
                .build();

        assertTrue(EconomyValidator.validate(
                table, List.of(CraftingRelation.of(INGOT, 9, BLOCK, 1))).isEmpty());
        assertTrue(EconomyValidator.hasErrors(EconomyValidator.validate(
                table, List.of(CraftingRelation.of(INGOT, 10, BLOCK, 1)))));
    }

    @Test
    void anEmptyTableIsTriviallySafe() {
        assertTrue(EconomyValidator.validate(
                CreditValueTable.empty(), IRON_RECIPES).isEmpty());
    }

    // --- Shop cross-checks -------------------------------------------------

    @Test
    void shopSellingBelowConversionValueIsAnExploit() {
        CreditValueTable table = CreditValueTable.builder().put(DIAMOND, 110).build();

        List<EconomyIssue> issues = EconomyValidator.validateShop(
                table, Map.of(DIAMOND, 80L));

        assertTrue(EconomyValidator.hasErrors(issues));
        assertEquals(List.of(DIAMOND), EconomyValidator.exploitableItems(issues));
    }

    @Test
    void shopSellingAboveConversionValueIsFine() {
        CreditValueTable table = CreditValueTable.builder().put(DIAMOND, 110).build();
        assertTrue(EconomyValidator.validateShop(table, Map.of(DIAMOND, 150L)).isEmpty());
    }

    @Test
    void shopSellingAtExactlyConversionValueOnlyWarns() {
        CreditValueTable table = CreditValueTable.builder().put(DIAMOND, 110).build();

        List<EconomyIssue> issues = EconomyValidator.validateShop(
                table, Map.of(DIAMOND, 110L));

        assertEquals(1, issues.size());
        assertFalse(EconomyValidator.hasErrors(issues), "a no-op is not an exploit");
    }

    @Test
    void nonConvertibleShopItemsAreAlwaysSafe() {
        // The normal case: the shop sells things players cannot sell back, so
        // it is a pure credit sink no matter how it is priced.
        CreditValueTable table = CreditValueTable.builder().put(INGOT, 10).build();

        assertTrue(EconomyValidator.validateShop(table, Map.of(WAND, 1L)).isEmpty());
        assertTrue(EconomyValidator.validateShop(table, Map.of(WAND, 50_000L)).isEmpty());
    }
}
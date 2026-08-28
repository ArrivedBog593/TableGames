package com.github.arrivedbog593.tablegames.engine.economy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Checks an economy configuration for ways to create credits from nothing.
 * <p>
 * With one-to-one conversion there is no spread to absorb a mistyped value,
 * so a single wrong number is a working money printer. This is what catches
 * it before players do.
 * <p>
 * The core check: for every recipe where both sides are convertible, crafting
 * must never be worth more than its ingredients. If nine ingots at ten
 * credits make a block worth one hundred, each craft mints ten credits and
 * the loop runs forever.
 * <p>
 * Reverse recipes need no special handling. Minecraft registers both
 * directions for blocks, so an undervalued block is caught by the
 * block-to-ingots recipe on the same pass.
 * <p>
 * Timing note for the platform layer: this must run once recipes are loaded,
 * which is after datapack reload completes, not during it. Running it too
 * early finds no recipes and reports a clean bill of health on a broken
 * config.
 */
public final class EconomyValidator {

    private EconomyValidator() {
    }

    /**
     * Validates conversion values against the server's recipes.
     *
     * @param table   what each item converts for
     * @param recipes every crafting relation known to the server; ones that
     *                touch unlisted items are skipped, since an item with no
     *                credit value cannot be part of a credit loop
     */
    public static List<EconomyIssue> validate(CreditValueTable table,
                                              List<CraftingRelation> recipes) {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(recipes, "recipes");

        List<EconomyIssue> issues = new ArrayList<>();
        for (CraftingRelation recipe : recipes) {
            if (!recipe.isFullyPricedBy(table)) {
                continue;
            }
            long ingredientValue = recipe.ingredientValue(table);
            long resultValue = recipe.resultValue(table);
            if (resultValue > ingredientValue) {
                long profit = resultValue - ingredientValue;
                issues.add(EconomyIssue.error(recipe.resultId(), String.format(
                        "Economy loop: %s is worth %d credits, but crafting it from %s "
                                + "costs only %d. Players mint %d credits per craft. "
                                + "Fix: set %s to %d, or remove one of the entries.",
                        recipe.resultId(), resultValue, recipe.describeIngredients(),
                        ingredientValue, profit, recipe.resultId(),
                        ingredientValue / recipe.resultCount())));
            }
        }
        return List.copyOf(issues);
    }

    /**
     * Validates shop prices against conversion values.
     * <p>
     * An item that can be bought from the shop for less than it converts back
     * for is a money printer with no crafting involved at all: buy, convert,
     * repeat. Shop items that are not convertible are safe by construction
     * and skipped, which is the normal case — a shop is meant to be a credit
     * sink selling things players cannot sell back.
     *
     * @param table      conversion values
     * @param shopPrices item id to what the shop charges for one
     */
    public static List<EconomyIssue> validateShop(CreditValueTable table,
                                                  Map<String, Long> shopPrices) {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(shopPrices, "shopPrices");

        List<EconomyIssue> issues = new ArrayList<>();
        for (Map.Entry<String, Long> entry : shopPrices.entrySet()) {
            String itemId = entry.getKey();
            long price = entry.getValue();
            table.valueOf(itemId).ifPresent(conversionValue -> {
                if (price < conversionValue) {
                    issues.add(EconomyIssue.error(itemId, String.format(
                            "Economy loop: the shop sells %s for %d credits but it converts "
                                    + "back for %d. Players mint %d credits per purchase. "
                                    + "Fix: raise the shop price to at least %d, or remove "
                                    + "%s from the conversion table.",
                            itemId, price, conversionValue, conversionValue - price,
                            conversionValue, itemId)));
                } else if (price == conversionValue) {
                    issues.add(EconomyIssue.warning(itemId, String.format(
                            "The shop sells %s at exactly its conversion value (%d). Not "
                                    + "exploitable, but it makes the shop a no-op for this "
                                    + "item.",
                            itemId, price)));
                }
            });
        }
        return List.copyOf(issues);
    }

    /** Whether any issue in the list is exploitable. */
    public static boolean hasErrors(List<EconomyIssue> issues) {
        for (EconomyIssue issue : issues) {
            if (issue.isError()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every item id flagged by an error, for the platform layer to drop from
     * the live table when configured to self-heal rather than only warn.
     */
    public static List<String> exploitableItems(List<EconomyIssue> issues) {
        List<String> items = new ArrayList<>();
        for (EconomyIssue issue : issues) {
            if (issue.isError() && !items.contains(issue.itemId())) {
                items.add(issue.itemId());
            }
        }
        return List.copyOf(items);
    }
}
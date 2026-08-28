package com.github.arrivedbog593.tablegames.engine.economy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A recipe reduced to what the economy cares about: what goes in, what comes
 * out, and how much.
 * <p>
 * The platform layer extracts these from the server's recipe manager and
 * hands them over as plain data. Keeping the validator on this simplified
 * view is what lets the whole exploit check run in a unit test without
 * launching Minecraft.
 *
 * @param resultId    registry id of what the recipe produces
 * @param resultCount how many it produces per craft
 * @param ingredients registry id to how much of it the recipe consumes
 */
public record CraftingRelation(String resultId, int resultCount, Map<String, Integer> ingredients) {

    public CraftingRelation {
        Objects.requireNonNull(resultId, "resultId");
        if (resultCount < 1) {
            throw new IllegalArgumentException("Result count must be positive: " + resultCount);
        }
        Objects.requireNonNull(ingredients, "ingredients");
        if (ingredients.isEmpty()) {
            throw new IllegalArgumentException("A recipe needs at least one ingredient");
        }
        for (Map.Entry<String, Integer> entry : ingredients.entrySet()) {
            if (entry.getValue() == null || entry.getValue() < 1) {
                throw new IllegalArgumentException(
                        "Ingredient count must be positive: " + entry.getKey());
            }
        }
        ingredients = Collections.unmodifiableMap(new LinkedHashMap<>(ingredients));
    }

    /** Convenience for the common single-ingredient case, e.g., nine ingots to a block. */
    public static CraftingRelation of(String resultId, int resultCount,
                                      String ingredientId, int ingredientCount) {
        return new CraftingRelation(resultId, resultCount, Map.of(ingredientId, ingredientCount));
    }

    /** Whether every item this recipe touches is convertible. */
    public boolean isFullyPricedBy(CreditValueTable table) {
        if (!table.contains(resultId)) {
            return false;
        }
        for (String ingredientId : ingredients.keySet()) {
            if (!table.contains(ingredientId)) {
                return false;
            }
        }
        return true;
    }

    /** Credit value of everything this recipe consumes. */
    public long ingredientValue(CreditValueTable table) {
        long total = 0;
        for (Map.Entry<String, Integer> entry : ingredients.entrySet()) {
            total += table.creditsFor(entry.getKey(), entry.getValue());
        }
        return total;
    }

    /** Credit value of everything this recipe produces. */
    public long resultValue(CreditValueTable table) {
        return table.creditsFor(resultId, resultCount);
    }

    /** Human-readable ingredient list for log messages. */
    public String describeIngredients() {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, Integer> entry : ingredients.entrySet()) {
            if (!text.isEmpty()) {
                text.append(" + ");
            }
            text.append(entry.getValue()).append("x ").append(entry.getKey());
        }
        return text.toString();
    }
}
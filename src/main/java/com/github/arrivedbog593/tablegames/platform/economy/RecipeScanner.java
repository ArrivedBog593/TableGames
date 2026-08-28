package com.github.arrivedbog593.tablegames.platform.economy;

import com.github.arrivedbog593.tablegames.engine.economy.CraftingRelation;
import com.github.arrivedbog593.tablegames.engine.economy.CreditValueTable;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the server's loaded recipes into the simplified relations the economy
 * validator understands.
 * <p>
 * Only recipes where the result and every ingredient are convertible matter:
 * an item with no credit value cannot be part of a credit loop, so everything
 * else is skipped without a second thought.
 * <p>
 * Must run once recipes are loaded, which means server start, not datapack
 * reload. Scanning too early finds nothing and reports a clean bill of health
 * on a broken configuration.
 */
public final class RecipeScanner {

    private RecipeScanner() {
    }

    /**
     * Extracts every priced crafting relation the server knows about.
     *
     * @param table only items listed here are considered
     */
    public static List<CraftingRelation> scan(MinecraftServer server, CreditValueTable table) {
        List<CraftingRelation> relations = new ArrayList<>();

        for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
            Recipe<?> recipe = holder.value();
            if (recipe.isSpecial()) {
                continue;
            }

            ItemStack result = recipe.getResultItem(server.registryAccess());
            if (result.isEmpty()) {
                continue;
            }
            String resultId = ItemIds.idOf(result);
            if (!table.contains(resultId)) {
                continue;
            }

            Map<String, Integer> ingredients = new LinkedHashMap<>();
            boolean priced = true;
            for (Ingredient ingredient : recipe.getIngredients()) {
                if (ingredient.isEmpty()) {
                    continue;
                }
                String cheapest = cheapestOption(ingredient, table);
                if (cheapest == null) {
                    priced = false;
                    break;
                }
                ingredients.merge(cheapest, 1, Integer::sum);
            }

            if (priced && !ingredients.isEmpty()) {
                relations.add(new CraftingRelation(resultId, result.getCount(), ingredients));
            }
        }
        return List.copyOf(relations);
    }

    /**
     * The cheapest convertible item that satisfies an ingredient or null if
     * none of its options are convertible.
     * <p>
     * Tag ingredients accept several items. Players will always feed in the
     * cheapest one, so that is the value an exploit check has to assume;
     * taking the first or the dearest would miss real loops.
     */
    private static String cheapestOption(Ingredient ingredient, CreditValueTable table) {
        String cheapestId = null;
        long cheapestValue = Long.MAX_VALUE;
        for (ItemStack option : ingredient.getItems()) {
            if (option.isEmpty()) {
                continue;
            }
            String id = ItemIds.idOf(option);
            Long value = table.valueOf(id).orElse(null);
            if (value != null && value < cheapestValue) {
                cheapestValue = value;
                cheapestId = id;
            }
        }
        return cheapestId;
    }
}
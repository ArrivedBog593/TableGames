package com.github.arrivedbog593.tablegames.platform.economy;

import com.github.arrivedbog593.tablegames.engine.economy.CraftingRelation;
import com.github.arrivedbog593.tablegames.engine.economy.CreditValueTable;
import com.github.arrivedbog593.tablegames.engine.economy.EconomyIssue;
import com.github.arrivedbog593.tablegames.engine.economy.EconomyValidator;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The live economy: which items convert, at what value, and whether any of them
 * can be exploited.
 * <p>
 * Rebuilt from scratch whenever anything changes — server start, datapack
 * reload, or a command edit. Rebuilding is inexpensive and always correct, which
 * beats patching a cached table incrementally and getting it subtly wrong
 * after the fifth edit.
 */
public final class EconomyManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** What to do when the exploit check finds a loop in an existing config. */
    public enum LoopPolicy {
        /** Drop the offending items and keep running. */
        DISABLE_ITEM,
        /** Log loudly but leave the table alone. */
        WARN_ONLY
    }

    private final ConversionTableLoader loader;
    private final LoopPolicy policy;

    private CreditValueTable liveTable = CreditValueTable.empty();
    private List<EconomyIssue> issues = List.of();
    private List<CraftingRelation> recipes = List.of();

    public EconomyManager(ConversionTableLoader loader, LoopPolicy policy) {
        this.loader = loader;
        this.policy = policy;
    }

    /**
     * Recomputes the live table from datapack defaults plus command edits,
     * then validates it.
     * <p>
     * Must run once recipes are loaded. During datapack reload, the recipe
     * manager is not ready, and a check with no recipes approves anything.
     */
    public void rebuild(MinecraftServer server) {
        EconomyData data = EconomyData.get(server);
        CreditValueTable candidate = data.mergedWith(loader.table());
        this.recipes = RecipeScanner.scan(server, candidate);

        List<EconomyIssue> found = new ArrayList<>(
                EconomyValidator.validate(candidate, recipes));
        found.addAll(EconomyValidator.validateShop(candidate, data.lowestShopPrices()));
        this.issues = List.copyOf(found);

        // The spread is applied last, to whichever table survives validation.
        // Validation only ever compares values against recipes, and a
        // surcharge on buying back cannot open a loop it would catch — it can
        // only make one less profitable.
        if (!EconomyValidator.hasErrors(issues) || policy == LoopPolicy.WARN_ONLY) {
            this.liveTable = candidate.withSpread(data.spreadPercent());
        } else {
            List<String> exploitable = EconomyValidator.exploitableItems(issues);
            CreditValueTable.Builder builder = CreditValueTable.builder();
            for (String itemId : candidate.itemIds()) {
                if (!exploitable.contains(itemId)) {
                    candidate.valueOf(itemId).ifPresent(value -> builder.put(itemId, value));
                }
            }
            this.liveTable = builder.build().withSpread(data.spreadPercent());
            LOGGER.error("[Economy] Disabled {} exploitable item(s): {}",
                    exploitable.size(), String.join(", ", exploitable));
        }

        for (EconomyIssue issue : issues) {
            if (issue.isError()) {
                LOGGER.error("[Economy] {}", issue.message());
            } else {
                LOGGER.warn("[Economy] {}", issue.message());
            }
        }
        if (issues.isEmpty()) {
            LOGGER.info("Economy ready: {} convertible items, {} priced recipes",
                    liveTable.size(), recipes.size());
        }
    }

    /**
     * Checks what would happen if an item were priced at this value, without
     * committing anything.
     * <p>
     * This is what turns a config mistake into a rejected command instead of a
     * console warning nobody reads until the damage is done.
     *
     * @return every issue the change would introduce; empty means it is safe
     */
    public List<EconomyIssue> previewConversion(MinecraftServer server,
                                                String itemId, long credits) {
        Map<String, Long> proposed = new LinkedHashMap<>();
        for (String existing : liveTable.itemIds()) {
            liveTable.valueOf(existing).ifPresent(value -> proposed.put(existing, value));
        }
        proposed.put(itemId, credits);

        CreditValueTable.Builder builder = CreditValueTable.builder();
        proposed.forEach(builder::put);
        CreditValueTable candidate = builder.build();

        List<EconomyIssue> found = new ArrayList<>(EconomyValidator.validate(
                candidate, RecipeScanner.scan(server, candidate)));
        found.addAll(EconomyValidator.validateShop(
                candidate, EconomyData.get(server).lowestShopPrices()));
        return List.copyOf(found);
    }

    /** Checks a proposed shop price against current conversion values. */
    public List<EconomyIssue> previewShopPrice(String itemId, long price) {
        return EconomyValidator.validateShop(liveTable, Map.of(itemId, price));
    }

    /**
     * Whether a value comes from a datapack rather than from a command.
     * <p>
     * The two sources look identical in the live table but behave nothing
     * alike: a datapack value cannot be removed at runtime because the next
     * reload would simply put it back. Telling a player their diamond "is not
     * listed" when it plainly is, because the command could not remove it, is
     * the kind of message that sends somebody hunting for a bug that is not
     * there.
     */
    public boolean isFromDatapack(String itemId) {
        return loader.table().contains(itemId);
    }

    public CreditValueTable table() {
        return liveTable;
    }

    public List<EconomyIssue> issues() {
        return issues;
    }

    public int pricedRecipeCount() {
        return recipes.size();
    }

    // --- Conversion ---------------------------------------------------------

    public boolean isConvertible(ItemStack stack) {
        return !stack.isEmpty() && liveTable.contains(ItemIds.idOf(stack));
    }

    /** Credits a whole stack is worth, or empty if it is not convertible. */
    public Optional<Long> valueOf(ItemStack stack) {
        if (stack.isEmpty()) {
            return Optional.empty();
        }
        return liveTable.valueOf(ItemIds.idOf(stack))
                .map(unit -> unit * stack.getCount());
    }

    /**
     * Turns credits back into items, splitting into stacks.
     * Leftover credits are reported, never dropped.
     */
    public Optional<Redemption> redeem(Item item, long credits) {
        String itemId = ItemIds.idOf(item);
        if (!liveTable.contains(itemId)) {
            return Optional.empty();
        }
        CreditValueTable.Purchase purchase = liveTable.itemsFor(itemId, credits);
        List<ItemStack> stacks = new ArrayList<>();
        long left = purchase.count();
        int maxStack = new ItemStack(item).getMaxStackSize();
        while (left > 0) {
            int size = (int) Math.min(left, maxStack);
            stacks.add(new ItemStack(item, size));
            left -= size;
        }
        return Optional.of(new Redemption(stacks, purchase.count(), purchase.remainder()));
    }

    /**
     * @param stacks    items to hand over, already split
     * @param count     total items
     * @param remainder credits that did not cover a whole item
     */
    public record Redemption(List<ItemStack> stacks, long count, long remainder) {
    }
}
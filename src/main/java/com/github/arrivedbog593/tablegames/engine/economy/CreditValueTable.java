package com.github.arrivedbog593.tablegames.engine.economy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What each item is worth in credits, and nothing more.
 * <p>
 * Conversion is one to one in both directions: an item worth ten credits
 * costs exactly ten credits to buy back. No spread, no house cut. The table
 * is a cashier, not a market.
 * <p>
 * Only listed items convert. An iron ingot being worth ten credits says
 * nothing about an iron block; if the block is not listed, it cannot be
 * traded at all. That default keeps a short config safe — every convertible
 * item is one someone deliberately typed.
 * <p>
 * Items are keyed by their registry id as plain text, e.g.
 * {@code "minecraft:iron_ingot"}. The engine never resolves these against a
 * registry, which is what keeps this class free of Minecraft and testable.
 */
public final class CreditValueTable {

    private final Map<String, Long> values;

    private CreditValueTable(Map<String, Long> values) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static CreditValueTable empty() {
        return new CreditValueTable(Map.of());
    }

    /** Credits for one of this item, or empty when it is not convertible. */
    public Optional<Long> valueOf(String itemId) {
        return Optional.ofNullable(values.get(itemId));
    }

    public boolean contains(String itemId) {
        return values.containsKey(itemId);
    }

    /** Every convertible item id, in declaration order. */
    public Set<String> itemIds() {
        return values.keySet();
    }

    public int size() {
        return values.size();
    }

    /**
     * Credits paid for a stack.
     *
     * @throws IllegalArgumentException if the item is not convertible
     */
    public long creditsFor(String itemId, int count) {
        if (count < 0) {
            throw new IllegalArgumentException("Negative count: " + count);
        }
        long unit = valueOf(itemId).orElseThrow(() ->
                new IllegalArgumentException("Item is not convertible: " + itemId));
        return unit * count;
    }

    /**
     * How many whole items a credit balance buys, and what is left over.
     * <p>
     * Credits are never rounded away: the remainder stays with the player.
     * Silently swallowing it would be a slow leak that players eventually
     * notice and read as theft.
     *
     * @throws IllegalArgumentException if the item is not convertible
     */
    public Purchase itemsFor(String itemId, long credits) {
        if (credits < 0) {
            throw new IllegalArgumentException("Negative credits: " + credits);
        }
        long unit = valueOf(itemId).orElseThrow(() ->
                new IllegalArgumentException("Item is not convertible: " + itemId));
        long count = credits / unit;
        return new Purchase(itemId, count, credits - count * unit);
    }

    /**
     * The result of turning credits back into items.
     *
     * @param itemId    what is being bought back
     * @param count     how many whole items the credits covered
     * @param remainder credits left over, returned to the player untouched
     */
    public record Purchase(String itemId, long count, long remainder) {
        public boolean isEmpty() {
            return count == 0;
        }
    }

    public static final class Builder {

        private final Map<String, Long> values = new LinkedHashMap<>();

        /**
         * Declares an item convertible.
         *
         * @throws IllegalArgumentException if the value is not positive or
         *                                  the item is already listed
         */
        public Builder put(String itemId, long credits) {
            Objects.requireNonNull(itemId, "itemId");
            if (credits <= 0) {
                throw new IllegalArgumentException(
                        "Credit value must be positive: " + itemId + " = " + credits);
            }
            if (values.putIfAbsent(itemId, credits) != null) {
                throw new IllegalArgumentException("Duplicate item: " + itemId);
            }
            return this;
        }

        public CreditValueTable build() {
            return new CreditValueTable(values);
        }
    }
}
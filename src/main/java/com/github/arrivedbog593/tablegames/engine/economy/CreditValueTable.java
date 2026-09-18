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
 * One figure per item, quoted in both directions: handing one in pays what
 * the table says, and taking one out costs the same, plus whatever surcharge
 * the house has set. That surcharge is a fee on undoing the trade rather
 * than a second price list — see {@link #spreadPercent}. With no surcharge
 * the two directions match exactly, which is how the table starts out.
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

    /** No surcharge: buying an item back costs exactly what it sold for. */
    public static final int NO_SPREAD = 0;

    private final Map<String, Long> values;

    /**
     * The percentage added when credits are turned back into items.
     * <p>
     * A transaction fee, not a revaluation: the item is still worth what the
     * table says, and selling it still pays that. The spread is what the
     * house charges to undo the trade — the same gap between buying and
     * selling that any real exchange takes, and the reason round-tripping a
     * stack of diamonds should not be free.
     * <p>
     * It is also the sink this economy was missing. Credits are minted every
     * time somebody sells an item and were only ever destroyed by the shop,
     * so a server that never set up a shop had no way to remove any. The
     * spread removes them on a transaction every server has.
     * <p>
     * Zero by default, because turning it on changes the economy of a server
     * that was running fine without it.
     */
    private final int spreadPercent;

    private CreditValueTable(Map<String, Long> values) {
        this(values, NO_SPREAD);
    }

    private CreditValueTable(Map<String, Long> values, int spreadPercent) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        this.spreadPercent = spreadPercent;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The same table with a different surcharge on buying items back. */
    public CreditValueTable withSpread(int spreadPercent) {
        if (spreadPercent < 0 || spreadPercent > MAX_SPREAD_PERCENT) {
            throw new IllegalArgumentException("Spread out of range: " + spreadPercent);
        }
        return new CreditValueTable(values, spreadPercent);
    }

    /**
     * As high as a spread may go.
     * <p>
     * Not a technical limit. Past doubling, buying anything back stops being
     * a fee and becomes a way to lock players out of their own deposits, and
     * a server that wants that should be made to think about it rather than
     * reach it with a typo.
     */
    public static final int MAX_SPREAD_PERCENT = 100;

    public static CreditValueTable empty() {
        return new CreditValueTable(Map.of());
    }

    /** The surcharge percentage on buying items back. */
    public int spreadPercent() {
        return spreadPercent;
    }

    /**
     * What one item costs to buy back, surcharge included.
     * <p>
     * Rounded up. Rounding down would let a spread of one percent on a cheap
     * item come out to no surcharge at all, which reads as the setting being
     * broken rather than as rounding.
     */
    public long buybackUnit(String itemId) {
        long unit = valueOf(itemId).orElseThrow(() ->
                new IllegalArgumentException("Item is not convertible: " + itemId));
        if (spreadPercent == NO_SPREAD) {
            return unit;
        }
        long scaled = Math.multiplyExact(unit, 100L + spreadPercent);
        return (scaled + 99) / 100;
    }

    /** What a whole stack costs to buy back. */
    public long buybackCost(String itemId, long count) {
        if (count < 0) {
            throw new IllegalArgumentException("Negative count: " + count);
        }
        return Math.multiplyExact(buybackUnit(itemId), count);
    }

    /**
     * The part of a buyback that is surcharge rather than value.
     * <p>
     * Separated out because it does not vanish: it is the house's take, and
     * the caller has to credit the bankroll with exactly this much or the
     * credits would simply cease to exist without anybody being paid.
     */
    public long surchargeOn(String itemId, long count) {
        return buybackCost(itemId, count) - Math.multiplyExact(valueOf(itemId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Item is not convertible: " + itemId)), count);
    }

    /** Credits for one of these items, or empty when it is not convertible. */
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
        return Math.multiplyExact(unit, count);
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
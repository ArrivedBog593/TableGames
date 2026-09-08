package com.github.arrivedbog593.tablegames.client;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * A catalog as one viewer has chosen to look at it.
 * <p>
 * Sorting and filtering happen entirely on the client, and that is the point.
 * The server sends the catalog once, in its own order; how a player wants it
 * arranged is nobody else's business and does not belong in a packet. Nothing
 * here can desynchronize, because there is nothing to synchronize.
 * <p>
 * That is only safe because a purchase quotes the entry's own identity — a
 * catalog number in the shop, an item id at the cashier — and never the row
 * it was drawn in. The two stopped being the same thing the moment this
 * existed, and the server never learns the difference.
 * <p>
 * Written against a generic entry so the player's screens and the admin
 * screens can share it rather than growing four subtly different sort orders.
 *
 * @param <T> whatever the screen is listing
 */
public final class CatalogView<T> {

    /** How a list can be arranged. */
    public enum SortBy {
        /** Catalog order, which is also the order things were added. */
        NUMBER,
        PRICE,
        NAME;

        public Component label() {
            return Component.translatable(
                    "tablegames.sort." + name().toLowerCase(Locale.ROOT));
        }
    }

    private final Function<T, ItemStack> stackOf;
    private final Function<T, Long> priceOf;

    /**
     * The orders this particular catalog can be put in.
     * <p>
     * Not every list has everyone. The cashier prices by item id and has no
     * catalog number at all, so offering "catalog order" there put a button
     * on screen that named something the data does not have — and quietly did
     * nothing, since the order it fell back to was the order the server
     * happened to send.
     */
    private final List<SortBy> options;

    private List<T> source = List.of();
    private List<T> view = List.of();

    private SortBy sortBy;
    private boolean descending;
    private String query = "";

    /** A catalog that can be arranged every way there is. */
    public CatalogView(Function<T, ItemStack> stackOf, Function<T, Long> priceOf) {
        this(stackOf, priceOf, List.of(SortBy.values()));
    }

    /**
     * A catalog offering only some orders.
     *
     * @param options at least one, in the order the button cycles through
     *                them. The first is what an unset preference falls back to
     */
    public CatalogView(Function<T, ItemStack> stackOf, Function<T, Long> priceOf,
                       List<SortBy> options) {
        if (options.isEmpty()) {
            throw new IllegalArgumentException("A catalog needs at least one sort order");
        }
        this.stackOf = stackOf;
        this.priceOf = priceOf;
        this.options = List.copyOf(options);
        this.sortBy = this.options.getFirst();
    }

    /** Replaces the underlying catalog, keeping the viewer's arrangement. */
    public void accept(List<T> entries) {
        this.source = List.copyOf(entries);
        rebuild();
    }

    public List<T> entries() {
        return view;
    }

    public int size() {
        return view.size();
    }

    public boolean isEmpty() {
        return view.isEmpty();
    }

    /** Whether a filter is hiding anything, which is worth saying on screen. */
    public boolean isFiltered() {
        return !query.isEmpty();
    }

    public SortBy sortBy() {
        return sortBy;
    }

    /** What the button cycles through, for a screen listing them in a tooltip. */
    public List<SortBy> options() {
        return options;
    }

    public boolean descending() {
        return descending;
    }

    /**
     * Restores an arrangement chosen the last time a screen was open.
     * <p>
     * An order this catalog does not offer falls back to the first one rather
     * than being honored. Preferences are shared between screens that do not
     * all list the same things, and a shop left in catalog order must not
     * leave the cashier sorted by a number it has none of.
     */
    public void restore(SortBy sortBy, boolean descending) {
        this.sortBy = sortBy != null && options.contains(sortBy) ? sortBy : options.getFirst();
        this.descending = descending;
        rebuild();
    }

    public void cycleSort() {
        sortBy = options.get((options.indexOf(sortBy) + 1) % options.size());
        rebuild();
    }

    public void toggleDirection() {
        descending = !descending;
        rebuild();
    }

    public void setQuery(String query) {
        this.query = query == null ? "" : query.trim();
        rebuild();
    }

    private void rebuild() {
        List<T> filtered = new ArrayList<>();
        String needle = query.toLowerCase(Locale.ROOT);
        for (T entry : source) {
            if (needle.isEmpty() || matches(entry, needle)) {
                filtered.add(entry);
            }
        }
        // Catalog order needs no sorting at all: the filter walks the source
        // in order, so what comes out is already in it. Expressing that as a
        // comparator over indexOf would have been quadratic for no reason.
        if (sortBy != SortBy.NUMBER) {
            filtered.sort(comparator());
        }
        if (descending) {
            filtered = filtered.reversed();
        }
        view = List.copyOf(filtered);
    }

    /**
     * Whether an entry answers to a search.
     * <p>
     * Matches the display name and the registry id, because people search
     * both ways: somebody after a netherite sword may type "sword" or may
     * type "netherite", and one of those is only in the id.
     */
    private boolean matches(T entry, String needle) {
        ItemStack stack = stackOf.apply(entry);
        if (stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        return stack.getItem().toString().toLowerCase(Locale.ROOT).contains(needle);
    }

    private Comparator<T> comparator() {
        return switch (sortBy) {
            case NUMBER -> throw new IllegalStateException("Catalog order is not sorted");
            case PRICE -> Comparator.comparingLong(priceOf::apply);
            case NAME -> Comparator.comparing(entry ->
                            stackOf.apply(entry).getHoverName().getString(),
                    String.CASE_INSENSITIVE_ORDER);
        };
    }
}

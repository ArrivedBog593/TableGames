package com.github.arrivedbog593.tablegames.client;

/**
 * How this player likes their screens, remembered between openings.
 * <p>
 * Client side and nothing else. None of it reaches the server, none of it is
 * anybody's business but the person looking at the screen, and losing it
 * costs nothing worse than retyping a word.
 * <p>
 * Kept for as long as the game is running, not written to disk. Persisting it
 * would mean a config file, and a config file for "what did I last type in a
 * search box" is a heavier thing than the problem — reopening a shop twice in
 * a row is the case this exists for, not reopening it next week.
 * <p>
 * Remembering is opt-out rather than absent, because a search that survives
 * being reopened is helpful right up until the moment a player forgets it is
 * there and thinks the shop has gone half empty. The toggle is what makes
 * that recoverable without having to explain it.
 */
public final class ScreenPreferences {

    private ScreenPreferences() {
    }

    private static boolean rememberSearch = true;
    private static String lastShopSearch = "";

    /**
     * The arrangement, always remembered.
     * <p>
     * Unlike the search text, which the toggle governs. A query hides most of
     * the shop and is easy to forget having typed; an order hides nothing and
     * is a settled preference, so restoring it needs no permission, and having
     * to pick "by price" on every visit would only be tedious.
     */
    private static CatalogView.SortBy shopSort = CatalogView.SortBy.NUMBER;
    private static boolean shopSortDescending;

    public static CatalogView.SortBy shopSort() {
        return shopSort;
    }

    public static boolean shopSortDescending() {
        return shopSortDescending;
    }

    public static void setShopSort(CatalogView.SortBy sortBy, boolean descending) {
        shopSort = sortBy;
        shopSortDescending = descending;
    }

    /** Whether a reopened screen should restore what was last searched for. */
    public static boolean rememberSearch() {
        return rememberSearch;
    }

    public static void setRememberSearch(boolean remember) {
        rememberSearch = remember;
        if (!remember) {
            // Forgetting has to take effect now, not at the next opening.
            // Leaving the old query stored while the toggle says otherwise is
            // the kind of thing that comes back when somebody turns it on
            // again and finds a word they typed an hour ago.
            lastShopSearch = "";
        }
    }

    /** What to put back in the shop's search field, or empty for none. */
    public static String shopSearch() {
        return rememberSearch ? lastShopSearch : "";
    }

    public static void setShopSearch(String query) {
        if (rememberSearch) {
            lastShopSearch = query == null ? "" : query;
        }
    }
}
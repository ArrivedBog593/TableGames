package com.github.arrivedbog593.tablegames.client;

import com.github.arrivedbog593.tablegames.platform.network.ShopCatalogPayload;

import java.util.List;

/**
 * The shop catalogue the server last sent, for the screen to draw.
 * <p>
 * Client only, and deliberately a plain holder: it is display data with no
 * authority. Editing it locally changes what a player sees and nothing else,
 * because prices are looked up again on the server for every purchase.
 */
public final class ClientShopState {

    private static List<ShopCatalogPayload.Entry> entries = List.of();

    private ClientShopState() {
    }

    public static void accept(ShopCatalogPayload payload) {
        entries = payload.entries();
    }

    public static List<ShopCatalogPayload.Entry> entries() {
        return entries;
    }
}

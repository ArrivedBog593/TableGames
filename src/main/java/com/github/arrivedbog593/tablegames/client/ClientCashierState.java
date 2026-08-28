package com.github.arrivedbog593.tablegames.client;

import com.github.arrivedbog593.tablegames.platform.network.CashierCatalogPayload;

import java.util.List;

/**
 * The catalogue the server last sent, for the cashier screen to draw.
 * <p>
 * Client only, and deliberately a plain holder: it is display data with no
 * authority whatsoever. Editing it locally changes what a player sees and
 * nothing else, because every action is revalidated on the server against the
 * real table.
 */
public final class ClientCashierState {

    private static List<CashierCatalogPayload.Entry> entries = List.of();

    private ClientCashierState() {
    }

    public static void accept(CashierCatalogPayload payload) {
        entries = payload.entries();
    }

    public static List<CashierCatalogPayload.Entry> entries() {
        return entries;
    }
}

package com.github.arrivedbog593.tablegames.platform.network;

import com.github.arrivedbog593.tablegames.platform.menu.AdminCashierMenu;
import com.github.arrivedbog593.tablegames.platform.menu.AdminShopMenu;
import com.github.arrivedbog593.tablegames.platform.menu.CashierMenu;
import com.github.arrivedbog593.tablegames.platform.menu.ShopMenu;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Tells everyone with a catalog open that it changed.
 * <p>
 * The catalogs are sent once, when a screen opens, which is correct right up
 * until a second person edits one. Two administrators configuring the same
 * shop each held their own snapshot: one removed entry three, the other
 * repriced what their screen still called entry four, and repriced the wrong
 * item — because entry numbers are positions in a list and everything after a
 * removal moves up one. The prices a player was browsing went stale the same
 * way, and their purchase quoted a number that no longer meant what the
 * screen had drawn.
 * <p>
 * Sending on change rather than on a timer, and only to players who actually
 * have one of these screens open, keeps this to a few hundred bytes at the
 * moment somebody types a command.
 * <p>
 * This narrows the window rather than closing it: a purchase already in
 * flight when the catalog changes still arrives quoting the old numbering.
 * That is why the server re-reads the catalog on every purchase instead of
 * trusting what the client sent, and why nothing here may be treated as a
 * substitute for that check.
 * <p>
 * Server thread only.
 */
public final class CatalogSync {

    private CatalogSync() {
    }

    /**
     * Pushes the current catalogs to whoever is looking at one.
     * <p>
     * Both catalogs, because a single edit can move both: a conversion value
     * changes what the cashier pays, and the exploit check can disable an
     * item that the shop was selling. Sorting out which screens a given edit
     * could possibly have touched would be one more thing to get wrong for no
     * saving worth having.
     */
    public static void refresh(MinecraftServer server) {
        if (server == null) {
            return;
        }
        ShopCatalogPayload shop = null;
        CashierCatalogPayload cashier = null;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            AbstractContainerMenu open = player.containerMenu;
            if (open instanceof ShopMenu || open instanceof AdminShopMenu) {
                // Built at most once per refresh and shared by every
                // recipient: a payload is immutable and is serialized again
                // for each send.
                if (shop == null) {
                    shop = ShopCatalogPayload.current(server);
                }
                PacketDistributor.sendToPlayer(player, shop);
            } else if (open instanceof CashierMenu || open instanceof AdminCashierMenu) {
                if (cashier == null) {
                    cashier = CashierCatalogPayload.current();
                }
                PacketDistributor.sendToPlayer(player, cashier);
            }
        }
    }
}

package com.github.arrivedbog593.tablegames.economy.platform.registry;

import com.github.arrivedbog593.tablegames.economy.platform.EconomyModule;
import com.github.arrivedbog593.tablegames.economy.platform.item.AdminKeyItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class EconomyItems {

    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(EconomyModule.MOD_ID);

    public static final DeferredItem<BlockItem> CASHIER =
            ITEMS.registerSimpleBlockItem("cashier", EconomyBlocks.CASHIER, new Item.Properties());

    public static final DeferredItem<BlockItem> SHOP =
            ITEMS.registerSimpleBlockItem("shop", EconomyBlocks.SHOP, new Item.Properties());

    /**
     * Opens a casino block's settings instead of using it.
     * <p>
     * Deliberately not craftable. It is issued with a command, bound to the
     * player it is issued to, and useless to anybody else — a recipe would
     * make all of that decoration.
     * <p>
     * Here rather than with the tables because what it proves is a staff
     * rank, and the staff list is the economy's. The tables accept it too:
     * they depend on the economy, so they may.
     */
    public static final DeferredItem<AdminKeyItem> ADMIN_KEY =
            ITEMS.register("admin_key", () -> new AdminKeyItem(new Item.Properties()));

    private EconomyItems() {
    }

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }
}

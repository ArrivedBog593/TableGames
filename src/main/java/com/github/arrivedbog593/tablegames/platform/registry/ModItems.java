package com.github.arrivedbog593.tablegames.platform.registry;

import com.github.arrivedbog593.tablegames.TableGames;
import com.github.arrivedbog593.tablegames.platform.item.AdminKeyItem;
import com.github.arrivedbog593.tablegames.platform.item.TableKeyItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Items the mod adds. */
public final class ModItems {

    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(TableGames.MOD_ID);

    public static final DeferredItem<BlockItem> TABLE =
            ITEMS.registerSimpleBlockItem("table", ModBlocks.TABLE, new Item.Properties());

    public static final DeferredItem<BlockItem> CASHIER =
            ITEMS.registerSimpleBlockItem("cashier", ModBlocks.CASHIER, new Item.Properties());

    public static final DeferredItem<BlockItem> SHOP =
            ITEMS.registerSimpleBlockItem("shop", ModBlocks.SHOP, new Item.Properties());

    public static final DeferredItem<BlockItem> SLOT_MACHINE =
            ITEMS.registerSimpleBlockItem("slot_machine", ModBlocks.SLOT_MACHINE, new Item.Properties());

    /**
     * Opens a casino block's settings instead of using it.
     * <p>
     * Deliberately not craftable. It is issued with a command, bound to the
     * player it is issued to, and useless to anybody else — a recipe would
     * make all of that decoration.
     */
    public static final DeferredItem<AdminKeyItem> ADMIN_KEY =
            ITEMS.register("admin_key", () -> new AdminKeyItem(new Item.Properties()));

    /**
     * Opens a table's settings, for anybody allowed to change them.
     * <p>
     * No recipe yet; the ingredients are still to be decided.
     */
    public static final DeferredItem<TableKeyItem> TABLE_KEY =
            ITEMS.register("table_key", () -> new TableKeyItem(new Item.Properties()));

    private ModItems() {
    }

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }
}

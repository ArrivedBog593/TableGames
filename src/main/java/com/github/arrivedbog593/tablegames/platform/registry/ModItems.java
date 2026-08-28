package com.github.arrivedbog593.tablegames.platform.registry;

import com.github.arrivedbog593.tablegames.TableGames;
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

    private ModItems() {
    }

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }
}

package com.github.arrivedbog593.tablegames.platform.registry;

import com.github.arrivedbog593.tablegames.TableGames;
import com.github.arrivedbog593.tablegames.platform.block.CashierBlock;
import com.github.arrivedbog593.tablegames.platform.block.ShopBlock;
import com.github.arrivedbog593.tablegames.platform.block.TableBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Blocks the mod adds.
 * <p>
 * Tables are one block for every game, since a table's game lives in its
 * block entity. The cashier and the shop are separate because neither is a
 * game: they have no players, no turns and no outcome.
 * <p>
 * The cashier and the shop are also separate from each other, and
 * deliberately so. The cashier is symmetric — items in, the same items back
 * out at the same rate. The shop only takes credits. Merging them into one
 * counter would hide that difference, and it is the difference that keeps the
 * economy stable.
 */
public final class ModBlocks {

    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(TableGames.MOD_ID);

    /**
     * The one table block, configured after placement.
     * <p>
     * Not flammable despite being wooden: a casino floor burning down because
     * somebody brought a flint and steel is a support ticket nobody wants.
     */
    public static final DeferredBlock<TableBlock> TABLE = BLOCKS.registerBlock(
            "table",
            TableBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GREEN)
                    .strength(2.5F, 6.0F)
                    .sound(SoundType.WOOD)
                    .noOcclusion());

    /** Exchanges items for credits and back, at the same rate both ways. */
    public static final DeferredBlock<CashierBlock> CASHIER = BLOCKS.registerBlock(
            "cashier",
            CashierBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.GOLD)
                    .strength(3.5F, 9.0F)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops());

    /** Sells goods for credits, and never buys them back. */
    public static final DeferredBlock<ShopBlock> SHOP = BLOCKS.registerBlock(
            "shop",
            ShopBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.TERRACOTTA_CYAN)
                    .strength(3.5F, 9.0F)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops());

    private ModBlocks() {
    }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
    }

    public static Block table() {
        return TABLE.get();
    }

    public static Block cashier() {
        return CASHIER.get();
    }

    public static Block shop() {
        return SHOP.get();
    }
}

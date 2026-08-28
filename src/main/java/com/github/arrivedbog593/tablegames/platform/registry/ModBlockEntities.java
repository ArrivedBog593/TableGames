package com.github.arrivedbog593.tablegames.platform.registry;

import com.github.arrivedbog593.tablegames.TableGames;
import com.github.arrivedbog593.tablegames.platform.block.CashierBlockEntity;
import com.github.arrivedbog593.tablegames.platform.block.ShopBlockEntity;
import com.github.arrivedbog593.tablegames.platform.block.TableBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Block entity types the mod adds. */
public final class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, TableGames.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TableBlockEntity>> TABLE =
            BLOCK_ENTITIES.register("table", () -> BlockEntityType.Builder
                    .of(TableBlockEntity::new, ModBlocks.table())
                    .build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CashierBlockEntity>> CASHIER =
            BLOCK_ENTITIES.register("cashier", () -> BlockEntityType.Builder
                    .of(CashierBlockEntity::new, ModBlocks.cashier())
                    .build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ShopBlockEntity>> SHOP =
            BLOCK_ENTITIES.register("shop", () -> BlockEntityType.Builder
                    .of(ShopBlockEntity::new, ModBlocks.shop())
                    .build(null));

    private ModBlockEntities() {
    }

    public static void register(IEventBus modEventBus) {
        BLOCK_ENTITIES.register(modEventBus);
    }
}

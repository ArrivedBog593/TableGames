package com.github.arrivedbog593.tablegames.economy.platform.registry;

import com.github.arrivedbog593.tablegames.economy.platform.EconomyModule;
import com.github.arrivedbog593.tablegames.economy.platform.block.CashierBlockEntity;
import com.github.arrivedbog593.tablegames.economy.platform.block.ShopBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class EconomyBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, EconomyModule.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CashierBlockEntity>> CASHIER =
            BLOCK_ENTITIES.register("cashier", () -> BlockEntityType.Builder
                    .of(CashierBlockEntity::new, EconomyBlocks.cashier())
                    .build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ShopBlockEntity>> SHOP =
            BLOCK_ENTITIES.register("shop", () -> BlockEntityType.Builder
                    .of(ShopBlockEntity::new, EconomyBlocks.shop())
                    .build(null));

    private EconomyBlockEntities() {
    }

    public static void register(IEventBus modEventBus) {
        BLOCK_ENTITIES.register(modEventBus);
    }
}

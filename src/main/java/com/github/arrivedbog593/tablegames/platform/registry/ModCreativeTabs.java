package com.github.arrivedbog593.tablegames.platform.registry;

import com.github.arrivedbog593.tablegames.TableGames;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** The mod's creative tab. */
public final class ModCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, TableGames.MOD_ID);

    @SuppressWarnings("unused")
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN =
            TABS.register("main", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.tablegames"))
                    .icon(() -> new ItemStack(ModItems.TABLE.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(ModItems.TABLE.get());
                        output.accept(ModItems.CASHIER.get());
                        output.accept(ModItems.SHOP.get());
                        // Unbound, so it works for operators and nobody else.
                        // Anyone administering without operator rights gets a
                        // bound one from the command instead.
                        output.accept(ModItems.ADMIN_KEY.get());
                    })
                    .build());

    private ModCreativeTabs() {
    }

    public static void register(IEventBus modEventBus) {
        TABS.register(modEventBus);
    }
}

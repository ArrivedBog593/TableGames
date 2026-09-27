package com.github.arrivedbog593.tablegames.client;

import com.github.arrivedbog593.tablegames.TableGames;
import com.github.arrivedbog593.tablegames.platform.block.SlotMachineBlockEntity;
import com.github.arrivedbog593.tablegames.platform.block.TableBlockEntity;
import com.github.arrivedbog593.tablegames.platform.registry.ModMenus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/**
 * Client-only wiring.
 * <p>
 * Everything here is gated on {@link Dist#CLIENT}. A dedicated server has no
 * screens on its classpath at all, so loading this class there would crash
 * before the world ever opened.
 * <p>
 * Only the cashier and the shop appear here. Those really are containers —
 * they move items — so they get menus. Table games open through
 * {@link TableScreens} instead.
 */
@EventBusSubscriber(modid = TableGames.MOD_ID, value = Dist.CLIENT)
public final class ClientSetup {

    private ClientSetup() {
    }

    /** Lets the slot machines and wheels this client can see be heard. */
    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            SlotMachineBlockEntity.listen(CabinetSounds.INSTANCE);
            TableBlockEntity.listenToWheels(WheelSounds.INSTANCE);
        });
    }

    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.CASHIER.get(), CashierScreen::new);
        event.register(ModMenus.SHOP.get(), ShopScreen::new);
        event.register(ModMenus.ADMIN_SHOP.get(), AdminShopScreen::new);
        event.register(ModMenus.ADMIN_CASHIER.get(), AdminCashierScreen::new);
    }
}

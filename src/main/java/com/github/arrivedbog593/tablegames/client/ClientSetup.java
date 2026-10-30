package com.github.arrivedbog593.tablegames.client;

import com.github.arrivedbog593.tablegames.TableGames;
import com.github.arrivedbog593.tablegames.platform.block.SlotMachineBlockEntity;
import com.github.arrivedbog593.tablegames.platform.block.TableBlockEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Client-only wiring.
 * <p>
 * Everything here is gated on {@link Dist#CLIENT}. A dedicated server has no
 * screens on its classpath at all, so loading this class there would crash
 * before the world ever opened.
 * <p>
 * No menu screens are registered here. Table games are not containers and
 * open through {@link TableScreens}; the cashier and the shop, which are,
 * belong to the economy and register their own.
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
}

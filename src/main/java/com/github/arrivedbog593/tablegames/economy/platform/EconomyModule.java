package com.github.arrivedbog593.tablegames.economy.platform;

import com.github.arrivedbog593.tablegames.economy.platform.registry.EconomyBlockEntities;
import com.github.arrivedbog593.tablegames.economy.platform.registry.EconomyBlocks;
import com.github.arrivedbog593.tablegames.economy.platform.registry.EconomyItems;
import com.github.arrivedbog593.tablegames.economy.platform.registry.EconomyMenus;
import net.neoforged.bus.api.IEventBus;

/**
 * The economy's half of the mod: what its own {@code @Mod} class will do once
 * it has one.
 * <p>
 * The economy is on its way into a mod of its own, which TableGames will then
 * depend on. Until it gets there it is registered from here, and TableGames
 * calls {@link #register} the way the loader will call the new mod's
 * constructor. Nothing in the economy may name anything in TableGames —
 * {@code EconomyBoundaryTest} fails the build if it does — so moving it out
 * should be a matter of moving the package.
 */
public final class EconomyModule {

    /**
     * Still TableGames' own id, so the cashier, the shop and the admin key
     * keep the ids worlds already know them by. It changes when the economy
     * becomes its own mod, and those ids change with it.
     */
    public static final String MOD_ID = "tablegames";

    private EconomyModule() {
    }

    public static void register(IEventBus modEventBus) {
        EconomyBlocks.register(modEventBus);
        EconomyItems.register(modEventBus);
        EconomyBlockEntities.register(modEventBus);
        EconomyMenus.register(modEventBus);
    }
}

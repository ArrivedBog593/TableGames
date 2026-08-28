package com.github.arrivedbog593.tablegames;

import com.github.arrivedbog593.tablegames.platform.game.Games;
import com.github.arrivedbog593.tablegames.platform.registry.ModBlockEntities;
import com.github.arrivedbog593.tablegames.platform.registry.ModBlocks;
import com.github.arrivedbog593.tablegames.platform.registry.ModCreativeTabs;
import com.github.arrivedbog593.tablegames.platform.registry.ModItems;
import com.github.arrivedbog593.tablegames.platform.registry.ModMenus;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/**
 * Mod entry point.
 * <p>
 * Deliberately thin. Lifecycle wiring lives with the systems it belongs to —
 * see {@code platform.economy.EconomyEvents} and
 * {@code platform.network.ModPayloads} — so this class does not grow into a
 * dumping ground as games are added.
 */
@Mod(TableGames.MOD_ID)
public final class TableGames {

    /**
     * Registry namespace and assets folder. Changing this breaks saved worlds
     * and every datapack written against the mod.
     */
    public static final String MOD_ID = "tablegames";

    public TableGames(IEventBus modEventBus, ModContainer container) {
        ModBlocks.register(modEventBus);
        ModItems.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModMenus.register(modEventBus);
        ModCreativeTabs.register(modEventBus);

        // Games are plain Java and need no registry event, but they must exist
        // before any table tries to resolve its saved game id.
        Games.bootstrap();
    }
}

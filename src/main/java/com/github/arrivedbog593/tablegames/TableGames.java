package com.github.arrivedbog593.tablegames;

import com.github.arrivedbog593.economycore.api.Bank;
import com.github.arrivedbog593.tablegames.engine.house.HouseTransactions;
import com.github.arrivedbog593.tablegames.platform.game.Games;
import com.github.arrivedbog593.tablegames.platform.house.OutcomeSettler;
import com.github.arrivedbog593.tablegames.platform.registry.ModBlockEntities;
import com.github.arrivedbog593.tablegames.platform.registry.ModBlocks;
import com.github.arrivedbog593.tablegames.platform.registry.ModCreativeTabs;
import com.github.arrivedbog593.tablegames.platform.registry.ModItems;
import com.github.arrivedbog593.tablegames.platform.registry.ModSounds;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/**
 * Mod entry point.
 * <p>
 * Deliberately thin. Lifecycle wiring lives with the systems it belongs to —
 * see {@code platform.house.HouseEvents} and
 * {@code platform.network.ModPayloads} — so this class does not grow into a
 * dumping ground as games are added.
 */
@Mod(TableGames.MOD_ID)
public final class TableGames {

    /**
     * Registry namespace and assets folder. Changing these breaks saved worlds
     * and every datapack written against the mod.
     */
    public static final String MOD_ID = "tablegames";

    @SuppressWarnings("unused")
    public TableGames(IEventBus modEventBus, ModContainer container) {
        ModBlocks.register(modEventBus);
        ModItems.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModCreativeTabs.register(modEventBus);
        ModSounds.register(modEventBus);

        // Games are plain Java and need no registry event, but they must exist
        // before any table tries to resolve its saved game id.
        Games.bootstrap();
        // Before the server starts: crash recovery reads the log then, and a
        // wager nobody had registered yet would not be replayed.
        HouseTransactions.bootstrap();
        // The house pays out of the server bank, so it switches the bank on —
        // and with it, what the server takes in funds the tables, unless the
        // owner configured otherwise. What the tables have riding on live
        // rounds is promised out of it, so staff cannot withdraw it.
        Bank.enable(MOD_ID);
        Bank.promise(MOD_ID, () -> OutcomeSettler.exposure().total());
    }
}

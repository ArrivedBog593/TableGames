package com.github.arrivedbog593.tablegames.platform.house;

import com.github.arrivedbog593.tablegames.TableGames;
import com.github.arrivedbog593.tablegames.platform.command.HelpCommands;
import com.github.arrivedbog593.tablegames.platform.command.HouseCommands;
import com.github.arrivedbog593.tablegames.platform.command.TableCommands;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * Wires the casino into the server lifecycle: its commands, and the house's
 * record of what the tables have at risk.
 * <p>
 * The economy wires itself in {@code EconomyEvents} — the ledger, the log,
 * crash recovery, the price list. This is only what the casino adds on top.
 */
@EventBusSubscriber(modid = TableGames.MOD_ID)
public final class HouseEvents {

    private HouseEvents() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        // Belt and braces with the clear on shutdown: a crash never runs the
        // stop hook, and a stale commitment would silently narrow every
        // table's limits with nothing left to release it.
        OutcomeSettler.exposure().clear();
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        HelpCommands.register(event.getDispatcher());
        TableCommands.register(event.getDispatcher());
        HouseCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        // Table commitments cannot outlive the rounds that made them, and
        // rounds are never saved. Left behind, they would shrink every
        // table's limit in the next world opened in the same process — the
        // registry is static, so a single-player session carries it across.
        OutcomeSettler.exposure().clear();
    }
}

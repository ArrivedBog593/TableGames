package com.github.arrivedbog593.tablegames.platform.economy;

import com.github.arrivedbog593.tablegames.TableGames;
import com.github.arrivedbog593.tablegames.engine.economy.TransactionRecord;
import com.github.arrivedbog593.tablegames.engine.economy.TransactionType;
import com.github.arrivedbog593.tablegames.platform.command.CreditCommands;
import com.github.arrivedbog593.tablegames.platform.command.EconomyCommands;
import com.github.arrivedbog593.tablegames.platform.command.HouseCommands;
import com.github.arrivedbog593.tablegames.platform.command.TableCommands;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.util.UUID;

/**
 * Wires the economy into the server lifecycle.
 * <p>
 * The ordering is the part that matters, and it is not obvious:
 * <ul>
 *   <li>{@link AddReloadListenerEvent} is where the conversion JSON gets
 *       read, but recipes are still being rebuilt at that point.</li>
 *   <li>{@link ServerStartedEvent} is the first moment recipes exist, so the
 *       initial exploit check happens there. Crash recovery also runs here,
 *       and must come before the log is rotated, since rotation archives the
 *       very file recovery reads.</li>
 *   <li>{@link OnDatapackSyncEvent} fires once a {@code /reload} has
 *       finished. Any mod that ships recipes as data — including ones that
 *       let admins edit recipes on a live server — can change the crafting
 *       graph out from under a validated table, so the check runs again.</li>
 * </ul>
 */
@EventBusSubscriber(modid = TableGames.MOD_ID)
public final class EconomyEvents {

    private static final ConversionTableLoader LOADER = new ConversionTableLoader();
    private static final EconomyManager MANAGER =
            new EconomyManager(LOADER, EconomyManager.LoopPolicy.DISABLE_ITEM);

    private static TransactionLog transactionLog;

    private EconomyEvents() {
    }

    public static EconomyManager economy() {
        return MANAGER;
    }

    /** Null until the server has started. */
    public static TransactionLog transactionLog() {
        return transactionLog;
    }

    /**
     * Writes a movement to the log and advances the saved sequence in one
     * step.
     * <p>
     * These two must never drift apart, and this method exists so they
     * cannot. The sequence lives in the same file as the balances, so both
     * reach disk in the same save: any line numbered above it is by
     * definition a movement that did not survive, which is exactly what
     * recovery replays.
     * <p>
     * Logging without advancing the sequence is the subtle version of the
     * bug. The balances save correctly, the sequence stays behind, and the
     * next start replays movements that were already applied — silently
     * doubling them. Never call {@code transactionLog().record(...)}
     * directly; always come through here.
     */
    public static void record(CreditStorage storage, TransactionType type, UUID owner,
                              long delta, long balance, String detail) {
        if (transactionLog == null) {
            return;
        }
        long sequence = transactionLog.record(type, owner, delta, balance, detail);
        storage.setLastSequence(sequence);
    }

    /** As {@link #record}, for movements of the house bank. */
    public static void recordHouse(CreditStorage storage, TransactionType type,
                                   long delta, long balance, String detail) {
        record(storage, type, TransactionRecord.HOUSE, delta, balance, detail);
    }

    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(LOADER);
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        CreditStorage storage = CreditStorage.get(server);

        // Recovery first: it reads the previous run's log, which opening the
        // new one archives.
        TransactionRecovery.Report report = TransactionRecovery.run(server, storage);
        transactionLog = TransactionLog.open(server, storage.lastSequence());
        if (report.didAnything()) {
            storage.setDirty();
        }

        MANAGER.rebuild(server);
    }

    /**
     * Revalidates after a datapack reload.
     * <p>
     * This event fires twice for different reasons: once with a null player
     * after {@code /reload} completes, and once per player as they join. Only
     * the first is a real data change; rebuilding on every login would scan
     * every recipe in the game each time somebody connects.
     */
    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        if (event.getPlayer() != null) {
            return;
        }
        MANAGER.rebuild(event.getPlayerList().getServer());
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CreditCommands.register(event.getDispatcher());
        EconomyCommands.register(event.getDispatcher());
        TableCommands.register(event.getDispatcher());
        HouseCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        MinecraftServer server = event.getServer();
        CreditStorage storage = CreditStorage.get(server);

        if (transactionLog != null) {
            storage.setLastSequence(transactionLog.currentSequence());
            transactionLog.close();
            transactionLog = null;
        }
        storage.setDirty();
        EconomyData.get(server).setDirty();
    }
}
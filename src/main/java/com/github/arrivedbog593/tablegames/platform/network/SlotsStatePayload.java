package com.github.arrivedbog593.tablegames.platform.network;

import com.github.arrivedbog593.tablegames.TableGames;
import com.github.arrivedbog593.tablegames.engine.games.slots.Payline;
import com.github.arrivedbog593.tablegames.engine.games.slots.Reel;
import com.github.arrivedbog593.tablegames.engine.games.slots.SlotMachine;
import com.github.arrivedbog593.tablegames.engine.games.slots.SlotSymbol;
import com.github.arrivedbog593.tablegames.engine.games.slots.SlotsGame;
import com.github.arrivedbog593.tablegames.engine.table.BuyIn;
import com.github.arrivedbog593.tablegames.engine.table.RoundPhase;
import com.github.arrivedbog593.tablegames.platform.block.GameBlockEntity;
import com.github.arrivedbog593.tablegames.platform.block.SlotCabinet;
import com.github.arrivedbog593.tablegames.platform.economy.CreditStorage;
import com.github.arrivedbog593.tablegames.platform.economy.OutcomeSettler;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A slot machine as the player standing at it sees it.
 * <p>
 * Everything here is addressed to one recipient, because a machine seats
 * one. There is no public half the way roulette has one: nobody else's
 * wagers are on this cabinet, and nobody else's spin lands on it.
 * <p>
 * The reels arrive as what they are showing, not as where they stopped. A
 * client that knew the strips and the stop positions would know what the
 * next pull could land before the lever came back up, and a client that is
 * merely told nine symbols cannot work backwards to the machine.
 *
 * @param machine how this cabinet is set up and what it is doing
 * @param spin    the spin on screen, if one is
 * @param funds   what the viewer has and what of it is spoken for
 */
public record SlotsStatePayload(MachineView machine, SpinView spin, PlayerFunds funds)
        implements CustomPacketPayload {

    /** Nine symbols: three reels of three rows. */
    public static final int WINDOW_SIZE = SlotMachine.REELS * Reel.ROWS;

    public static final Type<SlotsStatePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TableGames.MOD_ID, "slots_state"));

    /**
     * The cabinet itself: how it is set up, and where the pull it is in the
     * middle of has got to.
     *
     * @param phase      ordinal of the {@link RoundPhase}
     * @param rolling    whether the reels are still turning
     * @param seated     whether the viewer holds the seat
     * @param betMinimum the least a line may be played for
     * @param betMaximum the most, zero when only the bankroll decides
     * @param payback    the return this machine is set to, in whole percent
     */
    public record MachineView(int phase, boolean rolling, boolean seated,
                              long betMinimum, long betMaximum, String payback) {

        public static final StreamCodec<ByteBuf, MachineView> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, MachineView::phase,
                ByteBufCodecs.BOOL, MachineView::rolling,
                ByteBufCodecs.BOOL, MachineView::seated,
                ByteBufCodecs.VAR_LONG, MachineView::betMinimum,
                ByteBufCodecs.VAR_LONG, MachineView::betMaximum,
                ByteBufCodecs.STRING_UTF8, MachineView::payback,
                MachineView::new);

        public RoundPhase roundPhase() {
            RoundPhase[] all = RoundPhase.values();
            return all[Math.clamp(phase, 0, all.length - 1)];
        }
    }

    /**
     * One spin as the screen draws it.
     * <p>
     * Empty when the machine is idle, which is what
     * {@link #hasResult()} answers.
     *
     * @param window      what shows, reel by reel and top row first, as
     *                    {@link SlotSymbol} ordinals; empty when idle
     * @param winningLines {@link Payline} ordinals that paid
     * @param won         what the spin paid
     * @param prizes      the prize bank: won and not yet gambled again
     * @param freeLines   lines the owed free spin must be played on, zero for none
     * @param freePerLine what that free spin is staked at
     */
    public record SpinView(List<Integer> window, List<Integer> winningLines, long won,
                           long prizes, int freeLines, long freePerLine) {

        public static final StreamCodec<ByteBuf, SpinView> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(WINDOW_SIZE)), SpinView::window,
                ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(Payline.MAX)),
                SpinView::winningLines,
                ByteBufCodecs.VAR_LONG, SpinView::won,
                ByteBufCodecs.VAR_LONG, SpinView::prizes,
                ByteBufCodecs.VAR_INT, SpinView::freeLines,
                ByteBufCodecs.VAR_LONG, SpinView::freePerLine,
                SpinView::new);

        /** Whether there is anything to take out without leaving the machine. */
        public boolean hasPrizes() {
            return prizes > 0;
        }

        public boolean hasResult() {
            return window.size() == WINDOW_SIZE;
        }

        public boolean owesAFreeSpin() {
            return freeLines > 0;
        }

        /** What the given reel and row is showing, or null when the machine is idle. */
        public SlotSymbol symbolAt(int reel, int row) {
            if (!hasResult()) {
                return null;
            }
            SlotSymbol[] all = SlotSymbol.values();
            int ordinal = window.get(reel * Reel.ROWS + row);
            return all[Math.clamp(ordinal, 0, all.length - 1)];
        }

        public boolean paid(Payline line) {
            return winningLines.contains(line.ordinal());
        }
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, SlotsStatePayload> STREAM_CODEC =
            StreamCodec.composite(
                    MachineView.STREAM_CODEC, SlotsStatePayload::machine,
                    SpinView.STREAM_CODEC, SlotsStatePayload::spin,
                    PlayerFunds.STREAM_CODEC, SlotsStatePayload::funds,
                    SlotsStatePayload::new);

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** A machine doing nothing, for a screen that has not heard from one yet. */
    public static SlotsStatePayload idle() {
        return new SlotsStatePayload(
                new MachineView(RoundPhase.IDLE.ordinal(), false, false, 1, 0, ""),
                new SpinView(List.of(), List.of(), 0, 0, 0, 0),
                new PlayerFunds(0, 0, 0, 0, 0, 1));
    }

    /** Whether the viewer holds the machine's one seat. */
    public boolean isSeated() {
        return machine.seated();
    }

    public static void handleOnClient(SlotsStatePayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
                com.github.arrivedbog593.tablegames.client.ClientSlotsState.accept(payload));
    }

    // --- Building it --------------------------------------------------------------------

    /** Snapshots a machine for the player at it. Server side only. */
    public static SlotsStatePayload forPlayer(MinecraftServer server, GameBlockEntity block,
                                              SlotCabinet cabinet, UUID playerId) {
        SlotsGame game = cabinet.game();
        MachineView view = new MachineView(
                block.phase().ordinal(),
                cabinet.isRolling(),
                block.isSeated(playerId),
                block.settings().get(game.betMinimum()),
                block.settings().get(game.betMaximum()),
                game.paybackFrom(block.settings()).percent());

        // Withheld while the reels are turning, and not only for show: a
        // client holding the landing symbols early is a client that can be
        // made to say what they are. The spin is settled by then either way,
        // so what is being protected is the surprise — which is the whole
        // product. Only the player who pulled is shown it at all; another
        // viewer would be reading somebody else's spin off their screen.
        SlotMachine.SpinResult landed = !cabinet.isRolling()
                && playerId.equals(cabinet.lastSpinner())
                ? cabinet.lastResult().orElse(null)
                : null;
        // Zero while the reels turn, and not merely for tidiness: the bank is
        // set the moment the handle is pulled, so sending it would put the
        // win on the meter two seconds before the symbols that explain it.
        // It is also true — a spin in progress has the whole stack riding on
        // it and nothing set aside.
        boolean mine = playerId.equals(cabinet.lastSpinner());
        long prizes = mine && !cabinet.isRolling() ? cabinet.prizes() : 0;
        SpinView spin = landed == null
                ? new SpinView(List.of(), List.of(), 0, prizes, 0, 0)
                : new SpinView(flatten(landed), winners(landed), cabinet.lastWin(), prizes,
                        cabinet.freeLines(), cabinet.freePerLine());

        Optional<BuyIn> buyIn = block.buyIn();
        PlayerFunds funds = new PlayerFunds(
                CreditStorage.get(server).balanceOf(playerId),
                OutcomeSettler.stakes().committedElsewhere(playerId, block.commitmentKey()),
                block.stackOf(playerId),
                buyIn.map(BuyIn::minimum).orElse(0L),
                buyIn.map(BuyIn::maximum).orElse(0L),
                game.denominationFrom(block.settings()));

        return new SlotsStatePayload(view, spin, funds);
    }

    private static List<Integer> flatten(SlotMachine.SpinResult result) {
        List<Integer> window = new ArrayList<>(WINDOW_SIZE);
        for (List<SlotSymbol> reel : result.window()) {
            for (SlotSymbol symbol : reel) {
                window.add(symbol.ordinal());
            }
        }
        return List.copyOf(window);
    }

    private static List<Integer> winners(SlotMachine.SpinResult result) {
        List<Integer> lines = new ArrayList<>();
        for (Payline line : result.wins().keySet()) {
            lines.add(line.ordinal());
        }
        return List.copyOf(lines);
    }
}

package com.github.arrivedbog593.tablegames.platform.block;

import com.github.arrivedbog593.tablegames.engine.games.slots.Payline;
import com.github.arrivedbog593.tablegames.engine.games.slots.SlotAction;
import com.github.arrivedbog593.tablegames.engine.games.slots.SlotMachine;
import com.github.arrivedbog593.tablegames.engine.games.slots.SlotRig;
import com.github.arrivedbog593.tablegames.engine.games.slots.SlotSymbol;
import com.github.arrivedbog593.tablegames.engine.games.slots.SlotsGame;
import com.github.arrivedbog593.tablegames.engine.games.slots.SlotsSession;
import com.github.arrivedbog593.tablegames.engine.session.ActionResult;
import com.github.arrivedbog593.tablegames.engine.session.Outcome;
import com.github.arrivedbog593.tablegames.engine.session.Seat;
import com.github.arrivedbog593.tablegames.engine.table.RoundPhase;
import com.github.arrivedbog593.tablegames.platform.economy.CreditFormat;
import com.github.arrivedbog593.tablegames.platform.economy.OutcomeSettler;
import com.github.arrivedbog593.tablegames.platform.network.SlotsStatePayload;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * The live part of a slot machine: the reels, and the one spin they are in
 * the middle of.
 * <p>
 * Much smaller than {@link RouletteTable}, and for one reason: a spin is
 * settled the instant the lever is pulled. There is no betting window to
 * hold open, nothing of the house's riding on a countdown, and no round to
 * abandon halfway. What the reels do afterwards is an animation the client
 * plays over a result the server already wrote down.
 * <p>
 * That is why the phase here is a lock rather than a round.
 * {@link RoundPhase#LOCKED} while the reels are turning stops a second pull
 * landing before the first one is shown — the credits are already moved, so
 * without it a player could spin faster than the machine could say what
 * happened. The rest of the time the machine is idle, whatever is on the
 * glass.
 * <p>
 * A machine is one seat by definition, so everything here is about one
 * player: whoever is sitting at it.
 */
public final class SlotCabinet implements TableRuntime {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** How long the reels turn before they are readable, in ticks. */
    private static final int SPIN_TICKS = 40;

    private final GameBlockEntity block;
    private final SlotsGame game;

    /** Ticks left of the reels turning; zero when they are still. */
    private int ticksLeft;

    /** Whether the reels are turning, which is the whole of what the lock is. */
    private boolean rolling;

    /**
     * The last spin, kept until the next one replaces it.
     * <p>
     * Not cleared on a timer. A machine that wiped the symbols a few seconds
     * after they landed would be taking away the only record of what just
     * happened, and the player who looked away for a moment has no way to get
     * it back. Every machine on a real floor leaves the last result standing
     * until the handle comes down again, and so does this one.
     */
    private SlotMachine.SpinResult lastResult;

    /** What the next pull has been told to land, by id, on a test table; null to let it fall. */
    private String rigged;

    /** What the last spin paid, so the screen can say so. */
    private long lastWin;

    /** How many lines the last spin was played on, so a win can be weighed against it. */
    private int lastLines;

    /**
     * The prize bank: what has been won and not yet gambled again.
     * <p>
     * Not a second pot of money. It is a line drawn through the one stack
     * the player already has, marking off the part that arrived as winnings
     * — so credits are always the stack less this. Keeping it as a mark
     * rather than a balance is what stops the two ever disagreeing.
     * <p>
     * The point of it is the pause. Winnings land somewhere they are not at
     * risk, and taking them out is one button away, but so is putting them
     * back on the reels: pulling the handle moves the bank into credits
     * first, because a machine that let you play your winnings without ever
     * saying so would be hiding the only decision it ever asks you to make.
     */
    private long prizes;

    /** Whose spin it was, so nobody else is shown it as theirs. */
    private UUID lastSpinner;

    /**
     * A spin the machine owes, won by lining up replays.
     * <p>
     * Held against the seat rather than the player: it dies when they stand
     * up, the same way a stack does, and for the same reason. Nothing here
     * is persisted, so a free spin does not survive a restart either — by
     * then the seat it belonged to is gone too.
     */
    private Free free;

    /**
     * A spin already paid for.
     *
     * @param lines   how many lines it must be played on
     * @param perLine what it must be staked at
     */
    private record Free(int lines, long perLine) {
    }

    SlotCabinet(GameBlockEntity block, SlotsGame game) {
        this.block = Objects.requireNonNull(block, "block");
        this.game = Objects.requireNonNull(game, "game");
    }

    // --- What the screen reads ------------------------------------------------

    /** The machine this block is currently set up to run. */
    public SlotMachine machine() {
        return game.machineFor(block.settings());
    }

    public SlotsGame game() {
        return game;
    }

    /** The last spin while it is still on screen. */
    public Optional<SlotMachine.SpinResult> lastResult() {
        return Optional.ofNullable(lastResult);
    }

    /**
     * The most a line may be played for, in credits, on this many lines,
     * before the house could not cover the worst the reels can do.
     * <p>
     * The worst being every line landing the best combination at once, which
     * is what the pull is checked against — so this is exactly the stake
     * {@link #spin} would still take, worked out in advance for the screen's
     * "max" to offer and for a refusal to name. It moves with the bankroll
     * and with what other tables have committed, so it is a promise about
     * now and nothing longer.
     */
    public long houseCapPerLine(MinecraftServer server, int lines) {
        long headroom = block.funds().exposureHeadroom(server, game, block.commitmentKey());
        if (headroom == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        long worstPerCredit = Math.multiplyExact(game.priceOf(1, block.settings()),
                (long) machine().maxMultiple(lines));
        return worstPerCredit <= 0 ? Long.MAX_VALUE : headroom / worstPerCredit;
    }

    public long lastWin() {
        return lastWin;
    }

    /**
     * What the cabinet should announce for the last spin, or nothing while
     * the reels are still turning.
     * <p>
     * Nothing while they turn is the point of it. The spin was settled the
     * instant the lever was pulled, and this is read by the update every
     * client in earshot gets — so it keeps the same silence the screen's
     * payload keeps about the symbols and the prize bank until the spin is
     * over, and a client that went looking would find nothing early.
     */
    public SlotFanfare fanfare() {
        if (rolling || lastResult == null) {
            return SlotFanfare.NONE;
        }
        // The top of this machine's card, not a symbol named here: whatever
        // pays most for three is the jackpot, on any card it is ever given.
        SlotSymbol top = machine().paytable().threeOfAKind().entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(SlotSymbol.NETHERITE);
        return SlotFanfare.of(lastResult.totalMultiple(), lastLines, lastResult.replay(),
                lastResult.threeOnAPaidLine(top));
    }

    /** What is in the prize bank, in the currency a balance is kept in. */
    public long prizes() {
        return prizes;
    }

    /**
     * Pays the prize bank out, leaving the player at the machine with
     * whatever credits they had.
     * <p>
     * Refused while the reels are turning, like everything else that moves
     * money here.
     *
     * @return why not, or null when it was paid
     */
    public Component collect(ServerPlayer player) {
        UUID playerId = player.getUUID();
        if (!playerId.equals(lastSpinner) || prizes <= 0) {
            return Component.translatable("tablegames.slots.nothing_to_collect");
        }
        if (rolling) {
            return Component.translatable("tablegames.slots.still_spinning");
        }
        block.releaseFromStack(playerId, prizes);
        prizes = 0;
        block.markDirty();
        return null;
    }

    public UUID lastSpinner() {
        return lastSpinner;
    }

    /** Whether the reels are still turning, so the client keeps them moving. */
    public boolean isRolling() {
        return rolling;
    }

    /** How many lines the spin the machine owes must be played on, or zero. */
    public int freeLines() {
        return free == null ? 0 : free.lines();
    }

    /** What the spin the machine owes is staked at, or zero. */
    public long freePerLine() {
        return free == null ? 0 : free.perLine();
    }

    // --- Pulling the lever ------------------------------------------------------

    /**
     * Runs one spin and settles it, or says why it cannot.
     * <p>
     * Everything is checked here and nothing is taken on trust from the
     * client: which lines, how many credits a line, whether the meter covers
     * it, and — last, because it is the only limit that knows about the rest
     * of the casino — whether the bankroll could pay the best this machine
     * can land.
     * <p>
     * The one conversion in the whole feature happens here. What arrives is
     * in the machine's own credits, because that is what the player chose;
     * what leaves is in the currency balances are kept in, because that is
     * what a stake is settled in. Nothing downstream — the session, the
     * paytable, the bankroll — is told there was ever a credit.
     *
     * @param lines   how many paylines to play
     * @param credits the stake on each line, in this machine's credits
     * @return why not, or null when the reels are turning
     */
    public Component spin(ServerPlayer player, int lines, long credits) {
        UUID playerId = player.getUUID();
        if (!block.isConfigured()) {
            return Component.translatable("tablegames.table.not_configured");
        }
        if (!block.isSeated(playerId)) {
            return Component.translatable("tablegames.seat.must_be_seated");
        }
        if (ticksLeft > 0) {
            return Component.translatable("tablegames.slots.still_spinning");
        }

        long perLine;
        boolean owed = free != null;
        if (owed) {
            // A free spin is the spin that won it, replayed. Letting the
            // player restate the lines or the stake would turn a replay into
            // a free bet of whatever size they liked.
            //
            // The limits below are not asked again either, deliberately: this
            // stake was legal when it was paid for, and a machine retuned
            // between one spin and the next would otherwise owe a spin it
            // then refused to give, with no way for the player to clear it
            // but to walk away from the seat. It is kept already converted
            // for the same reason: a change of denomination must not reprice
            // a spin that has been paid for.
            lines = free.lines();
            perLine = free.perLine();
        } else {
            if (lines < 1 || lines > Payline.MAX) {
                return Component.translatable("tablegames.slots.bad_lines", Payline.MAX);
            }
            long minimum = block.settings().get(game.betMinimum());
            long maximum = block.settings().get(game.betMaximum());
            if (credits < minimum) {
                return Component.translatable("tablegames.slots.below_minimum",
                        CreditFormat.of(minimum));
            }
            if (maximum > 0 && credits > maximum) {
                return Component.translatable("tablegames.slots.above_maximum",
                        CreditFormat.of(maximum));
            }
            perLine = game.priceOf(credits, block.settings());
        }

        SlotAction.Spin pull = new SlotAction.Spin(lines, perLine, owed);
        long stack = block.stackOf(playerId);
        if (pull.cost() > stack) {
            return Component.translatable("tablegames.reject.insufficient_stack",
                    CreditFormat.of(stack));
        }

        Level level = block.getLevel();
        if (level == null || level.getServer() == null) {
            return Component.translatable("tablegames.table.round_failed");
        }
        MinecraftServer server = level.getServer();

        // What this pull could cost the house at worst: every line hitting
        // the machine's best combination at once. Counted by the engine over
        // every way the reels can stop, not guessed from the paytable.
        long worstCase = Math.multiplyExact(perLine, (long) machine().maxMultiple(lines));
        if (!block.funds().withinExposure(server, game, block.commitmentKey(), worstCase)) {
            // Named, and said as what it is: this pull is more than the house
            // will stand behind, and here is the most it will. The general
            // "the house has too much out" read as other tables' fault and
            // left the player guessing how much less to try.
            long cap = houseCapPerLine(server, lines);
            return cap <= 0
                    ? Component.translatable("tablegames.reject.house_exposed")
                    : Component.translatable("tablegames.slots.house_cap",
                            CreditFormat.of(cap), lines);
        }

        return run(server, player, pull);
    }

    /**
     * Plays the pull through the engine and settles what it paid.
     * <p>
     * The session is built for this one spin and thrown away, the same way a
     * roulette round is, so the rules that price a spin are the tested ones
     * and not a second copy living here.
     */
    private Component run(MinecraftServer server, ServerPlayer player, SlotAction.Spin pull) {
        UUID playerId = player.getUUID();
        // RandomSource is Minecraft's own interface and does not implement
        // RandomGenerator, which the engine takes.
        RandomGenerator random = new Random(Objects.requireNonNull(block.getLevel()).random.nextLong());
        SlotsSession session = game.createSession(
                List.of(Seat.forPlayer(0, playerId, block.stackOf(playerId))), random, machine());
        session.begin();
        // A test table told what to land: stops on these reels that show it,
        // and then the same settlement as ever. Checked again here rather
        // than trusted from when it was set, and spent on this pull whatever
        // happens to it, so a result asked for once lands once.
        if (rigged != null && block.isTestTable()) {
            SlotRig.target(machine().paytable(), rigged)
                    .flatMap(target -> SlotRig.stopsFor(machine(), target))
                    .ifPresent(session::rig);
        }
        rigged = null;

        ActionResult result = session.submit(playerId, pull);
        if (!result.accepted()) {
            // The two layers disagree about what is legal, which is a bug
            // here and not a player doing something clever.
            LOGGER.error("[TableGames] The engine refused a spin this machine had accepted, "
                            + "at {}: {}. Player {}, {} lines at {}.",
                    block.getBlockPos().toShortString(), result.messageKey(), playerId,
                    pull.lines(), pull.perLine());
            return Component.translatable("tablegames.table.round_failed");
        }

        Outcome outcome = session.outcome().orElse(null);
        if (outcome == null) {
            return Component.translatable("tablegames.table.round_failed");
        }

        OutcomeSettler.Result settled = block.funds().settle(
                server, game, outcome, "at " + block.getBlockPos().toShortString());
        if (!settled.applied()) {
            // Nothing moved, so the spin never happened. The free spin it
            // might have been paying for is still owed.
            return Component.translatable(settled.reasonKey());
        }

        block.settleStacks(outcome);
        SlotMachine.SpinResult landed = session.result().orElseThrow();

        this.free = landed.replay() ? new Free(pull.lines(), pull.perLine()) : null;
        this.lastResult = landed;
        this.lastLines = pull.lines();
        this.lastWin = Math.multiplyExact(pull.perLine(), (long) landed.totalMultiple());
        // Whatever was in the bank has just been played — the stake came out
        // of the one stack and the bank was only ever a mark on part of it —
        // so the bank now holds this spin's winnings and nothing else.
        this.prizes = lastWin;
        this.lastSpinner = playerId;
        this.ticksLeft = SPIN_TICKS;
        this.rolling = true;

        block.endRound(List.of(playerId));
        return null;
    }

    // --- The round, such as it is ------------------------------------------------

    /**
     * Locked while the reels turn, idle the rest of the time.
     * <p>
     * There is no phase for showing a result, because showing one is not
     * something the machine is doing — it is what the glass says until
     * somebody pulls again. Standing up and cashing out stay open throughout,
     * which is right: the spin was settled before the first reel moved.
     */
    @Override
    public RoundPhase phase() {
        return rolling ? RoundPhase.LOCKED : RoundPhase.IDLE;
    }

    @Override
    public void tick() {
        if (ticksLeft <= 0) {
            return;
        }
        ticksLeft--;
        if (ticksLeft == 0) {
            // The reels have stopped, so the symbols may be sent. They were
            // withheld while it was turning.
            rolling = false;
            block.markDirty();
        }
    }

    /**
     * Nothing, ever.
     * <p>
     * A spin is paid for and settled in the same call, so between one pull
     * and the next there is never a stake on this machine. The lock while the
     * reels turn is about what the screen is showing, not about money.
     */
    @Override
    public long wageredBy(UUID playerId) {
        return 0;
    }

    @Override
    public boolean hasLiveStakes() {
        return false;
    }

    @Override
    public long worstCaseHouseCost() {
        return 0;
    }

    /**
     * The machine forgets whoever walks away from it: the spin it owed them,
     * and the one still on the glass.
     */
    @Override
    public void seatLost(UUID playerId) {
        if (playerId.equals(lastSpinner)) {
            free = null;
            lastResult = null;
            lastSpinner = null;
            lastWin = 0;
            prizes = 0;
        }
    }

    @Override
    public void abandon() {
        ticksLeft = 0;
        rolling = false;
        lastResult = null;
        lastSpinner = null;
        lastWin = 0;
        prizes = 0;
        free = null;
    }

    @Override
    public List<RigOption> rigOptions() {
        return SlotRig.targets(machine().paytable()).stream()
                .map(target -> new RigOption(target.id(),
                        Component.translatable("tablegames.debug.rig.slots." + target.id()),
                        tintOf(target)))
                .toList();
    }

    /** Each result in the color of what it is made of, so the list reads at a glance. */
    private static int tintOf(SlotRig.Target target) {
        if (target.symbol() == null) {
            return 0x808080;
        }
        return switch (target.symbol()) {
            case NETHERITE -> 0xE0B020;
            case DIAMOND -> 0x5ED8E0;
            case EMERALD -> 0x3CC864;
            case GOLD -> 0xF0D060;
            case IRON -> 0xD0D0D0;
            case COAL -> 0x9A9A9A;
            case REPLAY -> 0xE8A040;
        };
    }

    @Override
    public boolean rig(String optionId) {
        if (optionId == null) {
            rigged = null;
            return true;
        }
        if (SlotRig.target(machine().paytable(), optionId).isEmpty()) {
            return false;
        }
        rigged = optionId;
        return true;
    }

    @Override
    public Optional<String> rigged() {
        return Optional.ofNullable(rigged);
    }

    @Override
    public CustomPacketPayload stateFor(MinecraftServer server, UUID viewer) {
        return SlotsStatePayload.forPlayer(server, block, this, viewer);
    }
}

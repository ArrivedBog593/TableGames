package com.github.arrivedbog593.tablegames.client;

import com.github.arrivedbog593.tablegames.TableGames;
import com.github.arrivedbog593.tablegames.engine.games.slots.SlotMachine;
import com.github.arrivedbog593.tablegames.platform.block.ReelMotion;
import com.github.arrivedbog593.tablegames.platform.block.SlotFanfare;
import com.github.arrivedbog593.tablegames.platform.block.SlotMachineBlockEntity;
import com.github.arrivedbog593.tablegames.platform.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * The noise a slot machine makes, played from the machine itself.
 * <p>
 * Every client within earshot works the spin out for itself from the one
 * thing the server already tells it — that the reels have started, and that
 * they are to stop — and plays the ticks and the landings from the cabinet's
 * own position, fading with distance. Nobody has to have the screen open to
 * hear a machine being played across the room, and nobody with the screen
 * open hears it twice: the screen makes no reel sound of its own.
 * <p>
 * Timed off {@link ReelMotion}, the same model the screen draws with, so the
 * landing you hear is the landing on the glass. Driven once a frame rather
 * than once a tick: at full speed the ticks come about every fifty-five
 * milliseconds, and a twenty-a-second clock would bunch and gap them.
 * <p>
 * The fanfare waits for the last reel, the same as everything on the screen
 * that would give the result away.
 */
@EventBusSubscriber(modid = TableGames.MOD_ID, value = Dist.CLIENT)
public final class CabinetSounds implements SlotMachineBlockEntity.Listener {

    public static final CabinetSounds INSTANCE = new CabinetSounds();

    /**
     * How many ticks sound for each symbol of the reel they follow. Two
     * against the slowest reel is about eighteen a second at full speed,
     * which is a roulette rather than a rattle.
     */
    private static final double TICKS_PER_SYMBOL = 2.0;

    /** Where on a symbol the first tick falls, so the pull itself does not tick twice. */
    private static final double TICK_OFFSET = 0.7;

    /** The tick's second note, a major third under the first: C against E. */
    private static final float LOW_TICK = 0.7937f;

    /**
     * Each landing a step higher than the last, so the three of them make a
     * chord rather than the same thud three times: G, B, D.
     */
    private static final float[] LANDING_PITCH = {1.0f, 1.26f, 1.5f};

    /** A breath between the last reel landing and the fanfare, so the two do not smear. */
    private static final long FANFARE_DELAY_MILLIS = 100;

    /**
     * How long a voice waits for the word that the reels are stopping before
     * it gives up on having heard it. The server holds a spin two seconds.
     */
    private static final long LOST_MILLIS = 15_000;

    private final Map<BlockPos, Voice> voices = new HashMap<>();

    private CabinetSounds() {
    }

    @Override
    public void reelsTurned(SlotMachineBlockEntity machine, boolean turning) {
        BlockPos pos = machine.getBlockPos().immutable();
        if (turning) {
            voices.put(pos, new Voice(machine, System.currentTimeMillis()));
            return;
        }
        Voice voice = voices.get(pos);
        if (voice != null) {
            voice.stoppedAt = System.currentTimeMillis();
        }
    }

    @Override
    public void cabinetGone(SlotMachineBlockEntity machine) {
        voices.remove(machine.getBlockPos());
    }

    @SubscribeEvent
    public static void onFrame(RenderFrameEvent.Pre event) {
        INSTANCE.play();
    }

    private void play() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            voices.clear();
            return;
        }
        long now = System.currentTimeMillis();
        voices.values().removeIf(voice -> !voice.play(level, now));
    }

    /** One machine's spin, from the pull to the fanfare. */
    private static final class Voice {

        private final SlotMachineBlockEntity machine;
        private final long startedAt;

        /** When the server said stop, or zero while it has not. */
        private long stoppedAt;

        /** How many ticks the followed reel has passed, or -1 before the first. */
        private int ticks = -1;
        private int played;

        private final boolean[] landed = new boolean[SlotMachine.REELS];

        /** When the last reel came down, or zero while one is still turning. */
        private long allDownAt;

        Voice(SlotMachineBlockEntity machine, long startedAt) {
            this.machine = machine;
            this.startedAt = startedAt;
        }

        /** Plays whatever is due this frame. False once there is nothing left to play. */
        boolean play(ClientLevel level, long now) {
            if (machine.isRemoved() || machine.getLevel() != level) {
                return false;
            }
            long spinning = now - startedAt;
            long stopped = stoppedAt == 0 ? -1 : now - stoppedAt;
            if (stoppedAt == 0 && spinning > LOST_MILLIS) {
                return false;
            }

            tick(level, spinning, stopped);
            land(level, spinning, stopped);

            if (allDownAt == 0) {
                return true;
            }
            if (now - allDownAt < FANFARE_DELAY_MILLIS) {
                return true;
            }
            fanfare(level);
            return false;
        }

        /**
         * The roulette, following the last reel: it is the one still turning
         * when the others have landed, so the ticks slow down with it and
         * stop when it does.
         */
        private void tick(ClientLevel level, long spinning, long stopped) {
            ReelMotion.At last = ReelMotion.of(SlotMachine.REELS - 1, spinning, stopped);
            int passed = (int) Math.floor(TICKS_PER_SYMBOL * last.symbols() + TICK_OFFSET);
            if (ticks < 0) {
                // The pull itself ticks, so the button never feels late.
                ticks = passed;
                sound(level, ModSounds.SLOT_TICK.get(), 0.35f, 1.0f);
                played++;
                return;
            }
            if (passed <= ticks || last.resting()) {
                return;
            }
            // One tick however many were crossed: a frame that hitched should
            // not come back as a burst.
            ticks = passed;
            sound(level, ModSounds.SLOT_TICK.get(), 0.35f, played % 2 == 0 ? 1.0f : LOW_TICK);
            played++;
        }

        private void land(ClientLevel level, long spinning, long stopped) {
            if (stopped < 0) {
                return;
            }
            boolean allDown = true;
            for (int reel = 0; reel < SlotMachine.REELS; reel++) {
                if (!landed[reel] && ReelMotion.of(reel, spinning, stopped).resting()) {
                    landed[reel] = true;
                    sound(level, ModSounds.SLOT_REEL_STOP.get(), 0.7f, LANDING_PITCH[reel]);
                }
                allDown &= landed[reel];
            }
            if (allDown && allDownAt == 0) {
                allDownAt = System.currentTimeMillis();
            }
        }

        private void fanfare(ClientLevel level) {
            SlotFanfare heard = machine.heard();
            switch (heard) {
                case WIN -> sound(level, ModSounds.SLOT_WIN.get(), 0.9f, 1.0f);
                case BIG_WIN -> sound(level, ModSounds.SLOT_BIG_WIN.get(), 1.0f, 1.0f);
                case FREE_SPIN -> sound(level, ModSounds.SLOT_FREE_SPIN.get(), 0.9f, 1.0f);
                case JACKPOT -> sound(level, ModSounds.SLOT_JACKPOT.get(), 1.0f, 1.0f);
                case NONE -> {
                    // A spin that paid nothing says nothing.
                }
            }
        }

        /** From the reel glass, which is in the upper half, not the block the machine is in. */
        private void sound(ClientLevel level, SoundEvent event, float volume, float pitch) {
            BlockPos pos = machine.getBlockPos();
            level.playLocalSound(pos.getX() + 0.5, pos.getY() + 1.5, pos.getZ() + 0.5,
                    event, SoundSource.BLOCKS, volume, pitch, false);
        }
    }
}

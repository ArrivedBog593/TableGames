package com.github.arrivedbog593.tablegames.client;

import com.github.arrivedbog593.tablegames.TableGames;
import com.github.arrivedbog593.tablegames.platform.block.TableBlockEntity;
import com.github.arrivedbog593.tablegames.platform.block.WheelMotion;
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
 * The noise a roulette table makes, played from the table itself.
 * <p>
 * The same arrangement as {@link CabinetSounds}: every client in earshot is
 * told the ball was thrown and how far it runs, works the run out from
 * {@link WheelMotion} — the model the strip is drawn from — and plays it from
 * the table's position, fading with distance. Nobody has to have the felt
 * open to hear the wheel, and nobody with it open hears it twice.
 * <p>
 * A tick for every pocket the ball passes, but never two closer than a hand
 * could hear: off the throw it passes a hundred a second, and one tick each
 * would be a buzz. They come up louder as they spread out, which is the
 * slowing you listen for. Then a clack for each fret it bounces off, on
 * the frame the strip turns, the settle when it lies still, and — when
 * the number paid anybody — the chips going out a moment after.
 */
@EventBusSubscriber(modid = TableGames.MOD_ID, value = Dist.CLIENT)
public final class WheelSounds implements TableBlockEntity.WheelListener {

    public static final WheelSounds INSTANCE = new WheelSounds();

    /** The closest two ticks may come, in milliseconds. */
    private static final long TICK_GAP_MILLIS = 28;

    /** The speed, in pockets a second, at which the ticks are as quiet as they get. */
    private static final double BLUR_SPEED = 40;

    /** How hard each bounce sounds, first to last. */
    private static final float[] HIT_VOLUME = {0.9f, 0.65f, 0.45f};

    /** How long after the ball settles the chips go out to the winners. */
    private static final long PAYOUT_DELAY_MILLIS = 350;

    private final Map<BlockPos, Throw> throwing = new HashMap<>();

    private WheelSounds() {
    }

    @Override
    public void wheelSpun(TableBlockEntity table, int pockets, boolean paid) {
        throwing.put(table.getBlockPos().immutable(),
                new Throw(table, pockets, paid, System.currentTimeMillis()));
    }

    @Override
    public void tableGone(TableBlockEntity table) {
        throwing.remove(table.getBlockPos());
    }

    @SubscribeEvent
    public static void onFrame(RenderFrameEvent.Pre event) {
        INSTANCE.play();
    }

    private void play() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            throwing.clear();
            return;
        }
        long now = System.currentTimeMillis();
        throwing.values().removeIf(ball -> !ball.play(level, now));
    }

    /** One ball, from the throw to the pocket. */
    private static final class Throw {

        private final TableBlockEntity table;
        private final int pockets;
        private final boolean paid;
        private final long thrownAt;

        private int passed;

        /** How many of the frets it bounces off have been heard. */
        private int hits;

        /** Whether the ball has settled and been heard doing it. */
        private boolean landed;

        // Zero, not Long.MIN_VALUE: "now - lastTickAt" with the minimum overflows
        // to a negative, the gap test never passes, and not one tick ever sounds.
        private long lastTickAt;

        Throw(TableBlockEntity table, int pockets, boolean paid, long thrownAt) {
            this.table = table;
            this.pockets = pockets;
            this.paid = paid;
            this.thrownAt = thrownAt;
        }

        /** Plays whatever is due this frame. False once there is nothing left to play. */
        boolean play(ClientLevel level, long now) {
            if (table.isRemoved() || table.getLevel() != level) {
                return false;
            }
            long elapsed = now - thrownAt;
            if (landed) {
                // The payout, once the winner has sat lit a moment. Never
                // before the ball is down: chips moving at the throw would
                // tell the room somebody won while the wheel was still turning.
                if (elapsed < WheelMotion.SPIN_MILLIS + PAYOUT_DELAY_MILLIS) {
                    return true;
                }
                sound(level, ModSounds.ROULETTE_PAYOUT.get(), 0.8f, 1.0f);
                return false;
            }
            // A clack at every fret it bounces off, on the frame the strip
            // turns: hardest the first time, softer each time after, a little
            // lower than a passing tick because the ball is hitting, not
            // grazing.
            while (hits < WheelMotion.HIT_MILLIS.length && elapsed >= WheelMotion.HIT_MILLIS[hits]) {
                sound(level, ModSounds.ROULETTE_TICK.get(), HIT_VOLUME[hits], 0.85f);
                hits++;
            }
            if (!WheelMotion.running(elapsed)) {
                sound(level, ModSounds.ROULETTE_LAND.get(), 0.8f, 1.0f);
                landed = true;
                return paid;
            }
            int passedNow = (int) Math.floor(WheelMotion.pocketsAt(elapsed, pockets));
            if (passedNow > passed && now - lastTickAt >= TICK_GAP_MILLIS) {
                double speed = (WheelMotion.pocketsAt(elapsed + 1, pockets)
                        - WheelMotion.pocketsAt(elapsed, pockets)) * 1000;
                double blur = Math.min(1.0, speed / BLUR_SPEED);
                sound(level, ModSounds.ROULETTE_TICK.get(), (float) (0.5 * (0.35 + 0.65 * (1 - blur))), 1.0f);
                lastTickAt = now;
            }
            passed = Math.max(passed, passedNow);
            return true;
        }

        private void sound(ClientLevel level, SoundEvent event, float volume, float pitch) {
            BlockPos pos = table.getBlockPos();
            level.playLocalSound(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
                    event, SoundSource.BLOCKS, volume, pitch, false);
        }
    }
}

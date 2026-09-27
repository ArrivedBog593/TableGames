package com.github.arrivedbog593.tablegames.platform.registry;

import com.github.arrivedbog593.tablegames.TableGames;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Every sound the mod makes, and how far each one carries.
 * <p>
 * Registered even though the files are the mod's own, so that code only ever
 * names an event and never a file. Which recording answers to an event is
 * {@code sounds.json}'s business, which is what lets a resource pack replace
 * the cabinet's voice, and what lets the recordings be redone without a line
 * of Java changing.
 * <p>
 * Every range is fixed rather than derived from volume. Loudness and reach
 * are two separate decisions here: a reel tick should be quiet and near, and
 * a big win should be heard across the floor without being any louder at the
 * machine than a small one.
 */
public final class ModSounds {

    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(Registries.SOUND_EVENT, TableGames.MOD_ID);

    /** One step of the reels turning. The machine plays it low and high by turns. */
    public static final DeferredHolder<SoundEvent, SoundEvent> SLOT_TICK = register("slot.tick", 12);

    /** A reel coming to rest. */
    public static final DeferredHolder<SoundEvent, SoundEvent> SLOT_REEL_STOP = register("slot.reel_stop", 14);

    /** A spin that paid. */
    public static final DeferredHolder<SoundEvent, SoundEvent> SLOT_WIN = register("slot.win", 16);

    /** A spin that paid enough to turn heads, so it carries further. */
    public static final DeferredHolder<SoundEvent, SoundEvent> SLOT_BIG_WIN = register("slot.big_win", 24);

    /** Replays lined up. */
    public static final DeferredHolder<SoundEvent, SoundEvent> SLOT_FREE_SPIN = register("slot.free_spin", 16);

    /** The top of the card. Heard across the floor, because it is the one the floor should hear. */
    public static final DeferredHolder<SoundEvent, SoundEvent> SLOT_JACKPOT = register("slot.jackpot", 32);

    /** The ball on a fret, once for every pocket it passes. */
    public static final DeferredHolder<SoundEvent, SoundEvent> ROULETTE_TICK = register("roulette.tick", 12);

    /** The ball dropping into its pocket. */
    public static final DeferredHolder<SoundEvent, SoundEvent> ROULETTE_LAND = register("roulette.land", 16);

    /** A wager put down on the felt. Close to the table: it is the room's murmur, not news. */
    public static final DeferredHolder<SoundEvent, SoundEvent> ROULETTE_CHIP = register("roulette.chip", 10);

    /** Chips pushed across to whoever the number paid. */
    public static final DeferredHolder<SoundEvent, SoundEvent> ROULETTE_PAYOUT = register("roulette.payout", 14);

    private ModSounds() {
    }

    private static DeferredHolder<SoundEvent, SoundEvent> register(String name, float range) {
        return SOUNDS.register(name, () -> SoundEvent.createFixedRangeEvent(
                ResourceLocation.fromNamespaceAndPath(TableGames.MOD_ID, name), range));
    }

    public static void register(IEventBus modEventBus) {
        SOUNDS.register(modEventBus);
    }
}

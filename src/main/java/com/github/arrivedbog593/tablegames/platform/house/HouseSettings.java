package com.github.arrivedbog593.tablegames.platform.house;

import com.github.arrivedbog593.tablegames.engine.house.HouseBankroll;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * How much of its bank the house will risk, set in game and saved with the
 * world.
 * <p>
 * The casino's own file, not the economy's. The house's money is the
 * server's treasury and lives in the economy's ledger, but how much of it a
 * table may put at stake is a question only the casino asks — a bazaar built
 * on the same economy would have no use for it.
 * <p>
 * Server thread only.
 */
public final class HouseSettings extends SavedData {

    private static final String DATA_NAME = "tablegames_house";

    private static final String KEY_EXPOSURE_PERCENT = "exposure_percent";
    private static final String KEY_MINIMUM_RESERVE = "minimum_reserve";

    public static final SavedData.Factory<HouseSettings> FACTORY =
            new SavedData.Factory<>(HouseSettings::new, HouseSettings::load, null);

    private int exposurePercent = HouseBankroll.DEFAULT_EXPOSURE_PERCENT;
    private long minimumReserve = HouseBankroll.DEFAULT_MINIMUM_RESERVE;

    private HouseSettings() {
    }

    private static HouseSettings load(CompoundTag tag, HolderLookup.Provider registries) {
        HouseSettings settings = new HouseSettings();
        if (tag.contains(KEY_EXPOSURE_PERCENT)) {
            settings.exposurePercent = Math.clamp(tag.getInt(KEY_EXPOSURE_PERCENT), 1, 100);
        }
        if (tag.contains(KEY_MINIMUM_RESERVE)) {
            settings.minimumReserve = Math.max(0, tag.getLong(KEY_MINIMUM_RESERVE));
        }
        return settings;
    }

    @Override
    public @NotNull CompoundTag save(CompoundTag tag, HolderLookup.@NotNull Provider registries) {
        tag.putInt(KEY_EXPOSURE_PERCENT, exposurePercent);
        tag.putLong(KEY_MINIMUM_RESERVE, minimumReserve);
        return tag;
    }

    public static HouseSettings get(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public int exposurePercent() {
        return exposurePercent;
    }

    public long minimumReserve() {
        return minimumReserve;
    }

    /**
     * How much of the bankroll one payout may claim.
     * <p>
     * Raising this loosens table limits and raises the odds of a bad night
     * emptying the casino. Five percent is the sane default; above about
     * twenty the math stops protecting anything.
     */
    public void setExposurePercent(int percent) {
        this.exposurePercent = Math.clamp(percent, 1, 100);
        setDirty();
    }

    /** Below this balance, house-banked games close. */
    public void setMinimumReserve(long reserve) {
        this.minimumReserve = Math.max(0, reserve);
        setDirty();
    }
}

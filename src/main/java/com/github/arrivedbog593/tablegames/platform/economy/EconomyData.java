package com.github.arrivedbog593.tablegames.platform.economy;

import com.github.arrivedbog593.tablegames.engine.economy.CreditValueTable;
import com.github.arrivedbog593.tablegames.engine.economy.HouseBankroll;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Conversion values, shop prices and the casino's risk settings, edited in
 * game and saved with the world.
 * <p>
 * Kept apart from {@link CreditStorage} on purpose. Balances change many
 * times a minute while prices change a few times a month, and splitting them
 * means a corrupt balance file cannot take the whole price list with it.
 * <p>
 * Datapack JSON still works and acts as the default. Anything set with a
 * command overrides it, because someone standing in the world with the item
 * in hand has more current intent than a file written once.
 * <p>
 * Server thread only.
 */
public final class EconomyData extends SavedData {

    private static final String DATA_NAME = "tablegames_economy";
    private static final String KEY_CONVERSIONS = "conversions";
    private static final String KEY_SHOP = "shop";
    private static final String KEY_EXPOSURE_PERCENT = "house_exposure_percent";
    private static final String KEY_MINIMUM_RESERVE = "house_minimum_reserve";

    public static final SavedData.Factory<EconomyData> FACTORY =
            new SavedData.Factory<>(EconomyData::new, EconomyData::load, null);

    private final Map<String, Long> conversions = new LinkedHashMap<>();
    private final Map<String, Long> shopPrices = new LinkedHashMap<>();

    private int exposurePercent = HouseBankroll.DEFAULT_EXPOSURE_PERCENT;
    private long minimumReserve = HouseBankroll.DEFAULT_MINIMUM_RESERVE;

    private EconomyData() {
    }

    private static EconomyData load(CompoundTag tag, HolderLookup.Provider registries) {
        EconomyData data = new EconomyData();
        readInto(tag.getCompound(KEY_CONVERSIONS), data.conversions);
        readInto(tag.getCompound(KEY_SHOP), data.shopPrices);
        if (tag.contains(KEY_EXPOSURE_PERCENT)) {
            data.exposurePercent = Math.clamp(tag.getInt(KEY_EXPOSURE_PERCENT), 1, 100);
        }
        if (tag.contains(KEY_MINIMUM_RESERVE)) {
            data.minimumReserve = Math.max(0, tag.getLong(KEY_MINIMUM_RESERVE));
        }
        return data;
    }

    private static void readInto(CompoundTag source, Map<String, Long> target) {
        for (String itemId : source.getAllKeys()) {
            long value = source.getLong(itemId);
            if (value > 0) {
                target.put(itemId, value);
            }
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put(KEY_CONVERSIONS, writeFrom(conversions));
        tag.put(KEY_SHOP, writeFrom(shopPrices));
        tag.putInt(KEY_EXPOSURE_PERCENT, exposurePercent);
        tag.putLong(KEY_MINIMUM_RESERVE, minimumReserve);
        return tag;
    }

    private static CompoundTag writeFrom(Map<String, Long> source) {
        CompoundTag out = new CompoundTag();
        source.forEach(out::putLong);
        return out;
    }

    public static EconomyData get(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    // --- Conversion values --------------------------------------------------

    public Map<String, Long> conversions() {
        return Collections.unmodifiableMap(conversions);
    }

    public Optional<Long> conversionOf(String itemId) {
        return Optional.ofNullable(conversions.get(itemId));
    }

    /** Sets or replaces a value. The caller must have validated it first. */
    public void setConversion(String itemId, long credits) {
        if (credits <= 0) {
            throw new IllegalArgumentException("Value must be positive: " + credits);
        }
        conversions.put(itemId, credits);
        setDirty();
    }

    public Optional<Long> removeConversion(String itemId) {
        Long removed = conversions.remove(itemId);
        if (removed != null) {
            setDirty();
        }
        return Optional.ofNullable(removed);
    }

    // --- Shop prices --------------------------------------------------------

    public Map<String, Long> shopPrices() {
        return Collections.unmodifiableMap(shopPrices);
    }

    public Optional<Long> shopPriceOf(String itemId) {
        return Optional.ofNullable(shopPrices.get(itemId));
    }

    public void setShopPrice(String itemId, long price) {
        if (price <= 0) {
            throw new IllegalArgumentException("Price must be positive: " + price);
        }
        shopPrices.put(itemId, price);
        setDirty();
    }

    public Optional<Long> removeShopPrice(String itemId) {
        Long removed = shopPrices.remove(itemId);
        if (removed != null) {
            setDirty();
        }
        return Optional.ofNullable(removed);
    }

    // --- House risk settings ---------------------------------------------------

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
     * twenty the maths stops protecting anything.
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

    /**
     * Command values layered over datapack defaults.
     * <p>
     * Entries whose item is no longer registered are dropped here rather than
     * at load time, so uninstalling a mod temporarily does not erase its
     * prices from the save.
     */
    public CreditValueTable mergedWith(CreditValueTable defaults) {
        Map<String, Long> merged = new LinkedHashMap<>();
        for (String itemId : defaults.itemIds()) {
            defaults.valueOf(itemId).ifPresent(value -> merged.put(itemId, value));
        }
        merged.putAll(conversions);

        CreditValueTable.Builder builder = CreditValueTable.builder();
        merged.forEach((itemId, value) -> {
            if (ItemIds.exists(itemId)) {
                builder.put(itemId, value);
            }
        });
        return builder.build();
    }
}
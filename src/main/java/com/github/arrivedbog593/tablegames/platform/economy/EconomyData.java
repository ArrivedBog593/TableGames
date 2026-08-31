package com.github.arrivedbog593.tablegames.platform.economy;

import com.github.arrivedbog593.tablegames.engine.economy.CreditValueTable;
import com.github.arrivedbog593.tablegames.engine.economy.HouseBankroll;
import com.mojang.logging.LogUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Conversion values, shop prices, and the casino's risk settings, edited in
 * game and saved with the world.
 * <p>
 * Kept apart from {@link CreditStorage} on purpose. Balances change many
 * times a minute while prices change a few times a month, and splitting them
 * means a corrupt balance file cannot take the whole price list with it.
 * <p>
 * Datapack JSON still works and acts as the default. Anything set with a
 * command overrides it because someone standing in the world with the item
 * in hand has more current intent than a file written once.
 * <p>
 * Server thread only.
 */
public final class EconomyData extends SavedData {

    private static final String DATA_NAME = "tablegames_economy";
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String KEY_CONVERSIONS = "conversions";
    private static final String KEY_SHOP = "shop";

    /** The catalog in the stack format. Its absence means the old one is live. */
    private static final String KEY_SHOP_ENTRIES = "shop_entries";

    private static final String KEY_EXPOSURE_PERCENT = "house_exposure_percent";
    private static final String KEY_MINIMUM_RESERVE = "house_minimum_reserve";
    private static final String KEY_SPREAD_PERCENT = "buyback_spread_percent";
    private static final String KEY_ADMINISTRATORS = "administrators";

    public static final SavedData.Factory<EconomyData> FACTORY =
            new SavedData.Factory<>(EconomyData::new, EconomyData::load, null);

    private final Map<String, Long> conversions = new LinkedHashMap<>();
    /**
     * What the shop sells, in the order it was added.
     * <p>
     * A list of whole stacks rather than a map of item id to price, because
     * two entries can sell the same item — a plain sword and an enchanted one
     * — and an id-keyed map cannot hold both.
     */
    private final List<ShopEntry> shopEntries = new ArrayList<>();

    private int exposurePercent = HouseBankroll.DEFAULT_EXPOSURE_PERCENT;
    private long minimumReserve = HouseBankroll.DEFAULT_MINIMUM_RESERVE;
    private int spreadPercent = CreditValueTable.NO_SPREAD;

    /**
     * Who may configure the casino without being an operator.
     * <p>
     * The permission itself, not a record of who holds a card. Revoking is
     * therefore instant and complete: a card still in somebody's pocket, or
     * in a chest nobody can find, stops opening anything the moment its owner
     * leaves this set.
     */
    private final Set<UUID> administrators = new LinkedHashSet<>();

    private EconomyData() {
    }

    private static EconomyData load(CompoundTag tag, HolderLookup.Provider registries) {
        EconomyData data = new EconomyData();
        readInto(tag.getCompound(KEY_CONVERSIONS), data.conversions);
        readShop(tag, data, registries);
        if (tag.contains(KEY_EXPOSURE_PERCENT)) {
            data.exposurePercent = Math.clamp(tag.getInt(KEY_EXPOSURE_PERCENT), 1, 100);
        }
        if (tag.contains(KEY_MINIMUM_RESERVE)) {
            data.minimumReserve = Math.max(0, tag.getLong(KEY_MINIMUM_RESERVE));
        }
        if (tag.contains(KEY_ADMINISTRATORS)) {
            ListTag listed = tag.getList(KEY_ADMINISTRATORS, Tag.TAG_INT_ARRAY);
            for (int i = 0; i < listed.size(); i++) {
                int[] raw = listed.getIntArray(i);
                if (raw.length == 4) {
                    data.administrators.add(UUIDUtil.uuidFromIntArray(raw));
                }
            }
        }
        if (tag.contains(KEY_SPREAD_PERCENT)) {
            data.spreadPercent = Math.clamp(tag.getInt(KEY_SPREAD_PERCENT),
                    CreditValueTable.NO_SPREAD, CreditValueTable.MAX_SPREAD_PERCENT);
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
    public @NotNull CompoundTag save(CompoundTag tag, HolderLookup.@NotNull Provider registries) {
        tag.put(KEY_CONVERSIONS, writeFrom(conversions));
        writeShop(tag, registries);
        tag.putInt(KEY_EXPOSURE_PERCENT, exposurePercent);
        tag.putLong(KEY_MINIMUM_RESERVE, minimumReserve);
        tag.putInt(KEY_SPREAD_PERCENT, spreadPercent);

        ListTag listed = new ListTag();
        for (UUID administrator : administrators) {
            listed.add(new IntArrayTag(UUIDUtil.uuidToIntArray(administrator)));
        }
        tag.put(KEY_ADMINISTRATORS, listed);
        return tag;
    }

    /**
     * Reads the catalog, converting the old format when that is all there
     * is.
     * <p>
     * The previous format stored item IDs against prices, so every migrated
     * entry becomes a single plain item at the same price — which is exactly
     * what the old shop sold, since it could not express anything else. The
     * old tag is simply ignored once the new one exists; nothing is deleted,
     * so a server that rolls the update back still has its catalog.
     */
    private static void readShop(CompoundTag tag, EconomyData data,
                                 HolderLookup.Provider registries) {
        if (tag.contains(KEY_SHOP_ENTRIES)) {
            ListTag stored = tag.getList(KEY_SHOP_ENTRIES, Tag.TAG_COMPOUND);
            RegistryOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
            for (int i = 0; i < stored.size(); i++) {
                ShopEntry.CODEC.parse(ops, stored.getCompound(i))
                        .resultOrPartial(error -> LOGGER.warn(
                                "[Economy] Dropped an unreadable shop entry: {}", error))
                        .ifPresent(data.shopEntries::add);
            }
            return;
        }

        CompoundTag legacy = tag.getCompound(KEY_SHOP);
        for (String itemId : legacy.getAllKeys()) {
            ItemIds.item(itemId).ifPresent(item -> data.shopEntries.add(
                    new ShopEntry(new ItemStack(item), legacy.getLong(itemId))));
        }
        if (!data.shopEntries.isEmpty()) {
            LOGGER.info("[Economy] Migrated {} shop entries to the stack format.",
                    data.shopEntries.size());
        }
    }

    private void writeShop(CompoundTag tag, HolderLookup.Provider registries) {
        RegistryOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
        ListTag stored = new ListTag();
        for (ShopEntry entry : shopEntries) {
            ShopEntry.CODEC.encodeStart(ops, entry)
                    .resultOrPartial(error -> LOGGER.error(
                            "[Economy] Could not save a shop entry: {}", error))
                    .ifPresent(stored::add);
        }
        tag.put(KEY_SHOP_ENTRIES, stored);
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

    /** The surcharge on buying items back, as a percentage. */
    public int spreadPercent() {
        return spreadPercent;
    }

    public void setSpreadPercent(int percent) {
        if (percent < 0 || percent > CreditValueTable.MAX_SPREAD_PERCENT) {
            throw new IllegalArgumentException("Spread out of range: " + percent);
        }
        this.spreadPercent = percent;
        setDirty();
    }

    /**
     * Whether this player is on the administration list.
     * <p>
     * Says nothing about operators, who need no listing. Callers that mean
     * "may configure the casino" should be asking
     * {@code AdminKeyItem.mayAdminister} instead, which covers both.
     */
    public boolean isAdministrator(UUID playerId) {
        return administrators.contains(playerId);
    }

    public Set<UUID> administrators() {
        return Set.copyOf(administrators);
    }

    /** @return false if they were already listed */
    public boolean addAdministrator(UUID playerId) {
        if (!administrators.add(playerId)) {
            return false;
        }
        setDirty();
        return true;
    }

    /** @return false if they were not listed to begin with */
    public boolean removeAdministrator(UUID playerId) {
        if (!administrators.remove(playerId)) {
            return false;
        }
        setDirty();
        return true;
    }

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

    /**
     * The catalog in order. An entry's number is its position here, counting
     * from one, so removing one renumbers everything after it.
     */
    public List<ShopEntry> shopEntries() {
        return List.copyOf(shopEntries);
    }

    /**
     * Looks an entry up by the number a player or a command sees.
     * <p>
     * One-based, because that is what the list shows. Out of range returns
     * empty rather than clamping: a number that no longer points anywhere has
     * to fail, not quietly hit a neighbor.
     */
    public Optional<ShopEntry> shopEntry(int number) {
        return number < 1 || number > shopEntries.size()
                ? Optional.empty()
                : Optional.of(shopEntries.get(number - 1));
    }

    /** Puts a stack on sale at the end of the catalog. */
    public int addShopEntry(ItemStack stack, long price) {
        shopEntries.add(new ShopEntry(stack.copy(), price));
        setDirty();
        return shopEntries.size();
    }

    /** Changes an entry's price, keeping its place and its stack. */
    public Optional<ShopEntry> repriceShopEntry(int number, long price) {
        if (number < 1 || number > shopEntries.size()) {
            return Optional.empty();
        }
        ShopEntry updated = new ShopEntry(shopEntries.get(number - 1).stack(), price);
        shopEntries.set(number - 1, updated);
        setDirty();
        return Optional.of(updated);
    }

    /** Takes an entry off sale. Everything after it moves up one. */
    public Optional<ShopEntry> removeShopEntry(int number) {
        if (number < 1 || number > shopEntries.size()) {
            return Optional.empty();
        }
        ShopEntry removed = shopEntries.remove(number - 1);
        setDirty();
        return Optional.of(removed);
    }

    /**
     * The cheapest shop price for each item type, for the exploit validator.
     * <p>
     * Cheapest because that is the one that could open a loop: the cashier
     * values an item by type and ignores components, so if any entry sells it
     * below its conversion value, players mint credits — whatever the other
     * entries charge.
     */
    public Map<String, Long> lowestShopPrices() {
        Map<String, Long> lowest = new LinkedHashMap<>();
        for (ShopEntry entry : shopEntries) {
            lowest.merge(entry.itemId(), entry.price(), Math::min);
        }
        return Collections.unmodifiableMap(lowest);
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
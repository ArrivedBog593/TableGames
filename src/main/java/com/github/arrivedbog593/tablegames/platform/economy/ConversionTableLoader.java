package com.github.arrivedbog593.tablegames.platform.economy;

import com.github.arrivedbog593.tablegames.engine.economy.CreditValueTable;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads default item-to-credit values from datapack JSON.
 * <p>
 * Files live at {@code data/<namespace>/tablegames/conversions/<name>.json}:
 * <pre>
 * {
 *   "values": {
 *     "minecraft:iron_ingot": 9,
 *     "minecraft:iron_block": 81
 *   }
 * }
 * </pre>
 * Several files merge, so a modpack can ship defaults without editing anyone
 * else's. Values set in-game with commands override whatever lands here.
 * <p>
 * Items are matched purely by registry id. This mod never imports another
 * mod's code, which is what lets it price currency items from any mod at all,
 * or none — plain vanilla ingots work exactly as well.
 * <p>
 * Unknown ids are skipped with a warning rather than treated as errors: a
 * server that removes a mod should keep booting, just without that item being
 * convertible.
 */
public final class ConversionTableLoader extends SimpleJsonResourceReloadListener {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private static final String DIRECTORY = "conversions";
    private static final String KEY_VALUES = "values";

    private CreditValueTable table = CreditValueTable.empty();

    public ConversionTableLoader() {
        super(GSON, DIRECTORY);
    }

    /** The defaults as last loaded. Never null; empty before the first reload. */
    public CreditValueTable table() {
        return table;
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files,
                         @NotNull ResourceManager resourceManager,
                         @NotNull ProfilerFiller profiler) {
        Map<String, Long> merged = new LinkedHashMap<>();
        int skipped = 0;

        for (Map.Entry<ResourceLocation, JsonElement> file : files.entrySet()) {
            ResourceLocation source = file.getKey();
            if (!file.getValue().isJsonObject()) {
                LOGGER.warn("Conversion file {} is not a JSON object; skipping", source);
                continue;
            }
            JsonObject root = file.getValue().getAsJsonObject();
            if (!root.has(KEY_VALUES) || !root.get(KEY_VALUES).isJsonObject()) {
                LOGGER.warn("Conversion file {} has no \"{}\" object; skipping",
                        source, KEY_VALUES);
                continue;
            }

            for (Map.Entry<String, JsonElement> entry
                    : root.getAsJsonObject(KEY_VALUES).entrySet()) {
                String itemId = entry.getKey();
                long credits;
                try {
                    credits = entry.getValue().getAsLong();
                } catch (RuntimeException e) {
                    LOGGER.warn("Value for {} in {} is not a number; skipping", itemId, source);
                    skipped++;
                    continue;
                }
                if (credits <= 0) {
                    LOGGER.warn("Value for {} in {} must be positive, was {}; skipping",
                            itemId, source, credits);
                    skipped++;
                    continue;
                }
                if (!ItemIds.exists(itemId)) {
                    LOGGER.warn("Item {} from {} is not registered; skipping. This is "
                                    + "expected when the mod providing it is not installed.",
                            itemId, source);
                    skipped++;
                    continue;
                }
                Long previous = merged.put(itemId, credits);
                if (previous != null && previous != credits) {
                    LOGGER.warn("Item {} was already worth {} credits; {} overrides it with {}",
                            itemId, previous, source, credits);
                }
            }
        }

        CreditValueTable.Builder builder = CreditValueTable.builder();
        merged.forEach(builder::put);
        this.table = builder.build();

        LOGGER.info("Loaded {} default conversion value(s) from {} file(s){}",
                table.size(), files.size(),
                skipped > 0 ? " (" + skipped + " entries skipped)" : "");
    }
}
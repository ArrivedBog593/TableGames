package com.github.arrivedbog593.tablegames.platform.network;

import com.github.arrivedbog593.tablegames.TableGames;
import com.github.arrivedbog593.tablegames.platform.economy.EconomyData;
import com.github.arrivedbog593.tablegames.platform.economy.ItemIds;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * What the shop sells and for how much, sent as a player opens it.
 * <p>
 * Sent rather than cached because the catalogue is edited live with commands.
 * A client holding yesterday's prices would show a bargain that the server
 * then refuses, which reads as a bug rather than as staleness.
 * <p>
 * Ordering is cheapest first, unlike the cashier: someone browsing a shop is
 * usually looking for what they can afford, not for what they cannot.
 *
 * @param entries item id and price, cheapest first
 */
public record ShopCatalogPayload(List<Entry> entries) implements CustomPacketPayload {

    public static final Type<ShopCatalogPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TableGames.MOD_ID, "shop_catalog"));

    /** One thing for sale. */
    public record Entry(String itemId, long price) {
        public static final StreamCodec<ByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Entry::itemId,
                ByteBufCodecs.VAR_LONG, Entry::price,
                Entry::new);
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, ShopCatalogPayload> STREAM_CODEC =
            StreamCodec.composite(
                    Entry.STREAM_CODEC.apply(ByteBufCodecs.list()), ShopCatalogPayload::entries,
                    ShopCatalogPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * Snapshots the live shop.
     * <p>
     * Entries whose item is no longer registered are dropped here rather than
     * sent and rendered as a missing-texture cube.
     */
    public static ShopCatalogPayload current(MinecraftServer server) {
        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<String, Long> priced : EconomyData.get(server).shopPrices().entrySet()) {
            if (ItemIds.exists(priced.getKey())) {
                entries.add(new Entry(priced.getKey(), priced.getValue()));
            }
        }
        entries.sort(Comparator
                .comparingLong(Entry::price)
                .thenComparing(Entry::itemId));
        return new ShopCatalogPayload(List.copyOf(entries));
    }

    /** Handed to the client's holder for the screen to read. */
    public static void handleOnClient(ShopCatalogPayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
                com.github.arrivedbog593.tablegames.client.ClientShopState.accept(payload));
    }
}

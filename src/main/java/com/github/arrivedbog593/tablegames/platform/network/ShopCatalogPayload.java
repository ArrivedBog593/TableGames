package com.github.arrivedbog593.tablegames.platform.network;

import com.github.arrivedbog593.tablegames.TableGames;
import com.github.arrivedbog593.tablegames.platform.economy.EconomyData;
import net.minecraft.world.item.ItemStack;
import com.github.arrivedbog593.tablegames.platform.economy.ShopEntry;
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

/**
 * What the shop sells and for how much, sent as a player opens it.
 * <p>
 * Sent rather than cached because the catalog is edited live with commands.
 * A client holding yesterday's prices would show a bargain that the server
 * then refuses, which reads as a bug rather than as staleness.
 * <p>
 * Ordering is cheapest first, unlike the cashier: someone browsing a shop is
 * usually looking for what they can afford, not for what they cannot.
 *
 * @param entries item id and price, cheapest first
 */
public record ShopCatalogPayload(List<Entry> entries) implements CustomPacketPayload {

    /** A catalog larger than this is a mistake, not a shop. */
    public static final int MAX_ENTRIES = 512;

    public static final Type<ShopCatalogPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TableGames.MOD_ID, "shop_catalog"));

    /**
     * One thing for sale.
     * <p>
     * The whole stack goes over the wire, so the screen can render the
     * enchantment glint and the real tooltip rather than a name and a guess.
     *
     * @param number the entry's place in the catalog, counting from one, and
     *               what a purchase quotes back. It shifts when an admin
     *               removes something, so the server always re-reads the
     *               catalog rather than trusting what the client believed
     * @param stack exactly what a purchase delivers
     * @param price credits per purchase
     */
    public record Entry(int number, ItemStack stack, long price) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, Entry::number,
                        ItemStack.STREAM_CODEC, Entry::stack,
                        ByteBufCodecs.VAR_LONG, Entry::price,
                        Entry::new);
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, ShopCatalogPayload> STREAM_CODEC =
            StreamCodec.composite(
                    Entry.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_ENTRIES)),
                    ShopCatalogPayload::entries,
                    ShopCatalogPayload::new);

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * Snapshots the live shop.
     * <p>
     * Entries whose item is no longer registered are dropped here rather than
     * sent and rendered as a missing-texture cube.
     */
    public static ShopCatalogPayload current(MinecraftServer server) {
        List<ShopEntry> sold = EconomyData.get(server).shopEntries();
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < sold.size() && i < MAX_ENTRIES; i++) {
            entries.add(new Entry(i + 1, sold.get(i).prototype(), sold.get(i).price()));
        }
        // Sent in catalog order. How a player wants it sorted is their own
        // business and the screen handles it locally, so the packet stays the
        // plain truth about what the shop holds.
        return new ShopCatalogPayload(List.copyOf(entries));
    }

    /** Handed to the client's holder for the screen to read. */
    public static void handleOnClient(ShopCatalogPayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
                com.github.arrivedbog593.tablegames.client.ClientShopState.accept(payload));
    }
}

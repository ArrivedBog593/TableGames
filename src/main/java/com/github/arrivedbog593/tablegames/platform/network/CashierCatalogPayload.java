package com.github.arrivedbog593.tablegames.platform.network;

import com.github.arrivedbog593.tablegames.TableGames;
import com.github.arrivedbog593.tablegames.platform.economy.EconomyEvents;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The list of convertible items and their values, sent to a player as they
 * open a cashier.
 * <p>
 * Sent rather than assumed because the table is edited live with commands: a
 * client that cached it at login would show yesterday's prices. Sending it on
 * open is a few hundred bytes and always current.
 * <p>
 * This is public information — it is what the cashier displays — so there is
 * nothing to redact here. Payloads carrying game state will not be so simple.
 *
 * @param entries item id and what one of it is worth, dearest-first
 */
public record CashierCatalogPayload(List<Entry> entries) implements CustomPacketPayload {

    public static final Type<CashierCatalogPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TableGames.MOD_ID, "cashier_catalog"));

    /**
     * One convertible item, priced in both directions.
     * <p>
     * Two figures rather than one, because the cashier stopped being
     * symmetrical the moment a buyback surcharge existed. Sending only the
     * sale value left the screen quoting the price of selling a diamond next
     * to a button that buys one, which is the single place a wrong number
     * does the most damage.
     *
     * @param itemId  what is being priced
     * @param value   credits paid for handing one in
     * @param buyback credits charged for taking one out, surcharge included
     */
    public record Entry(String itemId, long value, long buyback) {
        public static final StreamCodec<ByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Entry::itemId,
                ByteBufCodecs.VAR_LONG, Entry::value,
                ByteBufCodecs.VAR_LONG, Entry::buyback,
                Entry::new);
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, CashierCatalogPayload> STREAM_CODEC =
            StreamCodec.composite(
                    Entry.STREAM_CODEC.apply(ByteBufCodecs.list()), CashierCatalogPayload::entries,
                    CashierCatalogPayload::new);

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Snapshots the live conversion table. Server side only. */
    public static CashierCatalogPayload current() {
        List<Entry> entries = new ArrayList<>();
        var table = EconomyEvents.economy().table();
        for (String itemId : table.itemIds()) {
            table.valueOf(itemId).ifPresent(value ->
                    entries.add(new Entry(itemId, value, table.buybackUnit(itemId))));
        }
        entries.sort(Comparator
                .comparingLong(Entry::value).reversed()
                .thenComparing(Entry::itemId));
        return new CashierCatalogPayload(List.copyOf(entries));
    }

    /**
     * Handed to the client's holder for the screen to read.
     * <p>
     * Bounced onto the main thread by the context: payload handlers run on
     * the network thread, and touching client state from there is how race
     * conditions get shipped.
     */
    public static void handleOnClient(CashierCatalogPayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
                com.github.arrivedbog593.tablegames.client.ClientCashierState.accept(payload));
    }
}
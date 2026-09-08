package com.github.arrivedbog593.tablegames.platform.network;

import com.github.arrivedbog593.tablegames.TableGames;
import com.github.arrivedbog593.tablegames.platform.economy.CreditExchange;
import com.github.arrivedbog593.tablegames.platform.economy.CreditFormat;
import com.github.arrivedbog593.tablegames.platform.economy.CreditStorage;
import com.github.arrivedbog593.tablegames.platform.economy.EconomyEvents;
import com.github.arrivedbog593.tablegames.platform.menu.CashierMenu;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * A buyback on its way to the cashier.
 * <p>
 * Replaces a pair of button ids that carried the entry's <em>position</em> in
 * the catalog. That position was never stable: the cashier's catalog is sent
 * sorted by value, so changing the price of any one item reordered the whole
 * list, and a click already on its way arrived pointing at something else.
 * A player asking to buy back a diamond could be handed an iron ingot, at
 * iron's price, and nothing about it looked wrong from either side.
 * <p>
 * Naming the item removes the problem rather than narrowing it. The cashier
 * prices by item id — one value per id — so the id is the identity the data
 * already has, and no amount of reordering can make it point elsewhere.
 * <p>
 * Each line also carries the price the screen was showing. The server reads
 * the real price from its own table and only ever compares against the quoted
 * one, so a player is never charged a number they did not see.
 *
 * @param lines what the player is buying back
 */
public record CashierRedeemPayload(List<Line> lines) implements CustomPacketPayload {

    public static final Type<CashierRedeemPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(TableGames.MOD_ID, "cashier_redeem"));

    /**
     * One line of the buyback.
     *
     * @param itemId            the registry id, as text
     * @param count             how many
     * @param expectedUnitPrice what one cost on the screen that sent this,
     *                          surcharge included
     */
    public record Line(String itemId, int count, long expectedUnitPrice) {
        public static final StreamCodec<ByteBuf, Line> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Line::itemId,
                ByteBufCodecs.VAR_INT, Line::count,
                ByteBufCodecs.VAR_LONG, Line::expectedUnitPrice,
                Line::new);
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, CashierRedeemPayload>
            STREAM_CODEC = StreamCodec.composite(
            Line.STREAM_CODEC.apply(ByteBufCodecs.list(CreditExchange.MAX_LINES)),
            CashierRedeemPayload::lines,
            CashierRedeemPayload::new);

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * Hands the request to the exchange.
     * <p>
     * Having the cashier open is checked rather than assumed: a payload can
     * arrive from something that never opened one, and the menu is also what
     * has to be told the balance moved afterward.
     */
    public static void handleOnServer(CashierRedeemPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!(player.containerMenu instanceof CashierMenu menu)) {
                return;
            }

            List<CreditExchange.Line> lines = new ArrayList<>();
            for (Line line : payload.lines()) {
                lines.add(new CreditExchange.Line(
                        line.itemId(), line.count(), line.expectedUnitPrice()));
            }

            CreditStorage storage = CreditStorage.get(player.server);
            CreditExchange.CartResult result = CreditExchange.redeemCart(
                    player, lines, EconomyEvents.economy(), storage);

            if (!result.success()) {
                player.sendSystemMessage(Component.translatable(
                        result.failureKey(), result.argumentArray()));
                // A refusal almost always means this player's catalog is out
                // of date. Sent only to them: everybody else's is still fine.
                PacketDistributor.sendToPlayer(player, CashierCatalogPayload.current());
                return;
            }

            player.sendSystemMessage(Component.translatable(
                    "tablegames.exchange.cart_redeemed",
                    result.itemCount(), CreditFormat.of(result.credits())));
            // Refreshes the balance data slots as a side effect, which is what
            // redraws the figure at the top of the screen.
            menu.broadcastChanges();
        });
    }
}

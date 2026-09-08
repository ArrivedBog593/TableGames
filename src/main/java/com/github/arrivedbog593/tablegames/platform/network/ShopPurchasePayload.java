package com.github.arrivedbog593.tablegames.platform.network;

import com.github.arrivedbog593.tablegames.TableGames;
import com.github.arrivedbog593.tablegames.platform.economy.CreditFormat;
import com.github.arrivedbog593.tablegames.platform.economy.CreditStorage;
import com.github.arrivedbog593.tablegames.platform.economy.ShopExchange;
import com.github.arrivedbog593.tablegames.platform.menu.ShopMenu;
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
 * A shopping cart on its way to the till.
 * <p>
 * A payload rather than a menu button because {@code clickMenuButton} carries
 * a single {@code int}, and a cart is a list of three numbers per line. The
 * old encoding packed the entry number into the button id and had nowhere to
 * put a quantity, which is why buying was one click for one and shift-click
 * for a stack — two arbitrary points on a scale the player should be naming
 * themselves.
 * <p>
 * Each line carries the price the screen was showing. The server still reads
 * the real price from its own catalog and still refuses to be told what
 * anything costs; the quoted figure is only ever compared, never used to
 * charge. What it buys is the guarantee that a player is never charged a
 * number they did not see — an administrator repricing an entry while
 * somebody reads it used to charge the new figure silently, in whichever
 * direction it moved.
 *
 * @param lines what the player has in their cart
 */
public record ShopPurchasePayload(List<Line> lines) implements CustomPacketPayload {

    public static final Type<ShopPurchasePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(TableGames.MOD_ID, "shop_purchase"));

    /**
     * One line of the cart.
     *
     * @param number            the entry's place in the catalog as the server
     *                          sent it, not the row it was drawn in. The
     *                          screen sorts and filters locally, so the two
     *                          are rarely the same
     * @param count             how many lots of that entry
     * @param expectedUnitPrice what one-lot cost on the screen that sent this
     */
    public record Line(int number, int count, long expectedUnitPrice) {
        public static final StreamCodec<ByteBuf, Line> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Line::number,
                ByteBufCodecs.VAR_INT, Line::count,
                ByteBufCodecs.VAR_LONG, Line::expectedUnitPrice,
                Line::new);
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, ShopPurchasePayload> STREAM_CODEC =
            StreamCodec.composite(
                    Line.STREAM_CODEC.apply(ByteBufCodecs.list(ShopExchange.MAX_LINES)),
                    ShopPurchasePayload::lines,
                    ShopPurchasePayload::new);

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * Rings up the cart.
     * <p>
     * Having the shop screen open is checked here rather than assumed. A
     * payload can arrive from something that never opened one, and the menu
     * is also what has to be told the balance moved afterward.
     */
    public static void handleOnServer(ShopPurchasePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!(player.containerMenu instanceof ShopMenu menu)) {
                return;
            }

            List<ShopExchange.Line> lines = new ArrayList<>();
            for (Line line : payload.lines()) {
                lines.add(new ShopExchange.Line(
                        line.number(), line.count(), line.expectedUnitPrice()));
            }

            CreditStorage storage = CreditStorage.get(player.server);
            ShopExchange.CartResult result = ShopExchange.buyAll(player, lines, storage);

            if (!result.success()) {
                player.sendSystemMessage(Component.translatable(
                        result.failureKey(), result.argumentArray()));
                // A refusal almost always means this player's catalog is out
                // of date, and leaving them looking at the numbers that were
                // just rejected would have them click again and fail again.
                // Sent only to them: everybody else's copy is still correct.
                PacketDistributor.sendToPlayer(player, ShopCatalogPayload.current(player.server));
                return;
            }

            player.sendSystemMessage(Component.translatable(
                    "tablegames.shop.cart_bought",
                    result.itemCount(), CreditFormat.of(result.credits())));
            // Refreshes the balance data slots as a side effect, which is what
            // redraws the figure at the top of the screen.
            menu.broadcastChanges();
        });
    }
}
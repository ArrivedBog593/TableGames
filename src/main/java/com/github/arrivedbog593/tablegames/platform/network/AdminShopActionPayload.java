package com.github.arrivedbog593.tablegames.platform.network;

import com.github.arrivedbog593.tablegames.TableGames;
import com.github.arrivedbog593.tablegames.platform.economy.EconomyData;
import com.github.arrivedbog593.tablegames.platform.economy.EconomyEvents;
import com.github.arrivedbog593.tablegames.platform.economy.ShopEntry;
import com.github.arrivedbog593.tablegames.platform.item.AdminKeyItem;
import com.github.arrivedbog593.tablegames.platform.menu.AdminShopMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Adding, repricing, and removing what the shop sells.
 * <p>
 * A payload rather than a menu button because two of the three carry a price,
 * and {@code clickMenuButton} has only an integer to work with. Packing a
 * price into a button id would have capped what a shop could charge at
 * whatever the encoding left over.
 * <p>
 * Every field is a request. The server re-checks who is asking what they are
 * holding and whether the entry still exists, because a screen open for a
 * while is a screen whose idea of the catalog may be out of date.
 *
 * @param kind   what is being asked for
 * @param number the catalog position for repricing and removing
 * @param price  credits for adding and repricing
 */
public record AdminShopActionPayload(int kind, int number, long price)
        implements CustomPacketPayload {

    public static final int KIND_ADD = 0;
    public static final int KIND_REPRICE = 1;
    public static final int KIND_REMOVE = 2;

    public static final Type<AdminShopActionPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(TableGames.MOD_ID, "admin_shop_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AdminShopActionPayload>
            STREAM_CODEC = StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, AdminShopActionPayload::kind,
                    ByteBufCodecs.VAR_INT, AdminShopActionPayload::number,
                    ByteBufCodecs.VAR_LONG, AdminShopActionPayload::price,
                    AdminShopActionPayload::new);

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static AdminShopActionPayload add(long price) {
        return new AdminShopActionPayload(KIND_ADD, 0, price);
    }

    public static AdminShopActionPayload reprice(int number, long price) {
        return new AdminShopActionPayload(KIND_REPRICE, number, price);
    }

    public static AdminShopActionPayload remove(int number) {
        return new AdminShopActionPayload(KIND_REMOVE, number, 0);
    }

    public static void handleOnServer(AdminShopActionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            // Having the screen open is not permission. It could have been
            // revoked since it opened, and the packet could have been sent by
            // something that never opened one at all.
            if (!AdminKeyItem.mayAdminister(player, player.getMainHandItem())) {
                return;
            }
            if (!(player.containerMenu instanceof AdminShopMenu menu)) {
                return;
            }

            switch (payload.kind()) {
                case KIND_ADD -> add(payload, player, menu);
                case KIND_REPRICE -> reprice(payload, player);
                case KIND_REMOVE -> remove(payload, player);
                default -> {
                }
            }
            PacketDistributor.sendToPlayer(player,
                    ShopCatalogPayload.current(player.server));
        });
    }

    private static void add(AdminShopActionPayload payload, ServerPlayer player,
                            AdminShopMenu menu) {
        ItemStack listing = menu.held();
        if (listing.isEmpty() || payload.price() < 1) {
            return;
        }
        // Copied, not consumed. The stack only describes the sale; taking it
        // would cost an admin the enchanted sword they used to set one up.
        int number = EconomyData.get(player.server).addShopEntry(listing.copy(), payload.price());
        EconomyEvents.economy().rebuild(player.server);
        menu.clearInput();

        player.displayClientMessage(Component.translatable(
                "tablegames.command.shop.added",
                listing.getHoverName(), payload.price(), number), false);
    }

    private static void reprice(AdminShopActionPayload payload, ServerPlayer player) {
        if (payload.price() < 1) {
            return;
        }
        Optional<ShopEntry> updated = EconomyData.get(player.server)
                .repriceShopEntry(payload.number(), payload.price());
        if (updated.isEmpty()) {
            player.displayClientMessage(Component.translatable(
                    "tablegames.command.shop.no_such_entry", payload.number()), true);
            return;
        }
        EconomyEvents.economy().rebuild(player.server);
        player.displayClientMessage(Component.translatable(
                "tablegames.command.shop.repriced",
                updated.get().stack().getHoverName(), payload.price(),
                payload.number()), false);
    }

    private static void remove(AdminShopActionPayload payload, ServerPlayer player) {
        Optional<ShopEntry> removed = EconomyData.get(player.server)
                .removeShopEntry(payload.number());
        if (removed.isEmpty()) {
            player.displayClientMessage(Component.translatable(
                    "tablegames.command.shop.no_such_entry", payload.number()), true);
            return;
        }
        EconomyEvents.economy().rebuild(player.server);
        player.displayClientMessage(Component.translatable(
                "tablegames.command.shop.removed",
                removed.get().stack().getHoverName(), payload.number()), false);
    }
}

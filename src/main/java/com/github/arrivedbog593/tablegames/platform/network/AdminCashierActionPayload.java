package com.github.arrivedbog593.tablegames.platform.network;

import com.github.arrivedbog593.tablegames.TableGames;
import com.github.arrivedbog593.tablegames.engine.economy.CreditValueTable;
import com.github.arrivedbog593.tablegames.engine.economy.EconomyIssue;
import com.github.arrivedbog593.tablegames.platform.economy.AdminNotices;
import com.github.arrivedbog593.tablegames.platform.economy.EconomyData;
import com.github.arrivedbog593.tablegames.platform.economy.EconomyEvents;
import com.github.arrivedbog593.tablegames.platform.economy.ItemIds;
import com.github.arrivedbog593.tablegames.platform.item.AdminKeyItem;
import com.github.arrivedbog593.tablegames.platform.menu.AdminCashierMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Taking an item off the conversion table and setting the buyback surcharge.
 * <p>
 * Pricing is not here: values are staged on the screen and sent together by
 * {@link AdminCashierBatchPayload}, because prices tied by recipes can only
 * move as a set. These two have no such problem — a removal and a surcharge
 * each stand on their own.
 * <p>
 * The item is named rather than numbered, since a conversion table is keyed by
 * item and not by position, so neither request goes stale the way a shop entry
 * number does when somebody else deletes a row.
 *
 * @param kind   what is being asked for
 * @param itemId which item, for pricing and unpricing
 * @param value  credits, or the surcharge percentage
 */
public record AdminCashierActionPayload(int kind, String itemId, long value)
        implements CustomPacketPayload {

    /** Stop converting an item. */
    public static final int KIND_REMOVE = 2;
    /** Set the buyback surcharge. */
    public static final int KIND_SPREAD = 3;

    public static final Type<AdminCashierActionPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(TableGames.MOD_ID, "admin_cashier_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AdminCashierActionPayload>
            STREAM_CODEC = StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, AdminCashierActionPayload::kind,
                    ByteBufCodecs.STRING_UTF8, AdminCashierActionPayload::itemId,
                    ByteBufCodecs.VAR_LONG, AdminCashierActionPayload::value,
                    AdminCashierActionPayload::new);

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static AdminCashierActionPayload remove(String itemId) {
        return new AdminCashierActionPayload(KIND_REMOVE, itemId, 0);
    }

    public static AdminCashierActionPayload spread(int percent) {
        return new AdminCashierActionPayload(KIND_SPREAD, "", percent);
    }

    public static void handleOnServer(AdminCashierActionPayload payload, IPayloadContext context) {
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
            if (!(player.containerMenu instanceof AdminCashierMenu menu)) {
                return;
            }

            switch (payload.kind()) {
                case KIND_REMOVE -> remove(payload.itemId(), player);
                case KIND_SPREAD -> spread(payload, player, menu);
                default -> {
                }
            }
        });
    }

    private static void remove(String itemId, ServerPlayer player) {
        if (itemId.isEmpty()) {
            return;
        }
        if (EconomyData.get(player.server).removeConversion(itemId).isEmpty()) {
            // An item priced by a datapack is listed, is convertible, and
            // still cannot be removed here. Saying it is "not listed" sends
            // an admin looking for a bug that is not there.
            player.sendSystemMessage(EconomyEvents.economy().isFromDatapack(itemId)
                    ? Component.translatable("tablegames.command.economy.from_datapack",
                    ItemIds.displayName(itemId))
                    : Component.translatable("tablegames.command.economy.not_listed",
                    ItemIds.displayName(itemId)));
            return;
        }
        EconomyEvents.economy().rebuild(player.server);

        AdminNotices.announce(player, Component.translatable(
                "tablegames.command.economy.removed", ItemIds.displayName(itemId)));

        // Removing an item can leave the rest without the recipe links that
        // justified their values, so say so rather than let it pass silently.
        warn(player, EconomyEvents.economy().issues());
    }

    private static void spread(AdminCashierActionPayload payload, ServerPlayer player,
                               AdminCashierMenu menu) {
        int percent = (int) payload.value();
        if (percent < CreditValueTable.NO_SPREAD
                || percent > CreditValueTable.MAX_SPREAD_PERCENT) {
            return;
        }
        EconomyData.get(player.server).setSpreadPercent(percent);
        EconomyEvents.economy().rebuild(player.server);
        menu.refreshSpread();

        AdminNotices.announce(player, percent == CreditValueTable.NO_SPREAD
                ? Component.translatable("tablegames.house.spread_off")
                : Component.translatable("tablegames.house.spread_set", percent));
    }

    private static void warn(ServerPlayer player, List<EconomyIssue> problems) {
        for (EconomyIssue issue : problems) {
            if (!issue.isError()) {
                player.sendSystemMessage(Component.literal(issue.message())
                        .withStyle(ChatFormatting.YELLOW));
            }
        }
    }
}

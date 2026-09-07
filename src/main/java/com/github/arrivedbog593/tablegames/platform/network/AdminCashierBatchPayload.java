package com.github.arrivedbog593.tablegames.platform.network;

import com.github.arrivedbog593.tablegames.TableGames;
import com.github.arrivedbog593.tablegames.engine.economy.EconomyIssue;
import com.github.arrivedbog593.tablegames.platform.economy.AdminNotices;
import com.github.arrivedbog593.tablegames.platform.economy.EconomyData;
import com.github.arrivedbog593.tablegames.platform.economy.EconomyEvents;
import com.github.arrivedbog593.tablegames.platform.economy.ItemIds;
import com.github.arrivedbog593.tablegames.platform.item.AdminKeyItem;
import com.github.arrivedbog593.tablegames.platform.menu.AdminCashierMenu;
import io.netty.buffer.ByteBuf;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Several conversion values, applied as one change.
 * <p>
 * The prices are tied to each other by recipes, so some of them cannot be moved
 * one at a time. Nine diamonds make a block: with the two already in ratio,
 * raising either one alone opens a loop and is correctly refused, and there is
 * no order that works. The pair is only movable together.
 * <p>
 * So the screen stages changes and sends them in one packet. The server
 * validates the whole set against the live table and then either takes all of
 * them or none — a partial application would leave the economy in exactly the
 * state the validation exists to prevent.
 *
 * @param changes what each item should be worth
 */
public record AdminCashierBatchPayload(List<Change> changes) implements CustomPacketPayload {

    /** How many items one screen may reprice at once. */
    public static final int MAX_CHANGES = 64;

    public static final Type<AdminCashierBatchPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(TableGames.MOD_ID, "admin_cashier_batch"));

    /**
     * One proposed value.
     *
     * @param itemId what is being priced
     * @param value  credits paid for handing one in
     */
    public record Change(String itemId, long value) {
        public static final StreamCodec<ByteBuf, Change> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Change::itemId,
                ByteBufCodecs.VAR_LONG, Change::value,
                Change::new);
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, AdminCashierBatchPayload>
            STREAM_CODEC = StreamCodec.composite(
                    Change.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_CHANGES)),
                    AdminCashierBatchPayload::changes,
                    AdminCashierBatchPayload::new);

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleOnServer(AdminCashierBatchPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!AdminKeyItem.mayAdminister(player, player.getMainHandItem())) {
                return;
            }
            if (!(player.containerMenu instanceof AdminCashierMenu menu)) {
                return;
            }

            Map<String, Long> changes = new LinkedHashMap<>();
            for (Change change : payload.changes()) {
                if (!change.itemId().isEmpty() && change.value() >= 1
                        && ItemIds.item(change.itemId()).isPresent()) {
                    changes.put(change.itemId(), change.value());
                }
            }
            if (changes.isEmpty()) {
                return;
            }

            List<EconomyIssue> problems =
                    EconomyEvents.economy().previewConversions(player.server, changes);
            List<EconomyIssue> errors = problems.stream().filter(EconomyIssue::isError).toList();
            if (!errors.isEmpty()) {
                // Nothing is applied and nothing is announced. The screen
                // keeps the staged values, so an administrator can fix the
                // one that is wrong instead of retyping the set.
                player.sendSystemMessage(
                        Component.translatable("tablegames.command.economy.refused")
                                .withStyle(ChatFormatting.RED));
                for (EconomyIssue error : errors) {
                    player.sendSystemMessage(Component.literal(error.message()));
                }
                return;
            }

            EconomyData data = EconomyData.get(player.server);
            changes.forEach(data::setConversion);
            // Rebuilt once, after all of them. Rebuilding inside the loop
            // would validate half-applied states that this exists to skip.
            EconomyEvents.economy().rebuild(player.server);

            changes.forEach((itemId, value) -> AdminNotices.announce(player,
                    Component.translatable("tablegames.command.economy.set",
                            ItemIds.displayName(itemId), value)));
            menu.clearInput();

            for (EconomyIssue issue : problems) {
                if (!issue.isError()) {
                    player.sendSystemMessage(Component.literal(issue.message())
                            .withStyle(ChatFormatting.YELLOW));
                }
            }
        });
    }
}

package com.github.arrivedbog593.tablegames.platform.network;

import com.github.arrivedbog593.tablegames.TableGames;
import com.github.arrivedbog593.tablegames.platform.economy.CreditFormat;
import com.github.arrivedbog593.tablegames.platform.menu.CashierMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/**
 * A tray on its way to being counted.
 * <p>
 * Carries what the button was showing. The tray's worth is a data slot that
 * resyncs every tick, so the window in which it can go stale is far shorter
 * than the catalog's — but a player watching "+1,234" and being paid
 * something else is the same broken promise, however brief the gap, and the
 * check costs one number.
 *
 * @param expectedValue what the screen said the tray was worth
 */
public record CashierConvertPayload(long expectedValue) implements CustomPacketPayload {

    public static final Type<CashierConvertPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(TableGames.MOD_ID, "cashier_convert"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CashierConvertPayload>
            STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, CashierConvertPayload::expectedValue,
            CashierConvertPayload::new);

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleOnServer(CashierConvertPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            // The tray belongs to the menu, so there is nothing to convert
            // unless this player has one open.
            if (!(player.containerMenu instanceof CashierMenu menu)) {
                return;
            }

            CashierMenu.ConvertResult result = menu.convertTray(player, payload.expectedValue());
            if (!result.success()) {
                player.sendSystemMessage(Component.translatable(result.failureKey(),
                        CreditFormat.of(result.expected()),
                        CreditFormat.of(result.actual())));
                return;
            }
            player.sendSystemMessage(Component.translatable(
                    "tablegames.cashier.converted", CreditFormat.of(result.credits())));
        });
    }
}

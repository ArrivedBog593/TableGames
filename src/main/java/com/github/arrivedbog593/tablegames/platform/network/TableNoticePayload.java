package com.github.arrivedbog593.tablegames.platform.network;

import com.github.arrivedbog593.tablegames.TableGames;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/**
 * Why a table or a machine turned somebody down.
 * <p>
 * Sent instead of a chat line or an action bar message because neither works
 * for the person it is meant for. Whoever is refused is almost always playing,
 * with the screen open: the action bar is drawn under the screen, so they
 * would have to close it to read why, and it fades in seconds; the chat piles
 * up one refusal per misclick. Only the client knows whether a table screen
 * is open, so it decides — inside the screen if one is, on the action bar if
 * not.
 *
 * @param message what to tell them
 */
public record TableNoticePayload(Component message) implements CustomPacketPayload {

    public static final Type<TableNoticePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(TableGames.MOD_ID, "table_notice"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TableNoticePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ComponentSerialization.TRUSTED_STREAM_CODEC, TableNoticePayload::message,
                    TableNoticePayload::new);

    /** Tells one player why they were refused. Nothing happens for a null message. */
    public static void tell(ServerPlayer player, Component message) {
        if (message != null) {
            PacketDistributor.sendToPlayer(player, new TableNoticePayload(message));
        }
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleOnClient(TableNoticePayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
                com.github.arrivedbog593.tablegames.client.TableNotices.show(payload.message()));
    }
}

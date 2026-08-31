package com.github.arrivedbog593.tablegames.platform.network;

import com.github.arrivedbog593.tablegames.TableGames;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/**
 * Tells a client to open a table's screen.
 * <p>
 * Game tables do not use menus. A menu exists to move items between
 * containers, and no card game moves items: a poker table has cards, a
 * betting round, and a pot, none of which fit in slots. Inheriting from
 * {@code AbstractContainerScreen} would also nail every game to the width of
 * a nine-column inventory and draw one underneath whether it belongs there or
 * not.
 * <p>
 * The cost is that the server cannot call {@code openMenu}, so it says which
 * screen to open and the client opens it. That indirection is what lets each
 * game pick its own size and layout, which is the whole point.
 *
 * @param gameId   which game's screen to show
 * @param tablePos the table it belongs to, sent back with every action
 */
public record OpenTableScreenPayload(String gameId, BlockPos tablePos)
        implements CustomPacketPayload {

    public static final Type<OpenTableScreenPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(TableGames.MOD_ID, "open_table_screen"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenTableScreenPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, OpenTableScreenPayload::gameId,
                    BlockPos.STREAM_CODEC, OpenTableScreenPayload::tablePos,
                    OpenTableScreenPayload::new);

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleOnClient(OpenTableScreenPayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
                com.github.arrivedbog593.tablegames.client.TableScreens.open(payload));
    }
}

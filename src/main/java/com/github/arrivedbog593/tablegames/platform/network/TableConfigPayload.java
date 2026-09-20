package com.github.arrivedbog593.tablegames.platform.network;

import com.github.arrivedbog593.tablegames.TableGames;
import com.github.arrivedbog593.tablegames.engine.game.Game;
import com.github.arrivedbog593.tablegames.engine.table.SettingSpec;
import com.github.arrivedbog593.tablegames.engine.table.TableSettings;
import com.github.arrivedbog593.tablegames.platform.block.GameBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A table's settings, from its configuration screen.
 * <p>
 * Taken whole or refused whole, like the command that sets limits. Nothing
 * the client says is trusted: reach, permission, the game and every value
 * are checked again here, and values the chosen game does not declare are
 * ignored rather than stored.
 *
 * @param tablePos which table
 * @param gameId   the game the screen was built for, which must still be the one hosted
 * @param values   that game's settings, by id
 */
public record TableConfigPayload(BlockPos tablePos, String gameId, Map<String, Long> values)
        implements CustomPacketPayload {

    /** More than any game declares, and small enough that a forged packet costs nothing. */
    public static final int MAX_VALUES = 32;

    private static final int MAX_ID_LENGTH = 128;

    /** Same reach as the table's other actions. */
    private static final double REACH_SQUARED = 64.0;

    public static final Type<TableConfigPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(TableGames.MOD_ID, "table_config"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TableConfigPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, TableConfigPayload::tablePos,
                    ByteBufCodecs.stringUtf8(MAX_ID_LENGTH), TableConfigPayload::gameId,
                    ByteBufCodecs.map(HashMap::new, ByteBufCodecs.stringUtf8(MAX_ID_LENGTH),
                            ByteBufCodecs.VAR_LONG, MAX_VALUES), TableConfigPayload::values,
                    TableConfigPayload::new);

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleOnServer(TableConfigPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (player.distanceToSqr(payload.tablePos().getCenter()) > REACH_SQUARED) {
                return;
            }
            if (!(player.level().getBlockEntity(payload.tablePos())
                    instanceof GameBlockEntity table)) {
                return;
            }
            Component refusal = table.configureRefusal(player);
            if (refusal != null) {
                refuse(player, refusal);
                return;
            }

            // The screen was built for one game. If the table moved on while
            // it was open, these numbers were meant for something else.
            Optional<Game> hosted = table.game();
            if (hosted.isEmpty() || !hosted.get().id().equals(payload.gameId())) {
                refuse(player, Component.translatable("tablegames.config.game_changed"));
                return;
            }

            TableSettings proposed = table.settings();
            for (SettingSpec spec : hosted.get().settings()) {
                Long value = payload.values().get(spec.id());
                if (value == null) {
                    continue;
                }
                if (!spec.accepts(value)) {
                    refuse(player, Component.translatable("tablegames.config.refused"));
                    return;
                }
                proposed = proposed.with(spec, value);
            }
            Optional<String> problem = table.applySettings(proposed);
            if (problem.isPresent()) {
                refuse(player, Component.translatable(problem.get()));
                return;
            }

            player.sendSystemMessage(Component.translatable(table.hasPendingSettings()
                    ? "tablegames.config.saved_pending"
                    : "tablegames.config.saved"));
        });
    }

    private static void refuse(ServerPlayer player, Component reason) {
        player.sendSystemMessage(reason.copy().withStyle(ChatFormatting.RED));
    }
}

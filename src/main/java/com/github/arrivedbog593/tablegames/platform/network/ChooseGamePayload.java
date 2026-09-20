package com.github.arrivedbog593.tablegames.platform.network;

import com.github.arrivedbog593.tablegames.TableGames;
import com.github.arrivedbog593.tablegames.engine.game.Game;
import com.github.arrivedbog593.tablegames.platform.block.TableBlock;
import com.github.arrivedbog593.tablegames.platform.block.TableBlockEntity;
import com.github.arrivedbog593.tablegames.platform.game.Games;
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

import java.util.Optional;

/**
 * Picks the game a table hosts, or takes it away.
 * <p>
 * Either way the table is left unconfigured, and the player is shown the next
 * step: a chosen game's settings, or the list of games again. Nobody plays
 * until those settings are saved.
 * <p>
 * Nothing the client says is trusted: reach, permission and the game id are
 * checked again here.
 *
 * @param tablePos which table
 * @param gameId   what it should host, empty to go back to hosting nothing
 */
public record ChooseGamePayload(BlockPos tablePos, String gameId) implements CustomPacketPayload {

    private static final int MAX_ID_LENGTH = 128;

    /** Same reach as the table's other actions. */
    private static final double REACH_SQUARED = 64.0;

    public static final Type<ChooseGamePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(TableGames.MOD_ID, "choose_game"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChooseGamePayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, ChooseGamePayload::tablePos,
                    ByteBufCodecs.stringUtf8(MAX_ID_LENGTH), ChooseGamePayload::gameId,
                    ChooseGamePayload::new);

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleOnServer(ChooseGamePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (player.distanceToSqr(payload.tablePos().getCenter()) > REACH_SQUARED) {
                return;
            }
            if (!(player.level().getBlockEntity(payload.tablePos())
                    instanceof TableBlockEntity table)) {
                return;
            }
            if (payload.gameId().isEmpty()) {
                // Back: whoever may empty the table, which includes the owner
                // of a house game they may not otherwise touch.
                if (!table.mayClear(player)) {
                    refuse(player, Component.translatable("tablegames.command.table.not_yours"));
                    return;
                }
                if (table.game().isPresent()) {
                    table.setGame(null);
                }
            } else {
                Optional<Game> chosen = Games.registry().get(payload.gameId());
                if (chosen.isEmpty()) {
                    return;
                }
                // A registered game is not the same as a game a table can
                // host: slots are registered too, and belong in a cabinet.
                // The picker never offered it, so a client asking for one is
                // not a player who misclicked.
                if (!Games.fitsOnATable(chosen.get())) {
                    return;
                }
                if (!table.mayHost(player, chosen.get())) {
                    refuse(player, Component.translatable(table.mayClear(player)
                            ? "tablegames.table.house_game_staff_only"
                            : "tablegames.command.table.not_yours"));
                    return;
                }
                // Only an empty table takes a new game this way. Somebody else
                // may have picked one while this list was open, and theirs
                // stands: the player is shown it instead.
                if (table.game().isEmpty()) {
                    table.setGame(chosen.get());
                }
            }
            // Whatever the table now hosts may be a game somebody else picked
            // meanwhile, and not one this player may set up.
            Component refusal = table.configureRefusal(player);
            if (refusal != null) {
                refuse(player, refusal);
                return;
            }
            TableBlock.openSetup(player, table);
        });
    }

    private static void refuse(ServerPlayer player, Component reason) {
        player.sendSystemMessage(reason.copy().withStyle(ChatFormatting.RED));
    }
}

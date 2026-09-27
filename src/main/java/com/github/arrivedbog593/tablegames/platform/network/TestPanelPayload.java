package com.github.arrivedbog593.tablegames.platform.network;

import com.github.arrivedbog593.tablegames.TableGames;
import com.github.arrivedbog593.tablegames.platform.block.GameBlockEntity;
import com.github.arrivedbog593.tablegames.platform.economy.CreditFormat;
import com.github.arrivedbog593.tablegames.platform.economy.SandboxFunds;
import com.github.arrivedbog593.tablegames.platform.item.TestKeyItem;
import com.mojang.logging.LogUtils;
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
import org.slf4j.Logger;

/**
 * What the tester chose on the test panel.
 * <p>
 * Checked again from the beginning, like every other packet from a client:
 * a development build, a test key in hand, within reach, a game block. A
 * release build ignores this entirely, so a client that sent one to a real
 * server would be told nothing and change nothing.
 *
 * @param pos             which block
 * @param testTable       whether it should be a test table
 * @param bank            the pretend bank
 * @param startingBalance what each player should start with
 * @param rig             what the next round should land on, empty to let it fall
 */
public record TestPanelPayload(BlockPos pos, boolean testTable, long bank, long startingBalance,
                               String rig) implements CustomPacketPayload {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Same reach as a table's other actions. */
    private static final double REACH_SQUARED = 64.0;

    public static final Type<TestPanelPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(TableGames.MOD_ID, "test_panel"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TestPanelPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, TestPanelPayload::pos,
                    ByteBufCodecs.BOOL, TestPanelPayload::testTable,
                    ByteBufCodecs.VAR_LONG, TestPanelPayload::bank,
                    ByteBufCodecs.VAR_LONG, TestPanelPayload::startingBalance,
                    ByteBufCodecs.stringUtf8(64), TestPanelPayload::rig,
                    TestPanelPayload::new);

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleOnServer(TestPanelPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!TestKeyItem.enabled() || !(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!(player.getMainHandItem().getItem() instanceof TestKeyItem)
                    && !(player.getOffhandItem().getItem() instanceof TestKeyItem)) {
                return;
            }
            if (player.distanceToSqr(payload.pos().getCenter()) > REACH_SQUARED) {
                return;
            }
            if (!(player.level().getBlockEntity(payload.pos()) instanceof GameBlockEntity block)) {
                return;
            }
            apply(player, block, payload);
        });
    }

    private static void apply(ServerPlayer player, GameBlockEntity block, TestPanelPayload payload) {
        if (!payload.testTable()) {
            Component refusal = block.makeRealTable();
            player.sendSystemMessage(refusal != null
                    ? refusal.copy().withStyle(ChatFormatting.RED)
                    : Component.translatable("tablegames.debug.now_real").withStyle(ChatFormatting.GREEN));
            return;
        }

        // Only a change of figures starts the ledger over. Sending the same
        // ones back — to change the rigged result, say — keeps whatever the
        // pretend house and players have won and lost so far.
        boolean sameFigures = block.funds() instanceof SandboxFunds sandbox
                && sandbox.bank() == payload.bank()
                && sandbox.startingBalance() == payload.startingBalance();
        if (!sameFigures) {
            Component refusal;
            try {
                refusal = block.makeTestTable(payload.bank(), payload.startingBalance());
            } catch (IllegalArgumentException outOfRange) {
                refusal = Component.translatable("tablegames.debug.bad_figures");
            }
            if (refusal != null) {
                player.sendSystemMessage(refusal.copy().withStyle(ChatFormatting.RED));
                return;
            }
        }

        String rig = payload.rig().isEmpty() ? null : payload.rig();
        if (!block.runtime().rig(rig)) {
            player.sendSystemMessage(Component.translatable("tablegames.debug.no_such_result")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        LOGGER.info("[TableGames] {} set up a test table at {}: bank {}, balance {}, next result {}.",
                player.getGameProfile().getName(), block.getBlockPos().toShortString(),
                payload.bank(), payload.startingBalance(), rig == null ? "random" : rig);
        player.sendSystemMessage(Component.translatable("tablegames.debug.now_test",
                        CreditFormat.of(payload.bank()), CreditFormat.of(payload.startingBalance()),
                        rig == null
                                ? Component.translatable("tablegames.debug.random")
                                : block.runtime().rigOptions().stream()
                                        .filter(option -> option.id().equals(rig))
                                        .findFirst()
                                        .map(option -> option.label())
                                        .orElse(Component.literal(rig)))
                .withStyle(ChatFormatting.GREEN));
    }
}

package com.github.arrivedbog593.tablegames.platform.item;

import com.github.arrivedbog593.tablegames.platform.block.GameBlockEntity;
import com.github.arrivedbog593.tablegames.platform.block.SlotMachineBlock;
import com.github.arrivedbog593.tablegames.platform.network.OpenTestPanelPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Turns a game block into a test table: pretend money, and results chosen in
 * advance.
 * <p>
 * For checking what a game pays and how it sounds without waiting for luck,
 * and without the real bankroll or a real balance ever being touched — a test
 * table plays against a pretend economy of its own, set up on the panel this
 * opens.
 * <p>
 * It works only in a development build. In the jar a server runs it is inert,
 * however it was obtained: a key that chose results and made money in a live
 * economy would be a machine for printing items, and the only safe version of
 * it is one that does not exist there. Every check that stops it is made on
 * the server, here and again when the panel answers.
 * <p>
 * Used before the block gets the click, so it works on any block a game is
 * played at — the ones there are now and the ones still to come — without any
 * of them having to know it exists.
 */
public class TestKeyItem extends Item {

    public TestKeyItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    /** Whether test keys work at all: only outside a release build. */
    public static boolean enabled() {
        return !FMLEnvironment.production;
    }

    @Override
    public @NotNull InteractionResult onItemUseFirst(@NotNull ItemStack stack, UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof SlotMachineBlock) {
            // The reels are in the upper half and the machine in the lower.
            pos = SlotMachineBlock.cabinetOf(state, pos);
        }
        if (!(level.getBlockEntity(pos) instanceof GameBlockEntity block)) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(context.getPlayer() instanceof ServerPlayer player)) {
            return InteractionResult.PASS;
        }
        if (!enabled()) {
            player.displayClientMessage(Component.translatable("tablegames.debug.dev_only")
                    .withStyle(ChatFormatting.RED), true);
            return InteractionResult.FAIL;
        }
        if (block.game().isEmpty()) {
            player.displayClientMessage(Component.translatable("tablegames.debug.no_game"), true);
            return InteractionResult.FAIL;
        }
        PacketDistributor.sendToPlayer(player, OpenTestPanelPayload.of(block, player));
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(@NotNull ItemStack stack, @NotNull TooltipContext context,
                                List<Component> lines, @NotNull TooltipFlag flag) {
        lines.add(Component.translatable("tablegames.test_key.use").withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable(enabled()
                ? "tablegames.test_key.dev_build"
                : "tablegames.test_key.release_build").withStyle(ChatFormatting.DARK_PURPLE));
    }
}

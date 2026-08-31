package com.github.arrivedbog593.tablegames.platform.item;

import com.github.arrivedbog593.tablegames.platform.economy.EconomyData;
import com.github.arrivedbog593.tablegames.platform.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Opens a casino block's settings instead of using it.
 * <p>
 * The card is a key, not the permission. Who may administer the casino lives
 * in a list on {@link EconomyData}; the card only says, "I am here to
 * configure rather than to play". Splitting the two is what makes revoking
 * somebody instant: taking them off the list stops every card they hold,
 * including the ones already in their pockets and the ones nobody can find.
 * <p>
 * It carries the owner's id so a card cannot be handed on, and so one pulled
 * out of the creative menu — which carries nobody's — does nothing. Operators
 * are the exception on purpose: their authority comes from being operators,
 * so a blank card works in their hands and the owner of a server can never
 * lock themselves out of it.
 */
public class AdminKeyItem extends Item {

    /** Where the owner's id is written on the stack. */
    private static final String KEY_OWNER = "tablegames_owner";

    public AdminKeyItem(Properties properties) {
        // Survives death. Somebody who was given administration should not
        // have to ask for it again after a bad fall, and the alternative is a
        // card lying in lava that still counted as issued.
        super(properties.stacksTo(1).fireResistant());
    }

    /** A card bound to one player. */
    public static ItemStack forPlayer(UUID owner) {
        CompoundTag tag = new CompoundTag();
        tag.putIntArray(KEY_OWNER, UUIDUtil.uuidToIntArray(owner));

        ItemStack stack = new ItemStack(ModItems.ADMIN_KEY.get());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    /** Whose card this is if it is anybody's. */
    public static Optional<UUID> ownerOf(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return Optional.empty();
        }
        CompoundTag tag = data.copyTag();
        if (!tag.contains(KEY_OWNER)) {
            return Optional.empty();
        }
        int[] raw = tag.getIntArray(KEY_OWNER);
        return raw.length == 4 ? Optional.of(UUIDUtil.uuidFromIntArray(raw)) : Optional.empty();
    }

    /**
     * Whether this player may configure the casino right now.
     * <p>
     * Two ways in, and the card is only relevant to one of them. An operator
     * needs no card and no listing; anybody else needs to be holding their
     * own card and to still be on the list.
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public static boolean mayAdminister(ServerPlayer player, ItemStack held) {
        if (player.hasPermissions(2)) {
            return true;
        }
        if (!(held.getItem() instanceof AdminKeyItem)) {
            return false;
        }
        // Bound to them, and still listed. Either check alone would be a hole:
        // an unbound card would work for anyone, and a bound one would keep
        // working after they were removed.
        return ownerOf(held).filter(player.getUUID()::equals).isPresent()
                && EconomyData.get(player.server).isAdministrator(player.getUUID());
    }

    /**
     * Removes every card a player is carrying.
     * <p>
     * Best effort, and deliberately not the thing that revokes access — that
     * is the list. A card left in a chest, or on a player who is offline,
     * stops working the moment they come off it, so this only tidies up.
     *
     * @return how many were taken
     */
    public static int takeFrom(ServerPlayer player) {
        int taken = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.getItem() instanceof AdminKeyItem) {
                taken += stack.getCount();
                player.getInventory().setItem(slot, ItemStack.EMPTY);
            }
        }
        return taken;
    }

    /**
     * Refuses to be dropped.
     * <p>
     * A card on the ground is a card anybody can pick up, and picking one up
     * would not grant anything — but it would let somebody hoard another
     * administrator's key out of spite. Vanishing instead means the only way
     * to get one is to be given one.
     */
    @Override
    public boolean onDroppedByPlayer(@NotNull ItemStack stack, @NotNull Player player) {
        if (player instanceof ServerPlayer server) {
            server.displayClientMessage(
                    Component.translatable("tablegames.admin.key_cannot_drop"), true);
        }
        return false;
    }

    @Override
    public void appendHoverText(@NotNull ItemStack stack, @NotNull TooltipContext context,
                                List<Component> lines, @NotNull TooltipFlag flag) {
        lines.add(Component.translatable("tablegames.admin.key_use")
                .withStyle(ChatFormatting.GRAY));

        Optional<UUID> owner = ownerOf(stack);
        if (owner.isEmpty()) {
            lines.add(Component.translatable("tablegames.admin.key_unbound")
                    .withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        // The first block of the id, not a name. The client has no profile
        // cache worth trusting, and whoever is holding the card already knows
        // whether it is theirs — this only has to settle the rare case where
        // two cards end up in the same chest.
        lines.add(Component.translatable("tablegames.admin.key_bound",
                        owner.get().toString().substring(0, 8))
                .withStyle(ChatFormatting.DARK_GRAY));
    }

}
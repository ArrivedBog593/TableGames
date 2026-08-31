package com.github.arrivedbog593.tablegames.platform.economy;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Working out whether items will fit before taking anyone's credits.
 * <p>
 * Shared by the cashier and the shop because getting it subtly wrong in one
 * of them is worse than the small cost of a helper class: a purchase that
 * charges and then cannot deliver is the exact shape of bug players remember.
 */
final class Inventories {

    private Inventories() {
    }

    /**
     * How many of an item the main inventory can still take.
     * <p>
     * Counts partially filled stacks as well as empty slots, so a player
     * holding sixty diamonds in one slot is correctly seen as having room for
     * four more there. Armor and the offhand are excluded: they are worn
     * equipment, not storage.
     */
    static long spaceFor(ServerPlayer player, Item item) {
        return spaceFor(player, new ItemStack(item));
    }

    /**
     * How many copies of a stack the main inventory can still take.
     * <p>
     * Matched with {@link ItemStack#isSameItemSameComponents}, not by item
     * alone. An enchanted sword will not merge with a plain one, so counting
     * the plain one's slot as room would promise space that does not exist —
     * and the shop now sells stacks that carry components.
     */
    static long spaceFor(ServerPlayer player, ItemStack prototype) {
        int maxStack = prototype.getMaxStackSize();
        long room = 0;
        for (int slot = 0; slot < player.getInventory().items.size(); slot++) {
            ItemStack existing = player.getInventory().items.get(slot);
            if (existing.isEmpty()) {
                room += maxStack;
            } else if (ItemStack.isSameItemSameComponents(existing, prototype)
                    && existing.getCount() < maxStack) {
                room += maxStack - existing.getCount();
            }
        }
        return room;
    }

    /**
     * Hands over a number of items, splitting into stacks.
     * <p>
     * Only call once {@link #spaceFor} has confirmed they fit. Anything that
     * somehow does not is dropped rather than deleted: a dropped item can be
     * picked up, a deleted one is simply gone.
     */
    static void give(ServerPlayer player, Item item, long count) {
        give(player, new ItemStack(item), count);
    }

    /** Hands over copies of a stack, components and all. */
    static void give(ServerPlayer player, ItemStack prototype, long count) {
        int maxStack = prototype.getMaxStackSize();
        List<ItemStack> stacks = new ArrayList<>();
        long left = count;
        while (left > 0) {
            int size = (int) Math.min(left, maxStack);
            ItemStack portion = prototype.copy();
            portion.setCount(size);
            stacks.add(portion);
            left -= size;
        }
        for (ItemStack stack : stacks) {
            player.getInventory().add(stack);
            if (!stack.isEmpty()) {
                player.drop(stack, false);
            }
        }
    }
}

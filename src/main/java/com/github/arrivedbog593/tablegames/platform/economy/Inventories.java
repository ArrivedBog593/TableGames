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
     * four more there. Armour and the offhand are excluded: they are worn
     * equipment, not storage.
     */
    static long spaceFor(ServerPlayer player, Item item) {
        int maxStack = new ItemStack(item).getMaxStackSize();
        long room = 0;
        for (int slot = 0; slot < player.getInventory().items.size(); slot++) {
            ItemStack existing = player.getInventory().items.get(slot);
            if (existing.isEmpty()) {
                room += maxStack;
            } else if (existing.getItem() == item && existing.getCount() < maxStack) {
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
        int maxStack = new ItemStack(item).getMaxStackSize();
        List<ItemStack> stacks = new ArrayList<>();
        long left = count;
        while (left > 0) {
            int size = (int) Math.min(left, maxStack);
            stacks.add(new ItemStack(item, size));
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

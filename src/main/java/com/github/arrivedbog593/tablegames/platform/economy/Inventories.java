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
        return snapshot(player).roomFor(prototype);
    }

    /**
     * A working copy of the inventory, for deciding whether several
     * deliveries fit together.
     * <p>
     * One item at a time is not enough once a purchase can name more than
     * one. A player with three free slots asking for sixty-four diamonds,
     * sixty-four emeralds, and sixty-four ingots passes every check taken
     * separately and fails the only one that matters, because the diamonds
     * take the slots the emeralds were counting on. Reserving against a
     * snapshot makes each line see what the lines before it already claimed.
     * <p>
     * A copy, so nothing here can move a real item. The real delivery still
     * goes through {@link #give}, and only once every line has been reserved.
     */
    static Space snapshot(ServerPlayer player) {
        List<ItemStack> slots = new ArrayList<>();
        for (ItemStack existing : player.getInventory().items) {
            slots.add(existing.copy());
        }
        return new Space(slots);
    }

    /** The working copy itself. Not thread safe, and server thread only. */
    static final class Space {

        private final List<ItemStack> slots;

        private Space(List<ItemStack> slots) {
            this.slots = slots;
        }

        /** How many more of this would fit, given everything reserved so far. */
        long roomFor(ItemStack prototype) {
            int maxStack = prototype.getMaxStackSize();
            long room = 0;
            for (ItemStack existing : slots) {
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
         * Claims space for a delivery.
         * <p>
         * Fills partial stacks before opening empty slots, which is the order
         * {@link net.minecraft.world.entity.player.Inventory#add} uses. Getting
         * that order wrong would undercount the room a player really has and
         * refuse purchases that would have fitted.
         *
         * @return how many were reserved; less than asked means it does not fit
         */
        long reserve(ItemStack prototype, long count) {
            int maxStack = prototype.getMaxStackSize();
            long left = count;

            for (int slot = 0; slot < slots.size() && left > 0; slot++) {
                ItemStack existing = slots.get(slot);
                if (existing.isEmpty()
                        || !ItemStack.isSameItemSameComponents(existing, prototype)) {
                    continue;
                }
                int free = maxStack - existing.getCount();
                if (free <= 0) {
                    continue;
                }
                int taken = (int) Math.min(left, free);
                existing.grow(taken);
                left -= taken;
            }

            for (int slot = 0; slot < slots.size() && left > 0; slot++) {
                if (!slots.get(slot).isEmpty()) {
                    continue;
                }
                int taken = (int) Math.min(left, maxStack);
                ItemStack placed = prototype.copy();
                placed.setCount(taken);
                slots.set(slot, placed);
                left -= taken;
            }

            return count - left;
        }
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
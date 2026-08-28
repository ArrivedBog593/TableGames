package com.github.arrivedbog593.tablegames.platform.economy;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/** Helpers for moving between registry ids as text and real items. */
public final class ItemIds {

    private ItemIds() {
    }

    public static boolean exists(String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        return id != null && BuiltInRegistries.ITEM.containsKey(id);
    }

    public static Optional<Item> item(String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null) {
            return Optional.empty();
        }
        return BuiltInRegistries.ITEM.getOptional(id);
    }

    public static String idOf(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    public static String idOf(ItemStack stack) {
        return idOf(stack.getItem());
    }

    /**
     * The item's translated display name, or the raw id if the item is gone.
     * Showing "Iron Ingot" beside the id is what makes a long list readable.
     */
    public static Component displayName(String itemId) {
        return item(itemId)
                .map(item -> new ItemStack(item).getHoverName())
                .orElseGet(() -> Component.literal(itemId));
    }
}
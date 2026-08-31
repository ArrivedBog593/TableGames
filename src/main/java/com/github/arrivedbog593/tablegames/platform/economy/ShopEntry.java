package com.github.arrivedbog593.tablegames.platform.economy;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.item.ItemStack;

/**
 * One thing the shop sells.
 * <p>
 * A whole {@link ItemStack} rather than an item id, so what the shop hands
 * over is what the admin put on sale. The shop used to store an id and a
 * price, which meant an enchanted sword added to the catalog came back out
 * as a plain one — the enchantments were never lost, they were never stored.
 * <p>
 * The stack also carries a count, so an entry can be "a stack of sixteen
 * arrows for 200" rather than only ever one of something.
 * <p>
 * An entry has no id of its own. Its number is its position in the catalog,
 * so removing the third of ten renumbers everything below it, and the list an
 * admin reads never has gaps in it. The cost is that a number only means
 * something for as long as the catalog does not change underneath it: two
 * admins editing at once can have one of them type a number that meant
 * something else a moment ago. That is why every change is broadcast to the
 * other operators and why tab completion shows the item beside each number
 * rather than making anybody work from memory.
 *
 * @param stack what the buyer receives, components and all
 * @param price credits per purchase of this entry
 */
public record ShopEntry(ItemStack stack, long price) {

    public static final Codec<ShopEntry> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    ItemStack.CODEC.fieldOf("stack").forGetter(ShopEntry::stack),
                    Codec.LONG.fieldOf("price").forGetter(ShopEntry::price)
            ).apply(instance, ShopEntry::new));

    public ShopEntry {
        if (price <= 0) {
            throw new IllegalArgumentException("Shop price must be positive: " + price);
        }
        if (stack == null || stack.isEmpty()) {
            throw new IllegalArgumentException("Shop entry has no item");
        }
    }

    /**
     * The registry id of the item, for the checks that reason about items
     * rather than stacks.
     * <p>
     * The exploit validator works at this level on purpose: the cashier
     * values an item by type and ignores components, so an enchanted sword
     * and a plain one convert back for the same credits. Whether a shop price
     * opens a loop therefore depends on the item, not on what is written on
     * it.
     */
    public String itemId() {
        return ItemIds.idOf(stack.getItem());
    }

    /** How many items one purchase delivers. */
    public int count() {
        return stack.getCount();
    }

    /** Whether this entry carries anything beyond a plain item. */
    public boolean hasComponents() {
        return !stack.getComponentsPatch().isEmpty();
    }

    /** A fresh copy of what the buyer receives, safe to hand out. */
    public ItemStack prototype() {
        return stack.copy();
    }
}

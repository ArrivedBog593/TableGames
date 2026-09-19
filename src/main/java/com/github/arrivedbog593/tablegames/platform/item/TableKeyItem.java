package com.github.arrivedbog593.tablegames.platform.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Opens a table's settings instead of sitting down at it.
 * <p>
 * The player's counterpart to {@link AdminKeyItem}, and like it a key rather
 * than a permission. Who may configure a table is the table's business —
 * its owner, the guests they trusted, and whoever has authority over every
 * table — so the key is not bound to anybody and grants nothing. It only
 * says "I am here to set this up", which is why losing one costs nothing
 * and finding one is worth nothing.
 */
public class TableKeyItem extends Item {

    public TableKeyItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public void appendHoverText(@NotNull ItemStack stack, @NotNull TooltipContext context,
                                List<Component> lines, @NotNull TooltipFlag flag) {
        lines.add(Component.translatable("tablegames.table_key.use")
                .withStyle(ChatFormatting.GRAY));
    }
}

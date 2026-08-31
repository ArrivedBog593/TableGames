package com.github.arrivedbog593.tablegames.platform.command;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import java.util.List;

/**
 * Chat pagination in the style of vanilla {@code /help}: a header, a slice of
 * entries, and clickable arrows.
 * <p>
 * Clickable navigation matters more than it looks. A price list runs to dozens
 * of entries, and retyping the command with a different number every time is
 * the kind of friction that stops people using a feature.
 */
public final class Pagination {

    /** Entries per page. Enough to be useful, few enough not to flood chat. */
    public static final int PAGE_SIZE = 8;

    private Pagination() {
    }

    public static int pageCount(int totalEntries) {
        return Math.max(1, (totalEntries + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    /** Clamps a requested page into range. Pages are one-based. */
    public static int clampPage(int requested, int totalEntries) {
        return Math.clamp(requested, 1, pageCount(totalEntries));
    }

    /**
     * Where a page starts in the full list, zero-based.
     * <p>
     * For lists whose entries are numbered by position: the number shown
     * beside the third row of page two is not three, and working that out at
     * each call site is how one of them ends up wrong.
     */
    public static int firstIndex(int page) {
        return (page - 1) * PAGE_SIZE;
    }

    /** The entries belonging on a page, or an empty list if past the end. */
    public static <T> List<T> slice(List<T> entries, int page) {
        int from = firstIndex(page);
        if (from >= entries.size()) {
            return List.of();
        }
        return entries.subList(from, Math.min(from + PAGE_SIZE, entries.size()));
    }

    /**
     * A header line with arrows that rerun the command for the next or
     * previous page.
     *
     * @param titleKey    translation key for the list's name
     * @param baseCommand command to rerun, without the page number, e.g.
     *                    {@code "/tablegames economy list"}
     */
    public static Component header(String titleKey, String baseCommand,
                                   int page, int totalEntries) {
        int pages = pageCount(totalEntries);
        return Component.literal("")
                .append(arrow("<<<", baseCommand, page - 1, page > 1))
                .append(Component.literal(" "))
                .append(Component.translatable(titleKey).withStyle(ChatFormatting.GOLD))
                .append(Component.literal(" "))
                .append(Component.translatable("tablegames.command.page", page, pages)
                        .withStyle(ChatFormatting.GRAY))
                .append(Component.literal(" "))
                .append(arrow(">>>", baseCommand, page + 1, page < pages));
    }

    private static Component arrow(String label, String baseCommand, int target, boolean enabled) {
        if (!enabled) {
            return Component.literal(label).withStyle(ChatFormatting.DARK_GRAY);
        }
        return Component.literal(label)
                .withStyle(style -> style
                        .withColor(ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent(
                                ClickEvent.Action.RUN_COMMAND, baseCommand + " " + target))
                        .withHoverEvent(new HoverEvent(
                                HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("tablegames.command.go_to_page", target))));
    }
}
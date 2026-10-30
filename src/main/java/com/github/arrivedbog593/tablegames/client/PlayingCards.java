package com.github.arrivedbog593.tablegames.client;

import com.github.arrivedbog593.tablegames.engine.card.Card;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Playing cards, drawn rather than textured.
 * <p>
 * The same choice the panels made: a card is a white rectangle with a rank in
 * one corner and a suit under it, and drawing that from rectangles and the
 * game's own font means it reads at any size and needs no image for each of
 * fifty-two faces. Poker and anything else dealt from a {@code Deck} draw
 * theirs here too.
 * <p>
 * Client only.
 */
public final class PlayingCards {

    /** A card big enough for its rank and suit on two lines. */
    public static final int WIDTH = 14;
    public static final int HEIGHT = 20;

    /** How far apart cards in a hand sit: overlapped, with the rank still showing. */
    public static final int STEP = 12;

    private static final int FACE = 0xFFF6F2E8;
    private static final int EDGE = 0xFF3A3A3A;
    private static final int RED = 0xFFC0302C;
    private static final int BLACK = 0xFF1C1C1C;
    private static final int BACK = 0xFF8A2A2A;
    private static final int BACK_PATTERN = 0xFFB04040;

    private PlayingCards() {
    }

    /** One card face up, its top-left corner here. */
    public static void face(GuiGraphics graphics, Font font, int x, int y, Card card) {
        outline(graphics, x, y);
        graphics.fill(x + 1, y + 1, x + WIDTH - 1, y + HEIGHT - 1, FACE);
        int color = card.suit().isRed() ? RED : BLACK;
        String rank = card.rank().code();
        // Ten is the one rank two characters wide. Drawn a pixel further
        // left so it fits inside the face rather than over the edge.
        int rankX = rank.length() > 1 ? x + 1 : x + 2;
        graphics.drawString(font, rank, rankX, y + 2, color, false);
        graphics.drawString(font, String.valueOf(card.suit().symbol()), x + 2, y + 11, color, false);
    }

    /** One card face down. */
    public static void back(GuiGraphics graphics, int x, int y) {
        outline(graphics, x, y);
        graphics.fill(x + 1, y + 1, x + WIDTH - 1, y + HEIGHT - 1, BACK);
        for (int row = y + 3; row < y + HEIGHT - 3; row += 3) {
            graphics.fill(x + 3, row, x + WIDTH - 3, row + 1, BACK_PATTERN);
        }
    }

    private static void outline(GuiGraphics graphics, int x, int y) {
        graphics.fill(x, y, x + WIDTH, y + HEIGHT, EDGE);
    }
}

package com.github.arrivedbog593.tablegames.client;

import com.github.arrivedbog593.tablegames.TableGames;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Where a table's refusal is shown, decided on the one side that knows
 * whether a table screen is open — and the hook that draws it there.
 */
@EventBusSubscriber(modid = TableGames.MOD_ID, value = Dist.CLIENT)
public final class TableNotices {

    private TableNotices() {
    }

    public static void show(Component message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof TableScreen screen) {
            screen.showNotice(message);
        } else {
            // Nothing open, so the action bar is in plain sight.
            minecraft.gui.setOverlayMessage(message, false);
        }
    }

    /**
     * Fires once the screen has drawn all of its own, which a table screen's
     * own {@code render} cannot wait for: each game draws its panel after
     * calling up to the base class. Tooltips come later, but the notice is
     * drawn above the height they use, so it stays on top of them too.
     */
    @SubscribeEvent
    public static void afterScreenDrawn(ScreenEvent.Render.Post event) {
        if (event.getScreen() instanceof TableScreen screen) {
            screen.drawNotice(event.getGuiGraphics());
        }
    }
}

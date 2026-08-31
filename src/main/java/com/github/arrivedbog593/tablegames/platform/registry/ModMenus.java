package com.github.arrivedbog593.tablegames.platform.registry;

import com.github.arrivedbog593.tablegames.TableGames;
import com.github.arrivedbog593.tablegames.platform.menu.AdminShopMenu;
import com.github.arrivedbog593.tablegames.platform.menu.CashierMenu;
import com.github.arrivedbog593.tablegames.platform.menu.ShopMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Menu types the mod adds.
 * <p>
 * Only the cashier and the shop. Those move items between containers, which
 * is what a menu is for. Table games have no slots and open a plain screen
 * instead, so nothing about them belongs here.
 */
public final class ModMenus {

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, TableGames.MOD_ID);

    /**
     * Created with {@code IMenuTypeExtension.create} rather than the vanilla
     * constructor so the client's copy can read the extra data the server
     * sends when the menu opens — in this case which block was clicked.
     */
    public static final DeferredHolder<MenuType<?>, MenuType<CashierMenu>> CASHIER =
            MENUS.register("cashier", () -> IMenuTypeExtension.create(CashierMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<ShopMenu>> SHOP =
            MENUS.register("shop", () -> IMenuTypeExtension.create(ShopMenu::new));

    /**
     * Configuring the shop. A menu because listing something means putting the
     * stack in a slot, which is the only way to describe an item that carries
     * enchantments.
     */
    public static final DeferredHolder<MenuType<?>, MenuType<AdminShopMenu>> ADMIN_SHOP =
            MENUS.register("admin_shop", () -> IMenuTypeExtension.create(AdminShopMenu::new));

    private ModMenus() {
    }

    public static void register(IEventBus modEventBus) {
        MENUS.register(modEventBus);
    }
}

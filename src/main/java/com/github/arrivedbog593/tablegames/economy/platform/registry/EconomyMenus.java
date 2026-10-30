package com.github.arrivedbog593.tablegames.economy.platform.registry;

import com.github.arrivedbog593.tablegames.economy.platform.EconomyModule;
import com.github.arrivedbog593.tablegames.economy.platform.menu.AdminCashierMenu;
import com.github.arrivedbog593.tablegames.economy.platform.menu.AdminShopMenu;
import com.github.arrivedbog593.tablegames.economy.platform.menu.CashierMenu;
import com.github.arrivedbog593.tablegames.economy.platform.menu.ShopMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class EconomyMenus {

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, EconomyModule.MOD_ID);

    public static final DeferredHolder<MenuType<?>, MenuType<CashierMenu>> CASHIER =
            MENUS.register("cashier", () -> IMenuTypeExtension.create(CashierMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<ShopMenu>> SHOP =
            MENUS.register("shop", () -> IMenuTypeExtension.create(ShopMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<AdminShopMenu>> ADMIN_SHOP =
            MENUS.register("admin_shop", () -> IMenuTypeExtension.create(AdminShopMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<AdminCashierMenu>> ADMIN_CASHIER =
            MENUS.register("admin_cashier", () -> IMenuTypeExtension.create(AdminCashierMenu::new));

    private EconomyMenus() {
    }

    public static void register(IEventBus modEventBus) {
        MENUS.register(modEventBus);
    }
}

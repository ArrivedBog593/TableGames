package com.github.arrivedbog593.tablegames.economy.platform.network;

import com.github.arrivedbog593.tablegames.economy.platform.EconomyModule;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * The economy's network channel: the cashier and the shop.
 * <p>
 * Versioned on its own, apart from the games, because it will ship apart from
 * them. Bump it whenever one of these payloads changes shape, so an
 * out-of-date client is refused at login rather than misreading a catalog.
 */
@EventBusSubscriber(modid = EconomyModule.MOD_ID)
public final class EconomyPayloads {

    private static final String VERSION = "1";

    private EconomyPayloads() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);

        registrar.playToClient(
                CashierCatalogPayload.TYPE,
                CashierCatalogPayload.STREAM_CODEC,
                CashierCatalogPayload::handleOnClient);

        registrar.playToClient(
                ShopCatalogPayload.TYPE,
                ShopCatalogPayload.STREAM_CODEC,
                ShopCatalogPayload::handleOnClient);

        registrar.playToServer(
                ShopPurchasePayload.TYPE,
                ShopPurchasePayload.STREAM_CODEC,
                ShopPurchasePayload::handleOnServer);

        registrar.playToServer(
                CashierRedeemPayload.TYPE,
                CashierRedeemPayload.STREAM_CODEC,
                CashierRedeemPayload::handleOnServer);

        registrar.playToServer(
                CashierConvertPayload.TYPE,
                CashierConvertPayload.STREAM_CODEC,
                CashierConvertPayload::handleOnServer);

        registrar.playToServer(
                AdminShopActionPayload.TYPE,
                AdminShopActionPayload.STREAM_CODEC,
                AdminShopActionPayload::handleOnServer);

        registrar.playToServer(
                AdminCashierActionPayload.TYPE,
                AdminCashierActionPayload.STREAM_CODEC,
                AdminCashierActionPayload::handleOnServer);

        registrar.playToServer(
                AdminCashierBatchPayload.TYPE,
                AdminCashierBatchPayload.STREAM_CODEC,
                AdminCashierBatchPayload::handleOnServer);
    }
}

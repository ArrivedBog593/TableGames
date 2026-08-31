package com.github.arrivedbog593.tablegames.platform.network;

import com.github.arrivedbog593.tablegames.TableGames;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * The mod's network channel.
 * <p>
 * The version string is a compatibility gate: bump it whenever a payload's
 * shape changes, so an out-of-date client is refused at login rather than
 * silently misreading packets halfway through a hand.
 */
@EventBusSubscriber(modid = TableGames.MOD_ID)
public final class ModPayloads {

    private static final String VERSION = "3";

    private ModPayloads() {
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

        registrar.playToClient(
                OpenTableScreenPayload.TYPE,
                OpenTableScreenPayload.STREAM_CODEC,
                OpenTableScreenPayload::handleOnClient);

        registrar.playToClient(
                RouletteStatePayload.TYPE,
                RouletteStatePayload.STREAM_CODEC,
                RouletteStatePayload::handleOnClient);

        registrar.playToServer(
                RouletteActionPayload.TYPE,
                RouletteActionPayload.STREAM_CODEC,
                RouletteActionPayload::handleOnServer);

        registrar.playToServer(
                AdminShopActionPayload.TYPE,
                AdminShopActionPayload.STREAM_CODEC,
                AdminShopActionPayload::handleOnServer);

        registrar.playToServer(
                TableActionPayload.TYPE,
                TableActionPayload.STREAM_CODEC,
                TableActionPayload::handleOnServer);

        registrar.playToServer(
                CloseTablePayload.TYPE,
                CloseTablePayload.STREAM_CODEC,
                CloseTablePayload::handleOnServer);
    }
}

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

    private static final String VERSION = "17";

    private ModPayloads() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);

        registrar.playToClient(
                OpenTableScreenPayload.TYPE,
                OpenTableScreenPayload.STREAM_CODEC,
                OpenTableScreenPayload::handleOnClient);

        registrar.playToClient(
                OpenTableConfigPayload.TYPE,
                OpenTableConfigPayload.STREAM_CODEC,
                OpenTableConfigPayload::handleOnClient);

        registrar.playToClient(
                OpenGamePickerPayload.TYPE,
                OpenGamePickerPayload.STREAM_CODEC,
                OpenGamePickerPayload::handleOnClient);

        registrar.playToClient(
                RouletteStatePayload.TYPE,
                RouletteStatePayload.STREAM_CODEC,
                RouletteStatePayload::handleOnClient);

        registrar.playToClient(
                SlotsStatePayload.TYPE,
                SlotsStatePayload.STREAM_CODEC,
                SlotsStatePayload::handleOnClient);

        registrar.playToClient(
                BlackjackStatePayload.TYPE,
                BlackjackStatePayload.STREAM_CODEC,
                BlackjackStatePayload::handleOnClient);

        registrar.playToClient(
                OpenTestPanelPayload.TYPE,
                OpenTestPanelPayload.STREAM_CODEC,
                OpenTestPanelPayload::handleOnClient);

        registrar.playToClient(
                TableNoticePayload.TYPE,
                TableNoticePayload.STREAM_CODEC,
                TableNoticePayload::handleOnClient);

        registrar.playToServer(
                RouletteActionPayload.TYPE,
                RouletteActionPayload.STREAM_CODEC,
                RouletteActionPayload::handleOnServer);

        registrar.playToServer(
                SlotsActionPayload.TYPE,
                SlotsActionPayload.STREAM_CODEC,
                SlotsActionPayload::handleOnServer);

        registrar.playToServer(
                BlackjackActionPayload.TYPE,
                BlackjackActionPayload.STREAM_CODEC,
                BlackjackActionPayload::handleOnServer);

        registrar.playToServer(
                TableActionPayload.TYPE,
                TableActionPayload.STREAM_CODEC,
                TableActionPayload::handleOnServer);

        registrar.playToServer(
                TableConfigPayload.TYPE,
                TableConfigPayload.STREAM_CODEC,
                TableConfigPayload::handleOnServer);

        registrar.playToServer(
                ChooseGamePayload.TYPE,
                ChooseGamePayload.STREAM_CODEC,
                ChooseGamePayload::handleOnServer);

        registrar.playToServer(
                CloseTablePayload.TYPE,
                CloseTablePayload.STREAM_CODEC,
                CloseTablePayload::handleOnServer);

        registrar.playToServer(
                TestPanelPayload.TYPE,
                TestPanelPayload.STREAM_CODEC,
                TestPanelPayload::handleOnServer);
    }
}

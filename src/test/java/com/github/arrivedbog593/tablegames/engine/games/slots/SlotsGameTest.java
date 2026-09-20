package com.github.arrivedbog593.tablegames.engine.games.slots;

import com.github.arrivedbog593.tablegames.engine.session.ActionResult;
import com.github.arrivedbog593.tablegames.engine.session.Outcome;
import com.github.arrivedbog593.tablegames.engine.session.Seat;
import com.github.arrivedbog593.tablegames.engine.table.BuyIn;
import com.github.arrivedbog593.tablegames.engine.table.TableSettings;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlotsGameTest {

    private static final SlotsGame GAME = SlotsGame.standard();
    private static final UUID PLAYER = UUID.randomUUID();

    private static SlotsSession session(long credits, SlotMachine machine) {
        SlotsSession session = GAME.createSession(
                List.of(Seat.forPlayer(0, PLAYER, credits)), new Random(1), machine);
        session.begin();
        return session;
    }

    @Test
    void aWinningSpinSettlesAsTheWinLessTheStake() {
        // Iron on every row: three lines at 10 a line pay 14 times each.
        SlotsSession session = session(1_000, SlotMachineTest.uniform(SlotSymbol.IRON));
        assertTrue(session.submit(PLAYER, new SlotAction.Spin(3, 10, false)).accepted());

        Outcome outcome = session.outcome().orElseThrow();
        assertEquals(3 * 10 * 14 - 30, outcome.payouts().getFirst().delta());
        assertEquals(List.of(PLAYER), outcome.winners());
    }

    @Test
    void aLosingSpinCostsTheStakeOnEveryLine() {
        // Gold, iron and coal, one reel each, never line up three alike.
        SlotsSession session = session(1_000, new SlotMachine(List.of(
                new Reel(List.of(SlotSymbol.GOLD, SlotSymbol.GOLD, SlotSymbol.GOLD)),
                new Reel(List.of(SlotSymbol.IRON, SlotSymbol.IRON, SlotSymbol.IRON)),
                new Reel(List.of(SlotSymbol.COAL, SlotSymbol.COAL, SlotSymbol.COAL))),
                SlotsGame.Payback.P95.paytable()));
        session.submit(PLAYER, new SlotAction.Spin(5, 20, false));
        assertEquals(-100, session.outcome().orElseThrow().payouts().getFirst().delta());
    }

    @Test
    void aReplayCostsNothing() {
        SlotsSession session = session(0, SlotMachineTest.uniform(SlotSymbol.REPLAY));
        ActionResult result = session.submit(PLAYER, new SlotAction.Spin(5, 50, true));
        assertTrue(result.accepted(), "a free spin needs no credits");
        assertEquals(0, session.outcome().orElseThrow().payouts().getFirst().delta());
        assertTrue(session.result().orElseThrow().replay(), "and can win another");
    }

    @Test
    void nobodySpinsForMoreThanTheyHave() {
        SlotsSession session = session(49, SlotMachineTest.uniform(SlotSymbol.IRON));
        assertFalse(session.submit(PLAYER, new SlotAction.Spin(5, 10, false)).accepted());
        assertTrue(session.outcome().isEmpty());
    }

    @Test
    void anUnconfiguredMachineRunsAtNinetyFive() {
        assertEquals(SlotsGame.Payback.P95, GAME.paybackFrom(TableSettings.empty()));
        // Ten credits at one apiece, so a machine nobody has priced behaves
        // as though credits had never been invented.
        assertEquals(1, GAME.denominationFrom(TableSettings.empty()));
        assertEquals(new BuyIn(10, BuyIn.UNLIMITED), GAME.buyIn(TableSettings.empty()).orElseThrow());
    }

    @Test
    void theDenominationPricesTheBuyInAndNothingElse() {
        TableSettings quarter = TableSettings.empty()
                .with(GAME.denomination(), 25)
                .with(GAME.buyInMinimum(), 10)
                .with(GAME.buyInMaximum(), 100);

        assertEquals(25, GAME.denominationFrom(quarter));
        // Ten credits cost 250, a hundred cost 2500: the buy-in is quoted to
        // the rest of the mod in the currency a balance is kept in.
        assertEquals(new BuyIn(250, 2_500), GAME.buyIn(quarter).orElseThrow());
        assertEquals(125, GAME.priceOf(5, quarter));

        // The reels and the paytable never hear about it, so the return is
        // the same on a cheap cabinet and an expensive one.
        assertEquals(GAME.machineFor(TableSettings.empty()).returnToPlayer(1),
                GAME.machineFor(quarter).returnToPlayer(1));
    }

    @Test
    void anUncappedBuyInStaysUncappedWhateverACreditCosts() {
        TableSettings priced = TableSettings.empty()
                .with(GAME.denomination(), 100)
                .with(GAME.buyInMinimum(), 5);
        // Multiplying the "no ceiling" marker would turn it into a real one.
        assertEquals(new BuyIn(500, BuyIn.UNLIMITED), GAME.buyIn(priced).orElseThrow());
    }

    @Test
    void theChosenPaybackIsTheOneTheMachineRuns() {
        // By ordinal rather than by a number typed here: the ladder has grown
        // once already, and a test that hard-codes a position starts lying
        // the moment a level is added in the middle of it.
        TableSettings generous = TableSettings.empty()
                .with(GAME.payback(), SlotsGame.Payback.P97.ordinal());
        assertEquals(SlotsGame.Payback.P97, GAME.paybackFrom(generous));
        assertEquals(SlotsGame.Payback.P97.paytable(), GAME.machineFor(generous).paytable());
    }

    @Test
    void noMachineEverPaysLessThanATighterOne() {
        // The one promise the ladder makes that a player could check by
        // walking between two cabinets. Each level is built from the one
        // beside it, so nothing here is guaranteed by construction.
        SlotsGame.Payback[] ladder = SlotsGame.Payback.values();
        for (int i = 1; i < ladder.length; i++) {
            for (SlotSymbol symbol : SlotSymbol.values()) {
                if (symbol == SlotSymbol.REPLAY) {
                    continue;
                }
                assertTrue(ladder[i].paytable().multipleFor(symbol)
                                >= ladder[i - 1].paytable().multipleFor(symbol),
                        ladder[i] + " pays less than " + ladder[i - 1] + " for " + symbol);
            }
        }
    }

    @Test
    void anInvertedLineStakeIsRefused() {
        TableSettings inverted = TableSettings.empty()
                .with(GAME.betMinimum(), 50)
                .with(GAME.betMaximum(), 10);
        assertEquals(Optional.of("tablegames.setting.problem.bet_inverted"),
                GAME.settingsProblem(inverted));
    }

    @Test
    void aBuyInThatCannotPayForOneLineIsRefused() {
        TableSettings useless = TableSettings.empty()
                .with(GAME.betMinimum(), 500)
                .with(GAME.buyInMinimum(), 100);
        assertEquals(Optional.of("tablegames.setting.problem.buy_in_below_bet"),
                GAME.settingsProblem(useless));
    }

    @Test
    void oneMachineOnePlayer() {
        assertEquals(1, GAME.maxPlayers());
        assertTrue(GAME.isHouseBanked());
    }
}

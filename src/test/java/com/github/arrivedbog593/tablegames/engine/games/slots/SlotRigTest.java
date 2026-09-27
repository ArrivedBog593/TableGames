package com.github.arrivedbog593.tablegames.engine.games.slots;

import com.github.arrivedbog593.tablegames.engine.session.Outcome;
import com.github.arrivedbog593.tablegames.engine.session.Seat;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlotRigTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final SlotsGame GAME = SlotsGame.standard();
    private static final Paytable CARD = SlotsGame.Payback.P95.paytable();
    private static final SlotMachine MACHINE = new SlotMachine(GAME.reels(), CARD);

    /** Every result on the card can be asked for, and the reels can show it. */
    @Test
    void everyTargetOnTheCardCanBeLanded() {
        for (SlotRig.Target target : SlotRig.targets(CARD)) {
            int[] stops = SlotRig.stopsFor(MACHINE, target)
                    .orElseThrow(() -> new AssertionError("No stops show " + target.id()));
            SlotMachine.SpinResult result = MACHINE.resultAt(stops, Payline.MAX);
            assertTrue(SlotRig.lands(result, target), target.id() + " not on the middle line");
        }
    }

    /** Best first, so the jackpot is the first thing a tester is offered. */
    @Test
    void targetsRunFromTheJackpotDownToNothing() {
        List<SlotRig.Target> targets = SlotRig.targets(CARD);
        assertEquals(SlotSymbol.NETHERITE, targets.getFirst().symbol());
        assertEquals(SlotRig.Kind.NOTHING, targets.getLast().kind());
    }

    /**
     * A forced three of a kind pays what the card says for it and nothing on
     * top, so the meter shows the result that was asked for and no accident.
     */
    @Test
    void aForcedThreeOfAKindPaysExactlyItsLine() {
        for (SlotRig.Target target : SlotRig.targets(CARD)) {
            if (target.kind() != SlotRig.Kind.THREE || target.symbol() == SlotSymbol.REPLAY) {
                continue;
            }
            int[] stops = SlotRig.stopsFor(MACHINE, target).orElseThrow();
            SlotMachine.SpinResult result = MACHINE.resultAt(stops, Payline.MAX);
            assertEquals(CARD.multipleFor(target.symbol()), result.totalMultiple(),
                    target.id() + " paid something else as well");
        }
    }

    @Test
    void nothingPaysNothingOnAnyLine() {
        SlotRig.Target nothing = SlotRig.target(CARD, "nothing").orElseThrow();
        int[] stops = SlotRig.stopsFor(MACHINE, nothing).orElseThrow();
        SlotMachine.SpinResult result = MACHINE.resultAt(stops, Payline.MAX);
        assertEquals(0, result.totalMultiple());
        assertFalse(result.replay());
    }

    @Test
    void theJackpotIsThreeNetheriteOnAPaidLine() {
        int[] stops = SlotRig.stopsFor(MACHINE, SlotRig.target(CARD, "netherite").orElseThrow())
                .orElseThrow();
        SlotMachine.SpinResult result = MACHINE.resultAt(stops, 1);
        assertTrue(result.threeOnAPaidLine(SlotSymbol.NETHERITE));
        assertFalse(result.threeOnAPaidLine(SlotSymbol.DIAMOND));
    }

    /** A rigged session settles through the same rules as any other spin. */
    @Test
    void aRiggedSpinIsPaidByTheCard() {
        int[] stops = SlotRig.stopsFor(MACHINE, SlotRig.target(CARD, "diamond").orElseThrow())
                .orElseThrow();
        SlotsSession session = new SlotsSession(
                List.of(Seat.forPlayer(0, ALICE, 1_000)), new Random(1), MACHINE);
        session.rig(stops);
        session.begin();
        session.submit(ALICE, new SlotAction.Spin(1, 10, false));

        Outcome outcome = session.outcome().orElseThrow();
        assertEquals(10L * CARD.multipleFor(SlotSymbol.DIAMOND) - 10,
                outcome.payouts().getFirst().delta());
    }

    @Test
    void riggingRefusesStopsTheReelsDoNotHave() {
        SlotsSession session = new SlotsSession(
                List.of(Seat.forPlayer(0, ALICE, 1_000)), new Random(1), MACHINE);
        assertThrows(IllegalArgumentException.class, () -> session.rig(new int[]{0, 0}));
        assertThrows(IllegalArgumentException.class, () -> session.rig(new int[]{0, 0, 9_999}));
    }
}

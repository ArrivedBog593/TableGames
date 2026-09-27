package com.github.arrivedbog593.tablegames.engine.games.roulette;

import com.github.arrivedbog593.tablegames.engine.session.Outcome;
import com.github.arrivedbog593.tablegames.engine.session.Seat;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RouletteRigTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());

    private static Pocket pocket(RouletteWheel wheel, int number) {
        return wheel.pockets().stream()
                .filter(p -> p.number() == number && !p.doubleZero())
                .findFirst()
                .orElseThrow();
    }

    private static RouletteSession session() {
        RouletteSession session = new RouletteSession(
                List.of(Seat.forPlayer(0, ALICE, 10_000)), new Random(7),
                RouletteWheel.EUROPEAN, new BetLimits(10, 10_000, 10, 10_000));
        session.begin();
        return session;
    }

    /** The ball lands where it is told, whatever the random source says. */
    @Test
    void aRiggedWheelLandsOnThePocket() {
        for (int number : new int[]{0, 17, 36}) {
            RouletteSession session = session();
            session.rig(pocket(RouletteWheel.EUROPEAN, number));
            session.submit(ALICE, new RouletteAction.Place(RouletteBet.outside(BetType.RED, 10)));
            session.spin();
            assertEquals(number, session.result().orElseThrow().number());
        }
    }

    /** And the wagers on it are paid by the same rules as ever: 35 to 1 on a straight-up. */
    @Test
    void aStraightUpOnTheRiggedNumberPaysThirtyFiveToOne() {
        RouletteSession session = session();
        Pocket seventeen = pocket(RouletteWheel.EUROPEAN, 17);
        session.rig(seventeen);
        session.submit(ALICE, new RouletteAction.Place(RouletteBet.straightUp(seventeen, 100)));
        session.spin();

        Outcome outcome = session.outcome().orElseThrow();
        assertEquals(3_500, outcome.payouts().getFirst().delta());
    }

    @Test
    void riggingRefusesAPocketTheWheelDoesNotHave() {
        RouletteSession session = session();
        assertThrows(IllegalArgumentException.class, () -> session.rig(Pocket.doubleZeroPocket()));
    }
}
